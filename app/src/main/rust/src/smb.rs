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
use smb2::client::stream::FileReader;
use smb2::client::stream::FileWriter;
use smb2::client::tree::Tree;
use smb2::{ClientConfig, SmbClient};
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

/// The token a caller looks for to tell "this server wants credentials" from
/// every other way a connection fails.
///
/// The distinction has to cross the JNI boundary, which carries an exception
/// message and nothing else, so the message is where it goes.
pub const AUTH_REQUIRED: &str = "smb-auth-required";

/// Mark an error that means the server refused the session.
///
/// Three kinds say the same thing from a caller's side. `AuthRequired` is a
/// server that will not talk to anyone without a name; `SigningRequired` is one
/// that will not talk to an anonymous session, which is unsigned by definition;
/// `AccessDenied` is a session it accepted and then refused the share of, which
/// is what a server reports when it allows the anonymous login and denies the
/// tree connect -- the shape a share with `guest ok = no` has. The answer to
/// all three is the same one: ask for credentials.
///
/// Generic over nothing: the body hands back an `anyhow` error, and a caller
/// that has the server's own error type converts it before the body returns.
fn asking_for_credentials<T>(result: Result<T>) -> Result<T> {
    result.map_err(
        |error| match error.downcast_ref::<smb2::Error>().map(smb2::Error::kind) {
            Some(
                smb2::ErrorKind::AuthRequired
                | smb2::ErrorKind::SigningRequired
                | smb2::ErrorKind::AccessDenied,
            ) => error.context(AUTH_REQUIRED),
            _ => error,
        },
    )
}

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
        (
            self.host.clone(),
            self.port,
            self.share.clone(),
            self.user.clone(),
            self.domain.clone(),
        )
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

#[allow(clippy::type_complexity)]
fn sessions() -> &'static AsyncMutex<HashMap<(String, u16, String, String, String), Session>> {
    static SESSIONS: OnceLock<AsyncMutex<HashMap<(String, u16, String, String, String), Session>>> =
        OnceLock::new();
    SESSIONS.get_or_init(|| AsyncMutex::new(HashMap::new()))
}

type FileHandle = Arc<AsyncMutex<Option<FileReader>>>;
type WriteHandle = Arc<AsyncMutex<Option<FileWriter>>>;

/// Open file handles, each with the target it was opened from. The target is
/// kept so a handle can be reopened after its session dies.
fn handles() -> &'static Mutex<HashMap<u64, (Target, FileHandle)>> {
    static HANDLES: OnceLock<Mutex<HashMap<u64, (Target, FileHandle)>>> = OnceLock::new();
    HANDLES.get_or_init(|| Mutex::new(HashMap::new()))
}

fn handle_entry(handle: u64) -> Result<(Target, FileHandle)> {
    handles()
        .lock()
        .unwrap()
        .get(&handle)
        .cloned()
        .ok_or_else(|| anyhow!("Invalid SMB handle {handle}"))
}

fn writers() -> &'static Mutex<HashMap<u64, WriteHandle>> {
    static WRITERS: OnceLock<Mutex<HashMap<u64, WriteHandle>>> = OnceLock::new();
    WRITERS.get_or_init(|| Mutex::new(HashMap::new()))
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
    Ok(Session {
        client,
        tree,
        last_used: Instant::now(),
    })
}

/// The session for a target, connecting when it is missing or has gone stale.
async fn session_in(
    guard: &mut HashMap<(String, u16, String, String, String), Session>,
    target: &Target,
) -> Result<()> {
    let key = target.key();
    let stale = match guard.get(&key) {
        Some(session) => session.last_used.elapsed() >= IDLE_TIMEOUT,
        None => true,
    };
    if stale {
        let session = asking_for_credentials(connect(target).await)?;
        guard.insert(key, session);
    }
    Ok(())
}

/// Whether a failure is worth another attempt on a session built from scratch.
///
/// A phone's network goes away and comes back -- Wi-Fi that slept, a server that
/// dropped a connection it had no use for -- and what is gone is the connection
/// the request travelled on, not the file. Reporting "cannot list" for it is
/// what makes a browser look like it lost files that are right there.
fn retryable(error: &anyhow::Error) -> bool {
    matches!(
        error.downcast_ref::<smb2::Error>().map(smb2::Error::kind),
        Some(
            smb2::ErrorKind::ConnectionLost
                | smb2::ErrorKind::SessionExpired
                | smb2::ErrorKind::TimedOut
                | smb2::ErrorKind::Io
        )
    )
}

