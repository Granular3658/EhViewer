#![cfg(feature = "android")]

//! Reading a DwarFS image as a page source.
//!
//! DwarFS is a deduplicating, compressed read-only filesystem, and its reader is
//! an ordinary library that asks for positional reads. Nothing has to be mounted:
//! the image is read through a descriptor the caller already holds -- or, on a
//! share, through an SMB handle, because the descriptor for a remote file is a
//! pipe and a pipe cannot serve positional reads. Either way this needs neither
//! root nor `/dev/fuse`.
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
use std::ffi::{c_char, c_void};
use std::io::{self, Read};
use std::os::fd::RawFd;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

use anyhow::{Result, anyhow, ensure};
use dwarfs::archive::Config;
use dwarfs::{Archive, ArchiveIndex, AsChunks, Dir};
use jni::JNIEnv;
use jni::objects::{JByteBuffer, JClass};
use jni::sys::{jboolean, jint, jlong, jobject};
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
            let n = unsafe { libc::pread(self.fd, buf.as_mut_ptr().cast(), buf.len(), pos as libc::off_t) };
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

/// Positional reads through an SMB handle.
///
/// A descriptor for a remote file is a pipe, and a pipe cannot serve positional
/// reads at all, so an image on a share is read through the SMB layer instead.
/// Its windows live in the same cache, under the same cap and the same eviction,
/// as the whole-file regions a gallery of images uses.
struct SmbSource {
    handle: u64,
    size: u64,
}

impl ReadAt for SmbSource {
    #[cfg(feature = "android-26")]
    fn read_at(&self, pos: u64, buf: &mut [u8]) -> io::Result<usize> {
        if buf.is_empty() || pos >= self.size {
            return Ok(0);
        }
        let len = (buf.len() as u64).min(self.size - pos);
        let data = crate::smb_cache::read_window(self.handle, pos, len).map_err(|err| io::Error::other(format!("{err:#}")))?;
        let n = data.len().min(buf.len());
        buf[..n].copy_from_slice(&data[..n]);
        Ok(n)
    }

    /// A build below API 26 has no SMB at all -- `smb_cache` is not compiled
    /// into it -- so a share can never be reached. Failing here keeps the type
    /// the same shape in both builds rather than splitting the enum.
    #[cfg(not(feature = "android-26"))]
    fn read_at(&self, _pos: u64, _buf: &mut [u8]) -> io::Result<usize> {
        Err(io::Error::other("this build has no SMB support"))
    }
}

impl Size for SmbSource {
    fn size(&self) -> io::Result<Option<u64>> {
        Ok(Some(self.size))
    }
}

/// Where an image's bytes come from: a descriptor, or a share.
enum Source {
    Fd(FdSource),
    Smb(SmbSource),
}

impl ReadAt for Source {
    fn read_at(&self, pos: u64, buf: &mut [u8]) -> io::Result<usize> {
        match self {
            Source::Fd(source) => source.read_at(pos, buf),
            Source::Smb(source) => source.read_at(pos, buf),
        }
    }
}

impl Size for Source {
    fn size(&self) -> io::Result<Option<u64>> {
        match self {
            Source::Fd(source) => source.size(),
            Source::Smb(source) => source.size(),
        }
    }
}

struct Entry {
    path: String,
    size: u64,
}

struct Image {
    index: ArchiveIndex,
    archive: Archive<Source>,
    entries: Vec<Entry>,
}

impl Image {
    fn read_into(&mut self, page: usize, buf: &mut [u8]) -> Result<()> {
        let entry = self.entries.get(page).ok_or_else(|| anyhow!("page {page} out of range"))?;
        let path: Vec<String> = entry.path.split('/').map(str::to_owned).collect();
        let file = self.index.get_path(path.iter().map(String::as_str)).ok_or_else(|| anyhow!("{} left the index", entry.path))?.as_file().ok_or_else(|| anyhow!("{} is not a file", entry.path))?;
        // Serialised on the archive, which owns the block cache: one reader at a
        // time keeps decompression bounded, and a repeated read hits the cache.
        let mut reader = file.as_reader(&mut self.archive);
        reader.read_exact(buf)?;
        Ok(())
    }
}

