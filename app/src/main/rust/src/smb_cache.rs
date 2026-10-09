//! In-memory (ashmem) cache for SMB file contents, exposed as real mmap-able
//! file descriptors.
//!
//! A remote SMB file is fetched once into an `ndk::shared_memory` region (RAM,
//! no disk) and handed to Android as a file descriptor. Because that fd is a
//! genuine file descriptor, the decode layer can `mmap` it exactly like a local
//! file: no one-shot pipe (which cannot be mmap'd), and no per-image direct
//! buffer copied into the heap (which is what used to OOM while browsing).
//!
//! Regions are reference-counted. Every consumer that maps one bumps `pins`;
//! only regions with `pins == 0` may be evicted by the LRU. That guarantees a
//! region libwebp is still decoding lazily from is never reclaimed underneath
//! it — the consumer releases it (which drops `pins`) only after it has called
//! `dispose()` and closed the descriptor.

#![cfg(feature = "android-26")]

use crate::smb::{self, Target};
use anyhow::{Context, Result, anyhow, ensure};
use libc::{MAP_SHARED, PROT_READ, PROT_WRITE, mmap, munmap};
use log::info;
use ndk::shared_memory::SharedMemory;
use std::collections::HashMap;
use std::os::unix::io::AsRawFd;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Mutex, OnceLock};

/// (host, port, share, sub, user, domain, pass, size) — `size` is part of the
/// key so a changed file is re-fetched rather than served stale.
type Key = (String, u16, String, String, String, String, String, u64);

struct Entry {
    key: Key,
    mem: SharedMemory,
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

/// Cap on the unpinned (evictable) tail. The reader's page cache bounds how many
/// regions are simultaneously pinned, so this only needs to bound the leftover
/// history of pages you have already scrolled past.
const MAX_BYTES: u64 = 256 * 1024 * 1024;

fn touch(lru: &mut Vec<u64>, id: u64) {
    if let Some(pos) = lru.iter().position(|&x| x == id) {
        lru.remove(pos);
    }
    lru.push(id);
}

/// Evict the oldest entries whose `pins == 0` until `total` is within `MAX_BYTES`.
/// Pinned entries are always skipped, so this never reclaims a live mapping.
fn evict() {
    let mut guard = cache().lock().unwrap();
    while guard.total > MAX_BYTES {
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
            drop(entry); // closes the ashmem fd
        }
    }
}

fn dup_fd(mem: &SharedMemory) -> Result<i32> {
    // SAFETY: ashmem fds are regular file descriptors and can be dup'd.
    let fd = unsafe { libc::dup(mem.as_raw_fd()) };
    if fd < 0 {
        return Err(anyhow!("dup ashmem fd failed"));
    }
    Ok(fd)
}

/// Fetch a remote file into an in-memory region and return a dup'd fd plus a
/// cache key. On any failure returns `Err`, and the caller falls back to the
/// streaming pipe path. The fd must be freed by the caller (it owns the dup);
/// the backing region lives on in the cache until `release_ashmem` is called.
pub fn open_ashmem(target: &Target) -> Result<(i32, u64, u64)> {
    let stat = smb::stat(target).with_context(|| format!("smb stat {}", target.sub))?;
    ensure!(
        !stat.is_directory,
        "refusing to cache a directory: {}",
        target.sub
    );
    let size = stat.size;
    info!(target: "ashmem", "open_ashmem: stat size={size} path={}", target.sub);
    ensure!(size > 0, "empty file cannot be cached: {}", target.sub);
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
            let fd = dup_fd(&entry.mem)?;
            return Ok((fd, size, id));
        }
    }

    // Slow path: pull the whole file into a fresh ashmem region.
    let (handle, _) = smb::open(target).with_context(|| format!("smb open {}", target.sub))?;
    let fetched = (|| -> Result<SharedMemory> {
        let mem = SharedMemory::create(None, size as usize)
            .with_context(|| format!("ashmem create size={size} path={}", target.sub))?;
        let fd = mem.as_raw_fd();
        // SAFETY: a fresh ashmem region of `size` bytes, mapped read/write so
        // we can copy the file into it, then downgraded to read-only.
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
            return Err(anyhow!("mmap ashmem region failed")).context("ashmem mmap");
        }
        let slice = unsafe { std::slice::from_raw_parts_mut(ptr as *mut u8, size as usize) };
        let mut offset: u64 = 0;
        let chunk = 1024 * 1024u64;
        while offset < size {
            let len = chunk.min(size - offset);
            // A single SMB read can fail transiently (timeout / network blip),
            // especially for large files read in many chunks. Retry a few times
            // before giving up, otherwise we fall back to the non-mmap pipe and
            // the animated decoder cannot mmap the source.
            let mut bytes = Vec::new();
            let mut last_err = None;
            for attempt in 0..3 {
                match smb::read(handle, offset, len) {
                    Ok(b) => {
                        bytes = b;
                        break;
                    }
                    Err(e) => {
                        log::warn!(
                            target: "ashmem",
                            "smb read attempt {}/3 failed offset={offset} len={len} path={}: {e:#}",
                            attempt + 1,
                            target.sub
                        );
                        last_err = Some(e);
                    }
                }
            }
            let bytes = match last_err {
                Some(e) => {
                    return Err(e).with_context(|| {
                        format!("smb read offset={offset} len={len} path={}", target.sub)
                    });
                }
                None => bytes,
            };
            if bytes.is_empty() {
                break;
            }
            slice[offset as usize..offset as usize + bytes.len()].copy_from_slice(&bytes);
            offset += bytes.len() as u64;
        }
        unsafe { munmap(ptr, size as libc::size_t) };
        mem.set_prot(PROT_READ).context("ashmem set_prot")?;
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
    let mem_ref: &SharedMemory = &entry.mem;
    let fd = dup_fd(mem_ref).context("ashmem dup_fd")?;
    info!(target: "ashmem", "open_ashmem OK: fd={fd} size={size} key={id} path={}", target.sub);
    Ok((fd, size, id))
}

/// Drop a consumer's reference to a cached region. Only once `pins` returns to
/// 0 does the region become eligible for LRU eviction.
pub fn release_ashmem(id: u64) {
    let mut guard = cache().lock().unwrap();
    if let Some(entry) = guard.by_id.get_mut(&id) {
        entry.pins = entry.pins.saturating_sub(1);
    }
}