/// Run an operation on the cached session for a target.
///
/// One retry, on a connection built from scratch: the first attempt can lose its
/// connection between the request going out and the answer coming back, and a
/// caller asking for a listing has no way to tell that from a directory that is
/// not there.
///
/// A loop rather than a closure around the body: the body contains `await`, and
/// an async closure is not something this crate's Rust has. The loop runs it
/// once more on the way out, and the body stays written once.
macro_rules! with_session {
    ($target:expr, |$session:ident| $body:expr) => {{
        let target: &Target = $target;
        let mut retried = false;
        loop {
            let outcome = {
                let mut guard = sessions().lock().await;
                session_in(&mut guard, target).await?;
                let $session = guard
                    .get_mut(&target.key())
                    .ok_or_else(|| anyhow!("Session vanished"))?;
                $session.last_used = Instant::now();
                asking_for_credentials($body)
            };
            match outcome {
                Err(error) if !retried && retryable(&error) => {
                    // The session is the thing that is gone, so it goes before
                    // the second attempt builds another one.
                    retried = true;
                    sessions().lock().await.remove(&target.key());
                    log::debug!(target: "smb", "retrying after: {error:#}");
                }
                outcome => break outcome,
            }
        }
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
            let entries = timeout(
                OP_TIMEOUT,
                session.client.list_directory(&mut session.tree, path),
            )
            .await
            .map_err(|_| anyhow!("Timed out listing {path:?}"))??;
            Ok(entries
                .into_iter()
                .filter(|e| e.name != "." && e.name != "..")
                .map(|e| Entry {
                    name: e.name,
                    is_directory: e.is_directory,
                    size: e.size,
                })
                .collect())
        })
    })
}

pub struct Stat {
    pub is_directory: bool,
    pub size: u64,
    pub modified_millis: i64,
}

/// The disk shares a server offers.
///
/// A session is keyed by host and share, so this needs a share to hang one on.
/// `IPC$` is the share every server has, and it is also the share the listing
/// travels over: `list_shares` opens the `srvsvc` named pipe and asks for
/// `NetShareEnumAll` there, so which tree the session was built on is
/// irrelevant. Admin shares (names ending in `$`) are dropped by `smb2`.
pub fn list_shares(
    host: &str,
    port: u16,
    user: &str,
    pass: &str,
    domain: &str,
) -> Result<Vec<String>> {
    let target = Target {
        host: host.to_owned(),
        port,
        share: "IPC$".to_owned(),
        sub: String::new(),
        user: user.to_owned(),
        pass: pass.to_owned(),
        domain: domain.to_owned(),
    };
    runtime().block_on(async {
        with_session!(&target, |session| {
            let shares = timeout(OP_TIMEOUT, session.client.list_shares())
                .await
                .map_err(|_| anyhow!("Timed out listing the shares of {host}"))??;
            Ok(shares.into_iter().map(|share| share.name).collect())
        })
    })
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
            Ok(Stat {
                is_directory: info.is_directory,
                size: info.size,
                modified_millis,
            })
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
            handles().lock().unwrap().insert(
                handle,
                (target.clone(), Arc::new(AsyncMutex::new(Some(reader)))),
            );
            Ok((handle, size))
        })
    })
}

/// The target an open handle belongs to, so the window cache can key by file
/// rather than by handle and survive a close and reopen.
pub fn target_of(handle: u64) -> Option<Target> {
    handles()
        .lock()
        .unwrap()
        .get(&handle)
        .map(|(target, _)| target.clone())
}