/// Walks the whole tree so the page order is the image's own layout rather than
/// whatever order the index stores inodes in.
fn collect(dir: Dir<'_>, prefix: &str, out: &mut Vec<Entry>, all: bool) {
    for entry in dir.entries() {
        let name = entry.name();
        let path = if prefix.is_empty() { name.to_owned() } else { format!("{prefix}/{name}") };
        let inode = entry.inode();
        if let Some(inner) = inode.as_dir() {
            collect(inner, &path, out, all);
        } else if let Some(file) = inode.as_file()
            && (all || is_playable(name))
        {
            out.push(Entry { path, size: file.as_chunks().total_size() });
        }
    }
}

/// Compares two names the way the archive reader does: a run of digits is a
/// number, so `2.webp` comes before `10.webp`.
///
/// The C side sorts with `strnatcmp`, and an image that shows its pages in a
/// different order than a zip of the same files would is a bug that only shows up
/// on the pages themselves. Plain byte order puts 10 before 2, which is wrong for
/// anything a gallery is named after.
fn natural_cmp(a: &str, b: &str) -> std::cmp::Ordering {
    let (a, b) = (a.as_bytes(), b.as_bytes());
    let (mut i, mut j) = (0usize, 0usize);
    while i < a.len() && j < b.len() {
        if a[i].is_ascii_digit() && b[j].is_ascii_digit() {
            let (si, sj) = (i, j);
            while i < a.len() && a[i].is_ascii_digit() {
                i += 1;
            }
            while j < b.len() && b[j].is_ascii_digit() {
                j += 1;
            }
            let (na, nb) = (strip_zeros(&a[si..i]), strip_zeros(&b[sj..j]));
            // A longer number is a bigger one; equal lengths compare digit by
            // digit, which is what the bytes already are.
            let ord = na.len().cmp(&nb.len()).then_with(|| na.cmp(nb));
            if ord != std::cmp::Ordering::Equal {
                return ord;
            }
        } else {
            let ord = a[i].cmp(&b[j]);
            if ord != std::cmp::Ordering::Equal {
                return ord;
            }
            i += 1;
            j += 1;
        }
    }
    (a.len() - i).cmp(&(b.len() - j))
}

fn strip_zeros(digits: &[u8]) -> &[u8] {
    match digits.iter().position(|c| *c != b'0') {
        Some(start) => &digits[start..],
        None => &[],
    }
}

static NEXT_HANDLE: AtomicU64 = AtomicU64::new(1);

fn images() -> &'static Mutex<HashMap<u64, Arc<Mutex<Image>>>> {
    static IMAGES: OnceLock<Mutex<HashMap<u64, Arc<Mutex<Image>>>>> = OnceLock::new();
    IMAGES.get_or_init(|| Mutex::new(HashMap::new()))
}

fn lookup(handle: jlong) -> Result<Arc<Mutex<Image>>> {
    images().lock().map_err(|_| anyhow!("the image table is poisoned"))?.get(&(handle as u64)).cloned().ok_or_else(|| anyhow!("no DwarFS image for handle {handle}"))
}

unsafe extern "C" {
    fn malloc(size: usize) -> *mut c_void;
    fn free(ptr: *mut c_void);

    /// The archive reader's list of playable extensions, so an image shows the
    /// same pages a zip of the same files would. `ComicInfo.xml` and `.ehviewer`
    /// sit beside the images and are not pages; counting them adds a page that
    /// cannot decode. The list lives there rather than here so the two readers
    /// cannot drift apart.
    fn ehFilenameIsPlayable(name: *const c_char, len: usize) -> bool;
}

