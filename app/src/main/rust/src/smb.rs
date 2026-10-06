//! Read-only SMB2 backend for download locations.
//!
//! A share is addressed by [`Target`], sessions are cached per
//! (host, port, share, user, domain) and dropped after a minute of idleness.
//! Reads go through `read_at`, so a caller can fetch ranges in any order
//! without holding the session lock: an open file is an `Arc<FileReader>` that
//! outlives the client borrow.
//!
//! Verified against a local Samba server: guest/anonymous, NTLM, SMB2 and
//! SMB3 dialects. Servers that force encryption on the whole connection are
//! rejected during session setup - see `docs` in the commit message.

use anyhow::{Result, anyhow};
use smb2::{ClientConfig, SmbClient};
use smb2::client::stream::FileReader;
use smb2::client::tree::Tree;
use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant, UNIX_EPOCH};
use tokio::runtime::Runtime;
use tokio::sync::Mutex as AsyncMutex;
use tokio::time::timeout;

const CONNECT_TIMEOUT: Duration = Duration::from_secs(15);
const OP_TIMEOUT: Duration = Duration::from_secs(10);
const READ_TIMEOUT: Duration = Duration::from_secs(30);
const IDLE_TIMEOUT: Duration = Duration::from_secs(60);

pub const DEFAULT_PORT: u16 = 445;

#[derive(Clone, PartialEq, Eq, Hash, Debug)]
pub struct Target {
    pub host: String,
    pub port: u16,
    pub share: String,
    /// Path inside the share, `/`-separated. Empty means the share root.
    pub sub: String,
    pub user: String,
    pub pass: String,
    pub domain: String,
}

impl Target {
    fn key(&self) -> (String, u16, String, String, String) {
        (self.host.clone(), self.port, self.share.clone(), self.user.clone(), self.domain.clone())
    }

    fn addr(&self) -> String {
        format!("{}:{}", self.host, self.port)
    }
}

struct Session {
    client: SmbClient,
    tree: Tree,
    last_used: Instant,
}

fn runtime() -> &'static Runtime {
    static RT: OnceLock<Runtime> = OnceLock::new();
    RT.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()
            .expect("Failed to build SMB runtime")
    })
}

fn sessions() -> &'static AsyncMutex<HashMap<(String, u16, String, String, String), Session>> {
    static SESSIONS: OnceLock<AsyncMutex<HashMap<(String, u16, String, String, String), Session>>> = OnceLock::new();
    SESSIONS.get_or_init(|| AsyncMutex::new(HashMap::new()))
}

type FileHandle = Arc<AsyncMutex<Option<FileReader>>>;

fn handles() -> &'static Mutex<HashMap<u64, FileHandle>> {
    static HANDLES: OnceLock<Mutex<HashMap<u64, FileHandle>>> = OnceLock::new();
    HANDLES.get_or_init(|| Mutex::new(HashMap::new()))
}

async fn connect(target: &Target) -> Result<Session> {
    let config = ClientConfig {
        addr: target.addr(),
        timeout: CONNECT_TIMEOUT,
        username: target.user.clone(),
        password: target.pass.clone(),
        domain: target.domain.clone(),
        auto_reconnect: true,
        compression: true,
        dfs_enabled: false,
        dfs_target_overrides: Default::default(),
        connect_options: None,
    };
    let mut client = SmbClient::connect(config).await?;
    let tree = client.connect_share(&target.share).await?;
    Ok(Session { client, tree, last_used: Instant::now() })
}

/// Borrow the cached session, connecting (or reconnecting) when it is missing
/// or has been idle for too long.
macro_rules! with_session {
    ($target:expr, |$session:ident| $body:expr) => {{
        let target: &Target = $target;
        let mut guard = sessions().lock().await;
        let key = target.key();
        let stale = match guard.get(&key) {
            Some(session) => session.last_used.elapsed() >= IDLE_TIMEOUT,
            None => true,
        };
        if stale {
            let session = connect(target).await?;
            guard.insert(key.clone(), session);
        }
        let $session = guard.get_mut(&key).ok_or_else(|| anyhow!("Session vanished"))?;
        $session.last_used = Instant::now();
        $body
    }};
}