fn read_once(handle: u64, offset: u64, len: u64) -> Result<Vec<u8>> {
    let (_, reader) = handle_entry(handle)?;
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

/// Reopen the file behind a handle on a fresh session.
fn reopen(handle: u64, target: &Target) -> Result<()> {
    runtime().block_on(async {
        // Drop the cached session first. The handle died with it, and the
        // session macro would otherwise hand back the same dead connection.
        sessions().lock().await.remove(&target.key());
        let reader = with_session!(target, |session| {
            let Session { client, tree, .. } = session;
            // The server's error is converted here rather than by the caller:
            // the body has to hand back one error type for the macro to read
            // the status off, and this path used to leave it to its caller.
            timeout(OP_TIMEOUT, client.open_file_reader(tree, &target.sub))
                .await
                .map_err(|_| anyhow!("Timed out reopening {:?}", target.sub))?
                .map_err(anyhow::Error::from)
        })?;
        let (_, file) = handle_entry(handle)?;
        *file.lock().await = Some(reader);
        Ok(())
    })
}

/// Read a range. Short reads only happen at EOF; a read past the end is empty.
///
/// A server drops idle sessions, and a handle belongs to the session that opened
/// it, so reconnecting is not enough by itself -- the file has to be opened
/// again. Without this, reading after an idle gap fails and callers see a
/// truncated file: the archive reader reports a damaged zip and, before the
/// null-context fix, crashed.
pub fn read(handle: u64, offset: u64, len: u64) -> Result<Vec<u8>> {
    match read_once(handle, offset, len) {
        Ok(data) => Ok(data),
        Err(first) => {
            let (target, _) = handle_entry(handle)?;
            log::debug!(target: "smb", "read of handle {handle} failed, reopening: {first:#}");
            reopen(handle, &target)?;
            read_once(handle, offset, len)
        }
    }
}

/// Explicitly close the remote file handle. Dropping FileReader is not enough:
/// smb2 can only send SMB CLOSE from its async close method.
pub fn close(handle: u64) -> Result<()> {
    let entry = handles().lock().unwrap().remove(&handle);
    let Some((_, reader)) = entry else {
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

/// Create (truncating) a remote file for writing and return its handle.
/// The returned writer owns its own connection and tree handle, so it can be
/// driven by `write`/`write_close` later without holding the session lock.
pub fn create(target: &Target) -> Result<u64> {
    runtime().block_on(async {
        with_session!(target, |session| {
            let Session { client, tree, .. } = session;
            let writer = timeout(OP_TIMEOUT, client.create_file_writer(tree, &target.sub))
                .await
                .map_err(|_| anyhow!("Timed out creating {:?}", target.sub))??;
            static NEXT: AtomicU64 = AtomicU64::new(1);
            let handle = NEXT.fetch_add(1, Ordering::Relaxed);
            writers()
                .lock()
                .unwrap()
                .insert(handle, Arc::new(AsyncMutex::new(Some(writer))));
            Ok(handle)
        })
    })
}

/// Push a chunk of bytes to a writer opened with `create`.
pub fn write(handle: u64, data: Vec<u8>) -> Result<()> {
    let writer = writers()
        .lock()
        .unwrap()
        .get(&handle)
        .cloned()
        .ok_or_else(|| anyhow!("Invalid SMB write handle {handle}"))?;
    runtime().block_on(async {
        let mut guard = writer.lock().await;
        let writer = guard
            .as_mut()
            .ok_or_else(|| anyhow!("SMB write handle {handle} is closed"))?;
        timeout(OP_TIMEOUT, writer.write_chunk(&data))
            .await
            .map_err(|_| anyhow!("Timed out writing {} bytes", data.len()))?
            .map_err(anyhow::Error::from)
    })
}

/// Flush and close the writer, returning the total bytes written.
pub fn write_close(handle: u64) -> Result<u64> {
    let writer = writers().lock().unwrap().remove(&handle);
    let Some(writer) = writer else {
        return Ok(0);
    };

    runtime().block_on(async {
        let mut guard = writer.lock().await;
        let Some(writer) = guard.take() else {
            return Ok(0);
        };
        timeout(OP_TIMEOUT, writer.finish())
            .await
            .map_err(|_| anyhow!("Timed out closing SMB write handle {handle}"))?
            .map_err(anyhow::Error::from)
    })
}

/// Create a directory on the share. Errors if it already exists, matching the
/// `mustCreate` contract of the surrounding filesystem layer.
pub fn mkdir(target: &Target) -> Result<()> {
    runtime().block_on(async {
        with_session!(target, |session| {
            let path = &target.sub;
            let Session { client, tree, .. } = session;
            let conn = client.connection_mut();
            timeout(OP_TIMEOUT, tree.create_directory(conn, path))
                .await
                .map_err(|_| anyhow!("Timed out creating directory {path:?}"))??;
            Ok(())
        })
    })
}

/// Delete a file or (empty) directory. Tries the file form first, then the
/// directory form, so the caller does not need to know the entry type.
pub fn delete(target: &Target) -> Result<()> {
    runtime().block_on(async {
        with_session!(target, |session| {
            let path = &target.sub;
            let Session { client, tree, .. } = session;
            let conn = client.connection_mut();
            // `timeout(..).await` yields Result<Result<(), Error>, Elapsed>, so the
            // outer level only says whether it timed out. Testing that alone meant
            // the directory branch was never reached: delete_file fails on a
            // directory, which read as "fine, done", and the directory stayed
            // behind after its contents had been removed.
            let as_file = timeout(OP_TIMEOUT, tree.delete_file(conn, path)).await;
            if matches!(as_file, Ok(Ok(()))) {
                return Ok(());
            }
            timeout(OP_TIMEOUT, tree.delete_directory(conn, path))
                .await
                .map_err(|_| anyhow!("Timed out deleting {path:?}"))??;
            Ok(())
        })
    })
}

/// Rename/move a file within the same share. `to_sub` is the destination path
/// relative to the share root, on the same host/share as `from`.
pub fn rename(from: &Target, to_sub: &str) -> Result<()> {
    runtime().block_on(async {
        with_session!(from, |session| {
            let Session { client, tree, .. } = session;
            let conn = client.connection_mut();
            timeout(OP_TIMEOUT, tree.rename(conn, &from.sub, to_sub))
                .await
                .map_err(|_| anyhow!("Timed out renaming {:?} -> {:?}", from.sub, to_sub))??;
            Ok(())
        })
    })
}

/// Drop every cached session. Called when a location's credentials change, so
/// the next call re-authenticates instead of reusing the old login.
pub fn invalidate() {
    runtime().block_on(sessions().lock()).clear();
}