fn is_playable(name: &str) -> bool {
    unsafe { ehFilenameIsPlayable(name.as_ptr().cast(), name.len()) }
}

#[jni_fn("com.hippo.ehviewer.jni.DwarfsKt")]
pub fn dwarfsOpen(mut env: JNIEnv, _: JClass, fd: jint, cache_mb: jint) -> jlong {
    jni_throwing(&mut env, |_| {
        let fd = fd as RawFd;
        let mut st: libc::stat = unsafe { std::mem::zeroed() };
        ensure!(unsafe { libc::fstat(fd, &mut st) } == 0, "fstat failed");
        let size = st.st_size as u64;
        ensure!(size > 0, "the image is empty");
        open(Source::Fd(FdSource { fd, size }), cache_mb, false)
    })
}

/// Opens an image kept on a share, through a handle rather than a descriptor.
///
/// The size comes from the caller because the handle cannot be stat'ed cheaply
/// and it already had to be read to open the file.
#[jni_fn("com.hippo.ehviewer.jni.DwarfsKt")]
pub fn dwarfsOpenSmb(mut env: JNIEnv, _: JClass, handle: jlong, size: jlong, cache_mb: jint) -> jlong {
    jni_throwing(&mut env, |_| {
        let size = size as u64;
        ensure!(size > 0, "the image is empty");
        open(Source::Smb(SmbSource { handle: handle as u64, size }), cache_mb, false)
    })
}

/// Opens an image as a container rather than as a gallery: every regular file is
/// listed, in the order the image holds them.
///
/// A conversion rewrites the file, so dropping what it does not recognise or
/// reordering what it keeps would be the wrong thing to do to it.
#[jni_fn("com.hippo.ehviewer.jni.DwarfsKt")]
pub fn dwarfsOpenAll(mut env: JNIEnv, _: JClass, fd: jint, cache_mb: jint) -> jlong {
    jni_throwing(&mut env, |_| {
        let fd = fd as RawFd;
        let mut st: libc::stat = unsafe { std::mem::zeroed() };
        ensure!(unsafe { libc::fstat(fd, &mut st) } == 0, "fstat failed");
        let size = st.st_size as u64;
        ensure!(size > 0, "the image is empty");
        open(Source::Fd(FdSource { fd, size }), cache_mb, true)
    })
}

#[jni_fn("com.hippo.ehviewer.jni.DwarfsKt")]
pub fn dwarfsOpenSmbAll(mut env: JNIEnv, _: JClass, handle: jlong, size: jlong, cache_mb: jint) -> jlong {
    jni_throwing(&mut env, |_| {
        let size = size as u64;
        ensure!(size > 0, "the image is empty");
        open(Source::Smb(SmbSource { handle: handle as u64, size }), cache_mb, true)
    })
}

fn open(source: Source, cache_mb: jint, all: bool) -> Result<jlong> {
    let mut config = Config::default();
    // The default is 512 MiB, a desktop number, so the caller decides. It has
    // to stay at or above one block or the library refuses the image outright,
    // which is why a failure here is reported rather than swallowed.
    let cache_mb = cache_mb.clamp(16, 4096) as usize;
    config.block_cache_size_limit(cache_mb << 20);

    let (index, archive) = Archive::new_with_config(source, &config)?;
    let mut entries = Vec::new();
    collect(index.root(), "", &mut entries, all);
    ensure!(!entries.is_empty(), "the image holds no regular files");
    // The reader shows pages and wants them in one fixed order whatever order the
    // image was built in. A conversion is rewriting the container, so it keeps the
    // layout it was given instead of imposing one.
    if !all {
        entries.sort_by(|a, b| natural_cmp(&a.path, &b.path));
    }

    let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
    let image = Arc::new(Mutex::new(Image { index, archive, entries }));
    images().lock().map_err(|_| anyhow!("the image table is poisoned"))?.insert(handle, image);
    Ok(handle as jlong)
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
        let entry = image.entries.get(page as usize).ok_or_else(|| anyhow!("page {page} out of range"))?;
        Ok(env.new_string(&entry.path)?.into_raw())
    })
}

