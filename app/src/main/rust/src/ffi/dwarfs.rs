#![cfg(feature = "android")]

//! Reading a DwarFS image as a page source.
//!
//! DwarFS is a deduplicating, compressed read-only filesystem, and its reader is
//! an ordinary library that asks for positional reads. Nothing has to be mounted:
//! the image is read through a descriptor the caller already holds, so this needs
//! neither root nor `/dev/fuse`.
//!
//! Two numbers decide how it behaves, and both are baked in when the image is
//! built, not here:
//!
//! * `block_size` is a decompression unit. Reading any byte of a block costs the
//!   whole block, in I/O *and* in resident memory -- 16 MiB (the default) is
//!   about 13 MB of RSS to open a single 267 KB page, 256 KiB is about 0.6 MB.
//! * the block cache holds those decompressed blocks, defaults to 512 MiB, and
//!   has to be at least one block, so the two knobs move together.

use std::collections::HashMap;
use std::ffi::c_void;
use std::io::{self, Read};
use std::os::fd::RawFd;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

use anyhow::{Result, anyhow, ensure};
use dwarfs::archive::Config;
use dwarfs::{Archive, ArchiveIndex, AsChunks, Dir};
use jni::JNIEnv;
use jni::objects::{JByteBuffer, JClass};
use jni::sys::{jint, jlong, jobject};
use jni_fn::jni_fn;
use positioned_io::{ReadAt, Size};

use super::jvm::jni_throwing;

/// Positional reads over a descriptor the caller owns and keeps open.
///
/// `pread` is what makes this work for a descriptor from anywhere -- a plain
/// file, or a document provider that hands back a real file. There is
/// deliberately no fallback to `read`: a descriptor that is a pipe cannot serve
/// positional reads at all, and reading it sequentially would hand back the
/// wrong bytes instead of failing.
struct FdSource {
    fd: RawFd,
    size: u64,
}

impl ReadAt for FdSource {
    fn read_at(&self, pos: u64, buf: &mut [u8]) -> io::Result<usize> {
        loop {
            let n = unsafe {
                libc::pread(
                    self.fd,
                    buf.as_mut_ptr().cast(),
                    buf.len(),
                    pos as libc::off_t,
                )
            };
            if n >= 0 {
                return Ok(n as usize);
            }
            let err = io::Error::last_os_error();
            if err.kind() != io::ErrorKind::Interrupted {
                return Err(err);
            }
        }
    }
}

impl Size for FdSource {
    fn size(&self) -> io::Result<Option<u64>> {
        Ok(Some(self.size))
    }
}

struct Entry {
    path: String,
    size: u64,
}

struct Image {
    index: ArchiveIndex,
    archive: Archive<FdSource>,
    entries: Vec<Entry>,
}

impl Image {
    fn read_into(&mut self, page: usize, buf: &mut [u8]) -> Result<()> {
        let entry = self
            .entries
            .get(page)
            .ok_or_else(|| anyhow!("page {page} out of range"))?;
        let path: Vec<String> = entry.path.split('/').map(str::to_owned).collect();
        let file = self
            .index
            .get_path(path.iter().map(String::as_str))
            .ok_or_else(|| anyhow!("{} left the index", entry.path))?
            .as_file()
            .ok_or_else(|| anyhow!("{} is not a file", entry.path))?;
        // Serialised on the archive, which owns the block cache: one reader at a
        // time keeps decompression bounded, and a repeated read hits the cache.
        let mut reader = file.as_reader(&mut self.archive);
        reader.read_exact(buf)?;
        Ok(())
    }
}

/// Walks the whole tree so the page order is the image's own layout rather than
/// whatever order the index stores inodes in.
fn collect(dir: Dir<'_>, prefix: &str, out: &mut Vec<Entry>) {
    for entry in dir.entries() {
        let name = entry.name();
        let path = if prefix.is_empty() {
            name.to_owned()
        } else {
            format!("{prefix}/{name}")
        };
        let inode = entry.inode();
        if let Some(inner) = inode.as_dir() {
            collect(inner, &path, out);
        } else if let Some(file) = inode.as_file() {
            out.push(Entry {
                path,
                size: file.as_chunks().total_size(),
            });
        }
    }
}

static NEXT_HANDLE: AtomicU64 = AtomicU64::new(1);

fn images() -> &'static Mutex<HashMap<u64, Arc<Mutex<Image>>>> {
    static IMAGES: OnceLock<Mutex<HashMap<u64, Arc<Mutex<Image>>>>> = OnceLock::new();
    IMAGES.get_or_init(|| Mutex::new(HashMap::new()))
}

fn lookup(handle: jlong) -> Result<Arc<Mutex<Image>>> {
    images()
        .lock()
        .map_err(|_| anyhow!("the image table is poisoned"))?
        .get(&(handle as u64))
        .cloned()
        .ok_or_else(|| anyhow!("no DwarFS image for handle {handle}"))
}

