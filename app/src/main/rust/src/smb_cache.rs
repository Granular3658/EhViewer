//! In-memory (memfd) cache for SMB file contents, exposed as real mmap-able
//! file descriptors.
//!
//! A remote SMB file is fetched once into a `memfd_create(2)` region (RAM, no
//! disk) and handed to Android as a file descriptor. Because that fd is a
//! genuine file descriptor, the decode layer can `mmap` it exactly like a local
//! file: no one-shot pipe (which cannot be mmap'd), and no per-image direct
//! buffer copied into the heap (which is what used to OOM while browsing).
//!
//! Why memfd and not ashmem: an ashmem fd's driver never fills `i_size`, so
//! `fstat` reports 0 and `FileChannel.map()` refuses to size the mapping. A
//! memfd is a real anonymous file — `ftruncate` sets the size, so `fstat`
//! reports the true length. memfd is also where Android itself is heading:
//! ashmem was removed from the Linux staging tree in 5.18, and Android 17
//! requires launching devices to support the `memfd_file` SELinux class.
//! Verified on both test devices (kernel 4.4 and 4.19) that `untrusted_app` may
//! call `memfd_create` and that the resulting fd reports the correct size.
//!
//! Regions are reference-counted. Every consumer that maps one bumps `pins`;
//! only regions with `pins == 0` may be evicted by the LRU. That guarantees a
//! region libwebp is still decoding lazily from is never reclaimed underneath
//! it — the consumer releases it (which drops `pins`) only after it has called
//! `dispose()` and closed the descriptor.

#![cfg(feature = "android-26")]

use crate::smb::{self, Target};
use anyhow::{Result, anyhow, ensure};
use libc::{MAP_SHARED, PROT_READ, PROT_WRITE, mmap, munmap};
use std::collections::HashMap;
use std::ffi::CString;
use std::io::Error;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Mutex, OnceLock};

/// (host, port, share, sub, user, domain, pass, size) — `size` is part of the
/// key so a changed file is re-fetched rather than served stale.
type Key = (String, u16, String, String, String, String, String, u64);

/// `F_SEAL_FUTURE_WRITE` is not in libc; added in Linux 5.1 and backported to
/// most Android kernels, but absent on the 4.4 test device.
const F_SEAL_FUTURE_WRITE: libc::c_int = 0x0010;

/// A `memfd_create(2)` region: an anonymous, RAM-backed file with a real size.
struct Memfd {
    fd: OwnedFd,
}

impl Memfd {
    fn create(size: u64) -> Result<Self> {
        // SAFETY: plain syscall; `name` is a valid NUL-terminated C string.
        let raw = unsafe {
            libc::syscall(
                libc::SYS_memfd_create,
                c"ehviewer-smb-cache".as_ptr(),
                libc::MFD_CLOEXEC | libc::MFD_ALLOW_SEALING,
            )
        };
        if raw < 0 {
            return Err(anyhow!("memfd_create failed: {}", Error::last_os_error()));
        }
        // SAFETY: memfd_create returned a fresh fd that we now own.
        let fd = unsafe { OwnedFd::from_raw_fd(raw as libc::c_int) };
        // SAFETY: ftruncate on an fd we own. This is what makes fstat (and thus
        // FileChannel.map) see the real length.
        let rc = unsafe { libc::ftruncate(fd.as_raw_fd(), size as libc::off_t) };
        if rc != 0 {
            return Err(anyhow!(
                "ftruncate memfd failed: {}",
                Error::last_os_error()
            ));
        }
        Ok(Self { fd })
    }

    /// Best-effort read-only downgrade. Sealing is an optimisation, never a
    /// correctness requirement (we are the only writer and we never resize), so
    /// a failure is not fatal: kernel 4.4 has no `F_SEAL_FUTURE_WRITE` (EINVAL),
    /// in which case we fall back to blocking resize only (SIGBUS guard), which
    /// every kernel >= 3.17 supports.
    fn seal_read_only(&self) {
        let fd = self.fd.as_raw_fd();
        // SAFETY: fcntl on an fd we own.
        if unsafe { libc::fcntl(fd, libc::F_ADD_SEALS, F_SEAL_FUTURE_WRITE) } == 0 {
            return;
        }
        // SAFETY: as above.
        unsafe {
            libc::fcntl(
                fd,
                libc::F_ADD_SEALS,
                libc::F_SEAL_SHRINK | libc::F_SEAL_GROW,
            );
        }
    }
}

struct Entry {
    key: Key,
    mem: Memfd,
    size: u64,
    pins: u32,
}

struct Cache {
    by_id: HashMap<u64, Entry>,
    by_key: HashMap<Key, u64>,
    lru: Vec<u64>,
    total: u64,
}

static CACHE: OnceLock<Mutex<Cache>> = OnceLock::new();
static NEXT_ID: AtomicU64 = AtomicU64::new(1);

