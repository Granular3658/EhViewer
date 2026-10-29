#![cfg(feature = "android")]

//! Writing a DwarFS image, the way the archive writer writes a zip.
//!
//! Same shape as `archiveWriter*`: open once against the output descriptor, feed
//! it bounded chunks of entries, close to write the metadata and index. A
//! gallery is thousands of files, and every source descriptor has to stay open
//! until its entry is written -- on a share that is a pipe with a worker thread
//! behind it -- so the caller opens a chunk, appends it, closes it, and repeats.
//!
//! The block size is left at the library default (16 MiB). It is the single
//! biggest lever on how small the image ends up -- 16 MiB blocks measured 15%
//! smaller than 256 KiB ones on a real library -- and it is a property of the
//! file, so changing it later means rebuilding every image.

use std::collections::HashMap;
use std::io::{self, Read, Write};
use std::os::fd::RawFd;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Mutex, OnceLock};

use anyhow::{anyhow, ensure};
use dwarfs_enc::chunker::{BasicChunker, CdcChunker, Chunker};
use dwarfs_enc::metadata::{Builder as MetadataBuilder, DirId, InodeMetadata};
use dwarfs_enc::section::{self, CompressParam};
use jni::JNIEnv;
use jni::objects::{JClass, JIntArray, JObjectArray, JString};
use jni::sys::{jint, jlong};
use jni_fn::jni_fn;

use super::jvm::jni_throwing;

/// The output, written straight to the descriptor the caller opened.
struct FdWriter {
    fd: RawFd,
}

impl Write for FdWriter {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        loop {
            let n = unsafe { libc::write(self.fd, buf.as_ptr().cast(), buf.len()) };
            if n >= 0 {
                return Ok(n as usize);
            }
            let err = io::Error::last_os_error();
            if err.kind() != io::ErrorKind::Interrupted {
                return Err(err);
            }
        }
    }

    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

/// A source, read sequentially. Positional reads are not needed here: each
/// source is consumed once, from start to end.
struct FdReader {
    fd: RawFd,
}