pub struct Entry {
    pub name: String,
    pub is_directory: bool,
    pub size: u64,
}

/// List one directory. `.` and `..` are dropped: the server reports them and
/// the download scanner would otherwise treat them as galleries.
pub fn list_dir(target: &Target) -> Result<Vec<Entry>> {
    runtime().block_on(async {
        with_session!(target, |session| {
            let path = &target.sub;
            let entries = timeout(OP_TIMEOUT, session.client.list_directory(&mut session.tree, path))
                .await
                .map_err(|_| anyhow!("Timed out listing {path:?}"))??;
            Ok(entries
                .into_iter()
                .filter(|e| e.name != "." && e.name != "..")
                .map(|e| Entry { name: e.name, is_directory: e.is_directory, size: e.size })
                .collect())
        })
    })
}

pub struct Stat {
    pub is_directory: bool,
    pub size: u64,
    pub modified_millis: i64,
}

pub fn stat(target: &Target) -> Result<Stat> {
    runtime().block_on(async {
        with_session!(target, |session| {
            let path = &target.sub;
            let info = timeout(OP_TIMEOUT, session.client.stat(&mut session.tree, path))
                .await
                .map_err(|_| anyhow!("Timed out stat-ing {path:?}"))??;
            let modified_millis = info
                .modified
                .to_system_time()
                .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
                .map(|d| d.as_millis() as i64)
                .unwrap_or(0);
            Ok(Stat { is_directory: info.is_directory, size: info.size, modified_millis })
        })
    })
}

/// Open a file and return its handle along with the size known at open time.
pub fn open(target: &Target) -> Result<(u64, u64)> {
    runtime().block_on(async {
        with_session!(target, |session| {
            let path = &target.sub;
            let Session { client, tree, .. } = session;
            let reader = timeout(OP_TIMEOUT, client.open_file_reader(tree, path))
                .await
                .map_err(|_| anyhow!("Timed out opening {path:?}"))??;
            let size = reader.size();
            static NEXT: AtomicU64 = AtomicU64::new(1);
            let handle = NEXT.fetch_add(1, Ordering::Relaxed);
            handles()
                .lock()
                .unwrap()
                .insert(handle, Arc::new(AsyncMutex::new(Some(reader))));
            Ok((handle, size))
        })
    })
}

/// Read a range. Short reads only happen at EOF; a read past the end is empty.
pub fn read(handle: u64, offset: u64, len: u64) -> Result<Vec<u8>> {
    let reader = handles()
        .lock()
        .unwrap()
        .get(&handle)
        .cloned()
        .ok_or_else(|| anyhow!("Invalid SMB handle {handle}"))?;
    runtime().block_on(async {
        let guard = reader.lock().await;
        let reader = guard
            .as_ref()
            .ok_or_else(|| anyhow!("SMB handle {handle} is closed"))?;
        timeout(READ_TIMEOUT, reader.read_at(offset, len))
            .await
            .map_err(|_| anyhow!("Timed out reading {len} bytes at {offset}"))?
            .map_err(anyhow::Error::from)
    })
}

/// Explicitly close the remote file handle. Dropping FileReader is not enough:
/// smb2 can only send SMB CLOSE from its async close method.
pub fn close(handle: u64) -> Result<()> {
    let reader = handles().lock().unwrap().remove(&handle);
    let Some(reader) = reader else {
        return Ok(());
    };

    runtime().block_on(async {
        let mut guard = reader.lock().await;
        let Some(reader) = guard.take() else {
            return Ok(());
        };
        timeout(OP_TIMEOUT, reader.close())
            .await
            .map_err(|_| anyhow!("Timed out closing SMB handle {handle}"))?
            .map_err(anyhow::Error::from)
    })
}

/// Drop every cached session. Called when a location's credentials change, so
/// the next call re-authenticates instead of reusing the old login.
pub fn invalidate() {
    runtime().block_on(sessions().lock()).clear();
}
