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
//! only regions with `pins == 0` may be evicted. That guarantees a
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
use std::sync::atomic::{AtomicU8, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

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
    /// Second-chance bit, set when the region is read (a cache hit) or when a
    /// consumer releases it, and cleared when the eviction hand gives it a
    /// reprieve. A region inserted by a one-way scan starts with the bit clear,
    /// so the scan is evicted before the pages the user was actually reading.
    accessed: bool,
}

/// A window of a remote file, kept for the archive reader.
///
/// Keyed by file identity -- target plus size -- rather than by handle, so
/// closing and reopening the same archive keeps hitting it. Nothing holds a
/// window across a call: it is only ever copied out under the cache lock, never
/// handed out as a descriptor, so windows carry no pin count, unlike the memfd
/// regions.
struct Window {
    key: Key,
    index: u64,
    bytes: Arc<[u8]>,
    accessed: bool,
}

struct Cache {
    by_id: HashMap<u64, Entry>,
    by_key: HashMap<Key, u64>,
    /// (file identity, window index) -> id, for the archive reader's windows.
    windows: HashMap<(Key, u64), u64>,
    window_by_id: HashMap<u64, Window>,
    /// Size per open handle, resolved once so a window can be keyed by file.
    sizes: HashMap<u64, u64>,
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
            windows: HashMap::new(),
            window_by_id: HashMap::new(),
            sizes: HashMap::new(),
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

/// Move `id` to the newest end of the eviction list.
fn bump(lru: &mut Vec<u64>, id: u64) {
    if let Some(pos) = lru.iter().position(|&x| x == id) {
        lru.remove(pos);
    }
    lru.push(id);
}

/// Record that `id` was read just now: set its second-chance bit and make it the
/// newest entry. Called on a cache hit, and on release -- the latter is what
/// aligns this tier with the page cache above it. The page cache drops a page
/// only once the reader has moved past it, so at that moment the region is the
/// most likely one to be wanted again, not the least.
fn touch(cache: &mut Cache, id: u64) {
    if let Some(entry) = cache.by_id.get_mut(&id) {
        entry.accessed = true;
        bump(&mut cache.lru, id);
    }
}

/// Evict entries until `total` is within `MAX_BYTES`.
///
/// Pinned entries are always skipped, so this never reclaims a live mapping.
/// Among the rest it is a CLOCK rather than a plain LRU: the oldest unpinned
/// entry gets one reprieve (moved to the newest end, second-chance bit cleared)
/// before it is actually dropped. A plain LRU is pathological for a long one-way
/// scan -- which is what scrolling quickly through a gallery is -- because every
/// page the user just read is pushed out by the page after it, so scrolling back
/// re-fetches everything. The reprieve keeps the pages that were read more than
/// once (the ones a reversal actually returns to) at the expense of the scan.
/// Each reprieve clears a bit, so the sweep always terminates.
fn evict() {
    let cap = MAX_BYTES.load(Ordering::Relaxed);
    let mut guard = cache().lock().unwrap();
    while guard.total > cap {
        // A window is always evictable; a memfd region only once nothing maps it.
        let victim = guard.lru.iter().copied().find(|&id| {
            guard.by_id.get(&id).is_some_and(|e| e.pins == 0)
                || guard.window_by_id.contains_key(&id)
        });
        // Nothing is evictable, so the cap cannot be met -- give up rather than
        // spin. That makes the cap a *soft* limit: `total` is allowed to exceed
        // it, and does whenever more regions are in use than it allows. A reader
        // holds every page it has decoded, so a gallery of large images sits well
        // above a small cap and nothing is wrong: the cap governs only what is
        // kept *beyond* what is in use, which is what makes going back to a page
        // the page cache has already dropped cheaper than reading it again.
        //
        // The pin is what makes this safe. A region is pinned while a consumer
        // holds it -- see the module docs -- so the cap can never take memory
        // from under a decoder, and setting it to zero is a valid choice rather
        // than a way to break playback.
        let Some(id) = victim else { break };
        let reprieved = if let Some(e) = guard.by_id.get_mut(&id) {
            let was_accessed = e.accessed;
            e.accessed = false;
            was_accessed
        } else if let Some(w) = guard.window_by_id.get_mut(&id) {
            let was_accessed = w.accessed;
            w.accessed = false;
            was_accessed
        } else {
            false
        };
        if reprieved {
            bump(&mut guard.lru, id);
            continue;
        }
        if let Some(entry) = guard.by_id.remove(&id) {
            guard.by_key.remove(&entry.key);
            guard.total = guard.total.saturating_sub(entry.size);
            drop(entry); // closes the memfd
        } else if let Some(window) = guard.window_by_id.remove(&id) {
            guard.windows.remove(&(window.key, window.index));
            guard.total = guard.total.saturating_sub(window.bytes.len() as u64);
        }
        guard.lru.retain(|&x| x != id);
    }
}

/// Window size for the archive reader's read-through cache.
///
/// One window is one round trip, so this is the bandwidth-delay product of the
/// link: 1 Gbit/s is 125 MB/s, and at 3 ms that is ~375 KiB. 512 KiB covers a
/// gigabit LAN with headroom while keeping a seek's first byte quick -- a window
/// is fetched synchronously, so a much larger one trades seek latency for bulk
/// throughput.
const WINDOW: u64 = 512 * 1024;

/// Read `len` bytes at `offset` from an open handle, serving whole windows out
/// of the cache.
///
/// The archive reader walks a file by seeking, and every seek used to throw away
/// the single window the C side kept, so returning to an area cost a fresh round
/// trip. Windows live in the same cache, under the same cap and the same
/// eviction, as the whole-file memfd regions, so an archive pass and a gallery
/// of images cannot each claim the whole budget.
pub fn read_window(handle: u64, offset: u64, len: u64) -> Result<Vec<u8>> {
    if len == 0 {
        return Ok(Vec::new());
    }
    let key = identity(handle)?;
    let end = offset.saturating_add(len);
    let mut out = Vec::with_capacity(len as usize);
    let mut pos = offset;
    while pos < end {
        let index = pos / WINDOW;
        let bytes = window(&key, handle, index)?;
        let from = (pos - index * WINDOW) as usize;
        if from >= bytes.len() {
            break; // end of file
        }
        let take = ((bytes.len() - from) as u64).min(end - pos) as usize;
        out.extend_from_slice(&bytes[from..from + take]);
        pos += take as u64;
    }
    Ok(out)
}

/// The identity a handle's file is cached under: its target plus its size, so a
/// changed file is never served from an older file's windows. The size costs one
/// `stat` per open handle, which is why it is remembered rather than re-read.
fn identity(handle: u64) -> Result<Key> {
    let known = cache().lock().unwrap().sizes.get(&handle).copied();
    let target = smb::target_of(handle).ok_or_else(|| anyhow!("unknown handle {handle}"))?;
    let size = match known {
        Some(size) => size,
        None => {
            let size = smb::stat(&target)?.size;
            cache().lock().unwrap().sizes.insert(handle, size);
            size
        }
    };
    Ok((
        target.host,
        target.port,
        target.share,
        target.sub,
        target.user,
        target.domain,
        target.pass,
        size,
    ))
}

/// One window, from the cache or from the share. A short result means the offset
/// reached the end of the file, which is how the SMB layer reports EOF.
fn window(key: &Key, handle: u64, index: u64) -> Result<Arc<[u8]>> {
    let offset = index * WINDOW;
    {
        let mut guard = cache().lock().unwrap();
        // Cloning the key for the lookup is a few small allocations against a
        // network round trip, which is what a miss costs.
        let hit = guard.windows.get(&(key.clone(), index)).copied();
        if let Some(id) = hit {
            if let Some(entry) = guard.window_by_id.get_mut(&id) {
                entry.accessed = true;
            }
            bump(&mut guard.lru, id);
            if let Some(entry) = guard.window_by_id.get(&id) {
                return Ok(entry.bytes.clone());
            }
        }
    }
    let fetched: Arc<[u8]> = smb::read(handle, offset, WINDOW)?.into();
    let mut guard = cache().lock().unwrap();
    // Another reader may have filled it while the lock was released; keep one.
    let raced = guard.windows.get(&(key.clone(), index)).copied();
    if let Some(id) = raced {
        bump(&mut guard.lru, id);
        if let Some(entry) = guard.window_by_id.get(&id) {
            return Ok(entry.bytes.clone());
        }
    }
    let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
    guard.total = guard.total.saturating_add(fetched.len() as u64);
    guard.windows.insert((key.clone(), index), id);
    guard.window_by_id.insert(
        id,
        Window {
            key: key.clone(),
            index,
            bytes: fetched.clone(),
            accessed: true,
        },
    );
    guard.lru.push(id);
    drop(guard);
    evict();
    Ok(fetched)
}

/// Forget a closed handle's resolved size. Its windows stay: they are keyed by
/// file, so reopening the same archive still hits them.
pub fn forget_handle(handle: u64) {
    cache().lock().unwrap().sizes.remove(&handle);
}

/// Drop everything: every memfd region, every window, every remembered size.
///
/// The user can ask for this as a gallery closes, which is what the bitmap cache
/// does. It is also the only way to get this memory back without spending swap:
/// the regions are tmpfs, so the kernel can only reclaim them by writing them to
/// swap -- and already-compressed image data does not compress again there, so
/// that frees almost nothing while costing CPU.
pub fn clear() {
    let mut guard = cache().lock().unwrap();
    guard.by_id.clear();
    guard.by_key.clear();
    guard.windows.clear();
    guard.window_by_id.clear();
    guard.sizes.clear();
    guard.lru.clear();
    guard.total = 0;
}

/// Whether `/proc/self/fd/N` may be reopened on this device.
///
/// The `open` hook checks the memfd's label -- `appdomain_tmpfs` -- against the
/// app domain, and no AOSP policy grants `open` on that type to an app: the only
/// domain that has it is `shell`, for `fexecve`. So the answer is a property of
/// the device, not of the file, and it is probed once. Retrying per file pays a
/// failed syscall and a kernel audit record for every image opened, which also
/// floods the audit log.
static REOPEN: AtomicU8 = AtomicU8::new(REOPEN_UNKNOWN);
const REOPEN_UNKNOWN: u8 = 0;
const REOPEN_YES: u8 = 1;
const REOPEN_NO: u8 = 2;

/// Hand a consumer its own descriptor for a cached region.
///
/// Deliberately NOT `dup(2)` when it can be helped: `dup` shares the *file
/// position* with the cached descriptor, and the consumers are not
/// position-independent. Android only made
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
/// `F_SEAL_FUTURE_WRITE` (Linux 4.4 returns EINVAL for it). It is a privilege
/// though, and most devices do not grant it -- see [REOPEN].
fn open_for_consumer(mem: &Memfd) -> Result<i32> {
    if REOPEN.load(Ordering::Relaxed) != REOPEN_NO {
        match reopen(mem) {
            Ok(fd) => {
                REOPEN.store(REOPEN_YES, Ordering::Relaxed);
                return Ok(fd);
            }
            Err(e) => {
                if REOPEN.swap(REOPEN_NO, Ordering::Relaxed) != REOPEN_NO {
                    log::warn!(
                        target: "memfd",
                        "cannot reopen /proc/self/fd on this device, using dup+rewind from now on: {e:#}"
                    );
                }
            }
        }
    }
    dup_rewind(mem)
}

/// `dup` + rewind, the fallback when `/proc/self/fd` may not be reopened.
///
/// Not race-free -- a concurrent consumer can move the shared position between
/// the rewind and its read -- but still better than refusing to serve the file,
/// because the caller would drop to the pipe, which cannot be mmap'd, and the
/// animated-WebP decoder needs a real mmap-able descriptor. It also matches what
/// the platform's own static decoder does before reading.
fn dup_rewind(mem: &Memfd) -> Result<i32> {
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

/// Fetch a remote file into an in-memory region and return a consumer-owned fd
/// plus a cache key. On any failure returns `Err`, and the caller falls back to
/// the streaming pipe path. The fd must be freed by the caller (it owns it); the
/// backing region lives on in the cache until `release_memfd` is called.
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
            touch(&mut guard, id);
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
                accessed: false,
            },
        );
        guard.by_key.insert(key.clone(), id);
        guard.lru.push(id);
        guard.total = guard.total.saturating_add(size);
        id
    };
    // Whether we inserted or found a resident region, this consumer holds a
    // reference: bump the pin so the eviction hand cannot reclaim it under us.
    if let Some(entry) = guard.by_id.get_mut(&id) {
        entry.pins += 1;
    }
    touch(&mut guard, id);
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
/// 0 does the region become eligible for eviction.
pub fn release_memfd(id: u64) {
    let mut guard = cache().lock().unwrap();
    if let Some(entry) = guard.by_id.get_mut(&id) {
        entry.pins = entry.pins.saturating_sub(1);
    }
    // Refresh recency here, not only on open. A consumer releases a region when
    // it is done with it -- for an animated WebP that is the moment the user
    // scrolls away from the page -- so this is what makes this tier agree with
    // the page cache above it about which page was read most recently. Without
    // it the page the user just left looks like the oldest one in the cache.
    touch(&mut guard, id);
}