/// The page's uncompressed length.
///
/// A zip writer declares each entry's length before it reads it, and the page is
/// handed over through a pipe, which reports itself as a FIFO of size 0 -- so the
/// length has to come from the image rather than from the descriptor.
#[jni_fn("com.hippo.ehviewer.jni.DwarfsKt")]
pub fn dwarfsPageSize(mut env: JNIEnv, _: JClass, handle: jlong, page: jint) -> jlong {
    jni_throwing(&mut env, |_| {
        let image = lookup(handle)?;
        let image = image.lock().map_err(|_| anyhow!("the image is poisoned"))?;
        let entry = image.entries.get(page as usize).ok_or_else(|| anyhow!("page {page} out of range"))?;
        Ok(entry.size as jlong)
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
        let size = image.entries.get(page as usize).ok_or_else(|| anyhow!("page {page} out of range"))?.size as usize;
        ensure!(size > 0, "page {page} is empty");

        let addr = unsafe { malloc(size) };
        ensure!(!addr.is_null(), "out of memory for a {size} byte page");
        // Every allocation and release of a page buffer is logged with its
        // address, so that when the allocator aborts on a corrupted chunk the
        // address it names can be looked up: one allocation and one release
        // means something else wrote over the block, two releases mean a
        // release path is unguarded, and no allocation means the pointer was
        // never ours. Nothing cheaper distinguishes those three.
        log::debug!(
            target: "dwarfs-page",
            "alloc handle={handle} page={page} addr={addr:p} size={size}"
        );
        let buf = unsafe { std::slice::from_raw_parts_mut(addr.cast::<u8>(), size) };
        match image.read_into(page as usize, buf) {
            Ok(()) => Ok(unsafe { env.new_direct_byte_buffer(addr.cast::<u8>(), size)?.into_raw() }),
            Err(err) => {
                // The buffer never reached Java, so it has to go back here.
                log::debug!(
                    target: "dwarfs-page",
                    "free on error page={page} addr={addr:p} size={size}"
                );
                unsafe { free(addr) };
                Err(err)
            }
        }
    })
}

#[jni_fn("com.hippo.ehviewer.jni.DwarfsKt")]
pub fn dwarfsReadPageToFd(mut env: JNIEnv, _: JClass, handle: jlong, page: jint, fd: jint) -> jboolean {
    jni_throwing(&mut env, |_| {
        let image = lookup(handle)?;
        let mut image = image.lock().map_err(|_| anyhow!("the image is poisoned"))?;
        let size = image.entries.get(page as usize).ok_or_else(|| anyhow!("page {page} out of range"))?.size as usize;
        ensure!(size > 0, "page {page} is empty");

        let mut buf = vec![0u8; size];
        image.read_into(page as usize, &mut buf)?;

        let fd = fd as RawFd;
        let mut written = 0;
        while written < buf.len() {
            let n = unsafe { libc::write(fd, buf[written..].as_ptr().cast(), buf.len() - written) };
            if n < 0 {
                let err = io::Error::last_os_error();
                if err.kind() == io::ErrorKind::Interrupted {
                    continue;
                }
                return Err(err.into());
            }
            written += n as usize;
        }
        Ok(1 as jboolean)
    })
}

#[jni_fn("com.hippo.ehviewer.jni.DwarfsKt")]
pub fn dwarfsReleaseByteBuffer(mut env: JNIEnv, _: JClass, buffer: JByteBuffer) {
    jni_throwing(&mut env, |env| {
        let addr = env.get_direct_buffer_address(&buffer)?;
        let size = env.get_direct_buffer_capacity(&buffer)?;
        log::debug!(
            target: "dwarfs-page",
            "free addr={:p} size={size}",
            addr
        );
        if !addr.is_null() {
            unsafe { free(addr.cast::<c_void>()) }
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