unsafe extern "C" {
    fn malloc(size: usize) -> *mut c_void;
    fn free(ptr: *mut c_void);
}

#[jni_fn("com.hippo.ehviewer.jni.DwarfsKt")]
pub fn dwarfsOpen(mut env: JNIEnv, _: JClass, fd: jint) -> jlong {
    jni_throwing(&mut env, |_| {
        let fd = fd as RawFd;
        let mut st: libc::stat = unsafe { std::mem::zeroed() };
        ensure!(unsafe { libc::fstat(fd, &mut st) } == 0, "fstat failed");
        let size = st.st_size as u64;
        ensure!(size > 0, "the image is empty");

        let mut config = Config::default();
        // The defaults are desktop ones: a 512 MiB block cache is not something a
        // phone hands over. It has to stay at or above one block, so an image
        // built with the 16 MiB default simply gets a bigger cache than this.
        config.block_cache_size_limit(64 << 20);

        let (index, archive) = Archive::new_with_config(FdSource { fd, size }, &config)?;
        let mut entries = Vec::new();
        collect(index.root(), "", &mut entries);
        ensure!(!entries.is_empty(), "the image holds no regular files");
        entries.sort_by(|a, b| a.path.cmp(&b.path));

        let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
        let image = Arc::new(Mutex::new(Image {
            index,
            archive,
            entries,
        }));
        images()
            .lock()
            .map_err(|_| anyhow!("the image table is poisoned"))?
            .insert(handle, image);
        Ok(handle as jlong)
    })
}

#[jni_fn("com.hippo.ehviewer.jni.DwarfsKt")]
pub fn dwarfsPageCount(mut env: JNIEnv, _: JClass, handle: jlong) -> jint {
    jni_throwing(&mut env, |_| {
        let image = lookup(handle)?;
        let image = image.lock().map_err(|_| anyhow!("the image is poisoned"))?;
        Ok(image.entries.len() as jint)
    })
}

/// The entry's name, which is where the page's extension comes from.
#[jni_fn("com.hippo.ehviewer.jni.DwarfsKt")]
pub fn dwarfsPageName(mut env: JNIEnv, _: JClass, handle: jlong, page: jint) -> jobject {
    jni_throwing(&mut env, |env| {
        let image = lookup(handle)?;
        let image = image.lock().map_err(|_| anyhow!("the image is poisoned"))?;
        let entry = image
            .entries
            .get(page as usize)
            .ok_or_else(|| anyhow!("page {page} out of range"))?;
        Ok(env.new_string(&entry.path)?.into_raw())
    })
}

/// Hands one page to Java as a direct buffer.
///
/// The buffer is this module's own, not the archive pool's: a page's size comes
/// from the image, while every buffer in that pool is exactly `max_file_size`,
/// and mixing the two would let one reader hand the other a buffer that is too
/// small. Java releases it with `dwarfsReleaseByteBuffer`.
#[jni_fn("com.hippo.ehviewer.jni.DwarfsKt")]
pub fn dwarfsReadPage(mut env: JNIEnv, _: JClass, handle: jlong, page: jint) -> jobject {
    jni_throwing(&mut env, |env| {
        let image = lookup(handle)?;
        let mut image = image.lock().map_err(|_| anyhow!("the image is poisoned"))?;
        let size = image
            .entries
            .get(page as usize)
            .ok_or_else(|| anyhow!("page {page} out of range"))?
            .size as usize;
        ensure!(size > 0, "page {page} is empty");

        let addr = unsafe { malloc(size) };
        ensure!(!addr.is_null(), "out of memory for a {size} byte page");
        let buf = unsafe { std::slice::from_raw_parts_mut(addr.cast::<u8>(), size) };
        match image.read_into(page as usize, buf) {
            Ok(()) => Ok(unsafe {
                env.new_direct_byte_buffer(addr.cast::<u8>(), size)?
                    .into_raw()
            }),
            Err(err) => {
                // The buffer never reached Java, so it has to go back here.
                unsafe { free(addr) };
                Err(err)
            }
        }
    })
}

#[jni_fn("com.hippo.ehviewer.jni.DwarfsKt")]
pub fn dwarfsReleaseByteBuffer(mut env: JNIEnv, _: JClass, buffer: JByteBuffer) {
    jni_throwing(&mut env, |env| {
        let addr = env.get_direct_buffer_address(&buffer)?;
        if !addr.is_null() {
            unsafe { free(addr.cast::<c_void>()) };
        }
        Ok(())
    })
}

#[jni_fn("com.hippo.ehviewer.jni.DwarfsKt")]
pub fn dwarfsClose(mut env: JNIEnv, _: JClass, handle: jlong) {
    jni_throwing(&mut env, |_| {
        if let Ok(mut table) = images().lock() {
            table.remove(&(handle as u64));
        }
        Ok(())
    })
}