fn cache() -> &'static Mutex<Cache> {
    CACHE.get_or_init(|| {
        Mutex::new(Cache {
            by_id: HashMap::new(),
            by_key: HashMap::new(),
            lru: Vec::new(),
            total: 0,
        })
    })
}

/// Cap on the unpinned (evictable) tail, in bytes. Configurable at runtime via
/// [`set_cache_limit_mb`] so the user can trade RAM for cache hits. The reader's
/// page cache bounds how many regions are simultaneously pinned, so this only
/// needs to bound the leftover history of pages you have already scrolled past.
static MAX_BYTES: AtomicU64 = AtomicU64::new(256 * 1024 * 1024);

/// Set the evictable cap in mebibytes. Called once at startup (and on change)
/// from Kotlin with the user's SMB memory-cache preference.
pub fn set_cache_limit_mb(mb: u64) {
    let bytes = mb.saturating_mul(1024 * 1024);
    MAX_BYTES.store(bytes, Ordering::Relaxed);
}

fn touch(lru: &mut Vec<u64>, id: u64) {
    if let Some(pos) = lru.iter().position(|&x| x == id) {
        lru.remove(pos);
    }
    lru.push(id);
}

/// Evict the oldest entries whose `pins == 0` until `total` is within `MAX_BYTES`.
/// Pinned entries are always skipped, so this never reclaims a live mapping.
fn evict() {
    let cap = MAX_BYTES.load(Ordering::Relaxed);
    let mut guard = cache().lock().unwrap();
    while guard.total > cap {
        let victim = guard
            .lru
            .iter()
            .copied()
            .find(|&id| guard.by_id.get(&id).is_some_and(|e| e.pins == 0));
        let Some(id) = victim else { break };
        if let Some(entry) = guard.by_id.remove(&id) {
            guard.by_key.remove(&entry.key);
            guard.total = guard.total.saturating_sub(entry.size);
            guard.lru.retain(|&x| x != id);
            drop(entry); // closes the memfd
        }
    }
}

/// Hand a consumer its own descriptor for a cached region.
///
/// Deliberately NOT `dup(2)`: `dup` shares the *file position* with the cached
/// descriptor, and the consumers are not position-independent. Android only made
/// `AssetFileDescriptor.AutoCloseInputStream` read with `pread` in 15; on
/// Android 11-14 it reads with `read(2)`, which uses that shared position. With
/// every consumer sharing one position, the first decode advances it past the
/// header and each later decode of the same file reads from the wrong offset --
/// which is what made the animated-WebP pre-check see garbage on the Android 11
/// test device. (The mmap re-check hid it, because mmap ignores the position.)
///
/// Opening `/proc/self/fd/N` creates a fresh open file description, so every
/// consumer starts at offset 0 with a position of its own. `O_RDONLY` also makes
/// the descriptor read-only, which the seals cannot guarantee on kernels without
/// `F_SEAL_FUTURE_WRITE` (Linux 4.4 returns EINVAL for it).
fn open_for_consumer(mem: &Memfd) -> Result<i32> {
    match reopen(mem) {
        Ok(fd) => Ok(fd),
        Err(e) => {
            // Last resort: `dup` + rewind. Not race-free -- a concurrent consumer
            // can move the shared position between the rewind and its read -- but
            // still better than refusing to serve the file, because the caller
            // would drop to the pipe, which cannot be mmap'd, and the
            // animated-WebP decoder needs a real mmap-able descriptor. It also
            // matches what the platform's own static decoder does before reading.
            log::warn!(target: "memfd", "reopen /proc/self/fd failed, using dup+rewind: {e:#}");
            // SAFETY: memfd fds are regular file descriptors and can be dup'd.
            let fd = unsafe { libc::dup(mem.fd.as_raw_fd()) };
            if fd < 0 {
                return Err(anyhow!(
                    "dup memfd failed: {}",
                    std::io::Error::last_os_error()
                ));
            }
            // SAFETY: plain lseek on an fd we own.
            unsafe { libc::lseek(fd, 0, libc::SEEK_SET) };
            Ok(fd)
        }
    }
}

/// Reopen a cached region through `/proc/self/fd`, yielding a new open file
/// description: its own file position, and read-only.
fn reopen(mem: &Memfd) -> Result<i32> {
    let path = CString::new(format!("/proc/self/fd/{}", mem.fd.as_raw_fd()))?;
    // SAFETY: `path` is a valid NUL-terminated C string; `open` takes no mode
    // argument without O_CREAT.
    let fd = unsafe { libc::open(path.as_ptr(), libc::O_RDONLY | libc::O_CLOEXEC) };
    if fd < 0 {
        return Err(anyhow!(
            "open {} failed: {}",
            path.to_string_lossy(),
            std::io::Error::last_os_error()
        ));
    }
    Ok(fd)
}