impl Read for FdReader {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        loop {
            let n = unsafe { libc::read(self.fd, buf.as_mut_ptr().cast(), buf.len()) };
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

/// The writer chain the library composes: content-defined chunking feeding
/// blocks, blocks feeding the output. The generic is the output, not the
/// chunker -- `CdcChunker<W>` holds a `BasicChunker<W>` and gives back a
/// `section::Writer<W>`.
type ImageChunker = CdcChunker<FdWriter>;

struct Writer {
    builder: MetadataBuilder,
    /// Taken by close, which is what finishes the image.
    chunker: Option<ImageChunker>,
    compress: CompressParam,
    /// The directories made so far, by path.
    ///
    /// It lives here rather than in one append because a gallery is fed in
    /// chunks, and `put_dir` refuses a name that is already there: a per-call map
    /// would make the second chunk try to create the directory the first one
    /// created, and the whole append would fail on it.
    dirs: HashMap<String, DirId>,
}

static NEXT_HANDLE: AtomicU64 = AtomicU64::new(1);

/// The directory `path` names, made on the way if it does not exist yet.
///
/// `put_dir` refuses a name that is already there, so the ones made are kept:
/// a gallery's pages all land in the same directory, and asking for it once per
/// page would fail on the second.
fn ensure_dir(builder: &mut MetadataBuilder, dirs: &mut HashMap<String, DirId>, path: &str) -> anyhow::Result<DirId> {
    if path.is_empty() {
        return Ok(builder.root());
    }
    if let Some(id) = dirs.get(path) {
        return Ok(*id);
    }
    let (parent_path, name) = path.rsplit_once('/').unwrap_or(("", path));
    let parent = ensure_dir(builder, dirs, parent_path)?;
    let id = builder.put_dir(parent, name, &InodeMetadata::new(0o755))?;
    dirs.insert(path.to_owned(), id);
    Ok(id)
}

fn writers() -> &'static Mutex<std::collections::HashMap<u64, Writer>> {
    static WRITERS: OnceLock<Mutex<std::collections::HashMap<u64, Writer>>> = OnceLock::new();
    WRITERS.get_or_init(|| Mutex::new(std::collections::HashMap::new()))
}

#[jni_fn("com.hippo.ehviewer.jni.DwarfsKt")]
pub fn dwarfsWriterOpen(mut env: JNIEnv, _: JClass, fd: jint, level: jint) -> jlong {
    jni_throwing(&mut env, |_| {
        let fd = fd as RawFd;
        ensure!(fd >= 0, "invalid output descriptor");
        let root_meta = InodeMetadata::new(0o755);
        let builder = MetadataBuilder::new(&root_meta);
        // The level comes from the settings, which offer zstd's own range; it is
        // the same number for the data chunks and for the metadata sections.
        let compress = CompressParam::Zstd(level);
        let writer = section::Writer::new(FdWriter { fd })?;
        let chunker = CdcChunker::new(BasicChunker::new(writer, builder.block_size(), compress));
        let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
        writers().lock().map_err(|_| anyhow!("the writer table is poisoned"))?.insert(handle, Writer { builder, chunker: Some(chunker), compress, dirs: HashMap::new() });
        Ok(handle as jlong)
    })
}

/// Appends one chunk of entries. Returns how many were written, or -1 when no
/// writer is open.
#[jni_fn("com.hippo.ehviewer.jni.DwarfsKt")]
pub fn dwarfsWriterAppend(mut env: JNIEnv, _: JClass, handle: jlong, fd_batch: JIntArray, names: JObjectArray, size: jint) -> jint {
    jni_throwing(&mut env, |env| {
        if size <= 0 {
            return Ok(0 as jint);
        }
        let mut table = writers().lock().map_err(|_| anyhow!("the writer table is poisoned"))?;
        let writer = table.get_mut(&(handle as u64)).ok_or_else(|| anyhow!("no DwarFS writer for handle {handle}"))?;

        let mut fds = vec![0i32; size as usize];
        env.get_int_array_region(&fd_batch, 0, &mut fds)?;

        // A name may carry directories: an archive keeps its own layout, and a
        // conversion keeps that too. The parents are made as they come up, once
        // each -- once per writer, not once per chunk, because the chunk after
        // this one is in the same directory.
        let mut written = 0i32;
        for (i, fd) in fds.iter().enumerate() {
            let name = env.get_object_array_element(&names, i as i32)?;
            let name: String = env.get_string(&JString::from(name))?.into();
            let (parent_path, file_name) = match name.rsplit_once('/') {
                Some((parent, file)) => (parent, file),
                None => ("", name.as_str()),
            };
            let parent = ensure_dir(&mut writer.builder, &mut writer.dirs, parent_path)?;
            let meta = InodeMetadata::new(0o644);
            let chunker = writer.chunker.as_mut().ok_or_else(|| anyhow!("the writer is already closed"))?;
            let mut reader = FdReader { fd: *fd as RawFd };
            let chunks = chunker.put_reader(&mut reader)?;
            writer.builder.put_file(parent, file_name, &meta, chunks)?;
            written += 1;
        }
        Ok(written as jint)
    })
}

/// Finishes the image: writes the metadata sections and the index. Returns 0 on
/// success, -1 when no writer was open.
#[jni_fn("com.hippo.ehviewer.jni.DwarfsKt")]
pub fn dwarfsWriterClose(mut env: JNIEnv, _: JClass, handle: jlong) -> jint {
    jni_throwing(&mut env, |_| {
        let writer = writers().lock().map_err(|_| anyhow!("the writer table is poisoned"))?.remove(&(handle as u64)).ok_or_else(|| anyhow!("no DwarFS writer for handle {handle}"))?;
        let Writer { builder, chunker, compress, .. } = writer;
        let mut section_writer = chunker.ok_or_else(|| anyhow!("the writer is already closed"))?.finish()?;
        section_writer.write_metadata_sections(&builder.finish()?, compress)?;
        section_writer.finish()?;
        Ok(0 as jint)
    })
}