/// Fetch a remote file into an in-memory region and return a dup'd fd plus a
/// cache key. On any failure returns `Err`, and the caller falls back to the
/// streaming pipe path. The fd must be freed by the caller (it owns the dup);
/// the backing region lives on in the cache until `release_memfd` is called.
pub fn open_memfd(target: &Target) -> Result<(i32, u64, u64)> {
    let stat = smb::stat(target)?;
    ensure!(!stat.is_directory, "refusing to cache a directory");
    let size = stat.size;
    ensure!(size > 0, "empty file cannot be cached");
    let key: Key = (
        target.host.clone(),
        target.port,
        target.share.clone(),
        target.sub.clone(),
        target.user.clone(),
        target.domain.clone(),
        target.pass.clone(),
        size,
    );

    // Fast path: already resident.
    {
        let mut guard = cache().lock().unwrap();
        if let Some(&id) = guard.by_key.get(&key) {
            if let Some(entry) = guard.by_id.get_mut(&id) {
                entry.pins += 1;
            }
            touch(&mut guard.lru, id);
            let entry = guard
                .by_id
                .get(&id)
                .ok_or_else(|| anyhow!("lost cache entry"))?;
            let fd = open_for_consumer(&entry.mem)?;
            return Ok((fd, size, id));
        }
    }

    // Slow path: pull the whole file into a fresh memfd region.
    let (handle, _) = smb::open(target)?;
    let fetched = (|| -> Result<Memfd> {
        let mem = Memfd::create(size)?;
        let fd = mem.fd.as_raw_fd();
        // SAFETY: a fresh memfd of `size` bytes, mapped read/write so we can
        // copy the file into it, then downgraded to read-only.
        let ptr = unsafe {
            mmap(
                std::ptr::null_mut(),
                size as libc::size_t,
                PROT_READ | PROT_WRITE,
                MAP_SHARED,
                fd,
                0,
            )
        };
        if ptr == libc::MAP_FAILED {
            return Err(anyhow!("mmap memfd region failed"));
        }
        let slice = unsafe { std::slice::from_raw_parts_mut(ptr as *mut u8, size as usize) };
        let mut offset: u64 = 0;
        let chunk = 1024 * 1024u64;
        while offset < size {
            let len = chunk.min(size - offset);
            // A single SMB read can fail transiently (timeout / network blip),
            // especially for large files read in many chunks. Retry a few times
            // before giving up: without this, one transient failure aborts the
            // whole in-memory path and the file drops back to the non-mmap pipe,
            // which the animated decoder cannot mmap.
            let mut last_err = None;
            let mut bytes = None;
            for _ in 0..3 {
                match smb::read(handle, offset, len) {
                    Ok(b) => {
                        bytes = Some(b);
                        break;
                    }
                    Err(e) => last_err = Some(e),
                }
            }
            let bytes = match bytes {
                Some(b) => b,
                None => return Err(last_err.unwrap_or_else(|| anyhow!("smb read failed"))),
            };
            if bytes.is_empty() {
                break;
            }
            slice[offset as usize..offset as usize + bytes.len()].copy_from_slice(&bytes);
            offset += bytes.len() as u64;
        }
        unsafe { munmap(ptr, size as libc::size_t) };
        mem.seal_read_only();
        Ok(mem)
    })();
    // The remote handle must be closed regardless of success or failure.
    let _ = smb::close(handle);
    let mem = fetched?;

    let mut guard = cache().lock().unwrap();
    let id = if let Some(&id) = guard.by_key.get(&key) {
        id
    } else {
        let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
        guard.by_id.insert(
            id,
            Entry {
                key: key.clone(),
                mem,
                size,
                pins: 0,
            },
        );
        guard.by_key.insert(key.clone(), id);
        guard.lru.push(id);
        guard.total = guard.total.saturating_add(size);
        id
    };
    // Whether we inserted or found a resident region, this consumer holds a
    // reference: bump the pin so the LRU cannot evict it under us.
    if let Some(entry) = guard.by_id.get_mut(&id) {
        entry.pins += 1;
    }
    touch(&mut guard.lru, id);
    drop(guard);

    evict();

    let guard = cache().lock().unwrap();
    let entry = guard
        .by_id
        .get(&id)
        .ok_or_else(|| anyhow!("lost cache entry"))?;
    let fd = open_for_consumer(&entry.mem)?;
    Ok((fd, size, id))
}

/// Drop a consumer's reference to a cached region. Only once `pins` returns to
/// 0 does the region become eligible for LRU eviction.
pub fn release_memfd(id: u64) {
    let mut guard = cache().lock().unwrap();
    if let Some(entry) = guard.by_id.get_mut(&id) {
        entry.pins = entry.pins.saturating_sub(1);
    }
}
