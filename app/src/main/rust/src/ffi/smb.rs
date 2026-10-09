#![cfg(feature = "android")]

use super::jvm::jni_throwing;
use crate::smb::{self, DEFAULT_PORT, Target};
use anyhow::{Result, ensure};
use jni::JNIEnv;
use jni::objects::{JByteBuffer, JClass, JObject, JString};
use jni::sys::{jint, jlong, jlongArray, jobject, jobjectArray};
use jni_fn::jni_fn;

#[allow(clippy::too_many_arguments)]
fn read_target(
    env: &mut JNIEnv,
    host: &JString,
    port: jint,
    share: &JString,
    sub: &JString,
    user: &JString,
    pass: &JString,
    domain: &JString,
) -> Result<Target> {
    let port = if port > 0 { port as u16 } else { DEFAULT_PORT };
    Ok(Target {
        host: env.get_string(host)?.into(),
        port,
        share: env.get_string(share)?.into(),
        sub: env.get_string(sub)?.into(),
        user: env.get_string(user)?.into(),
        pass: env.get_string(pass)?.into(),
        domain: env.get_string(domain)?.into(),
    })
}

/// Entries are marshalled as "1:name" for directories and "0:name" for files:
/// a single array keeps the JNI surface small, and the caller splits on the
/// first colon (names may contain colons, the flag cannot).
fn marshal_entries(env: &mut JNIEnv, entries: Vec<smb::Entry>) -> Result<jobjectArray> {
    let array = env.new_object_array(entries.len() as i32, "java/lang/String", JObject::null())?;
    for (i, entry) in entries.iter().enumerate() {
        let flag = if entry.is_directory { '1' } else { '0' };
        let value = env.new_string(format!("{flag}:{}", entry.name))?;
        env.set_object_array_element(&array, i as i32, value)?;
    }
    Ok(array.into_raw())
}

#[jni_fn("com.hippo.ehviewer.jni.SmbKt")]
pub fn smbTest(
    mut env: JNIEnv,
    _: JClass,
    host: JString,
    port: jint,
    share: JString,
    sub: JString,
    user: JString,
    pass: JString,
    domain: JString,
) -> jlong {
    jni_throwing(&mut env, |env| {
        let target = read_target(env, &host, port, &share, &sub, &user, &pass, &domain)?;
        Ok(smb::list_dir(&target)?.len() as jlong)
    })
}

#[jni_fn("com.hippo.ehviewer.jni.SmbKt")]
pub fn smbList(
    mut env: JNIEnv,
    _: JClass,
    host: JString,
    port: jint,
    share: JString,
    sub: JString,
    user: JString,
    pass: JString,
    domain: JString,
) -> jobjectArray {
    jni_throwing(&mut env, |env| {
        let target = read_target(env, &host, port, &share, &sub, &user, &pass, &domain)?;
        let entries = smb::list_dir(&target)?;
        marshal_entries(env, entries)
    })
}

/// Returns [isDirectory, size, lastModifiedMillis].
#[jni_fn("com.hippo.ehviewer.jni.SmbKt")]
pub fn smbStat(
    mut env: JNIEnv,
    _: JClass,
    host: JString,
    port: jint,
    share: JString,
    sub: JString,
    user: JString,
    pass: JString,
    domain: JString,
) -> jlongArray {
    jni_throwing(&mut env, |env| {
        let target = read_target(env, &host, port, &share, &sub, &user, &pass, &domain)?;
        let stat = smb::stat(&target)?;
        let array = env.new_long_array(3)?;
        env.set_long_array_region(
            &array,
            0,
            &[
                stat.is_directory as jlong,
                stat.size as jlong,
                stat.modified_millis,
            ],
        )?;
        Ok(array.into_raw())
    })
}

/// Returns [handle, size]: the size is known at open time, so the caller does
/// not need a second round trip to learn it.
#[jni_fn("com.hippo.ehviewer.jni.SmbKt")]
pub fn smbOpen(
    mut env: JNIEnv,
    _: JClass,
    host: JString,
    port: jint,
    share: JString,
    sub: JString,
    user: JString,
    pass: JString,
    domain: JString,
) -> jlongArray {
    jni_throwing(&mut env, |env| {
        let target = read_target(env, &host, port, &share, &sub, &user, &pass, &domain)?;
        let (handle, size) = smb::open(&target)?;
        let array = env.new_long_array(2)?;
        env.set_long_array_region(&array, 0, &[handle as jlong, size as jlong])?;
        Ok(array.into_raw())
    })
}

/// Reads into `buffer` at `buffer_offset` and returns the number of bytes
/// copied, 0 at end of file.
#[jni_fn("com.hippo.ehviewer.jni.SmbKt")]
pub fn smbRead(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    buffer: JByteBuffer,
    file_offset: jlong,
    buffer_offset: jint,
    len: jint,
) -> jint {
    jni_throwing(&mut env, |env| {
        ensure!(
            file_offset >= 0 && buffer_offset >= 0 && len >= 0,
            "Negative SMB read argument"
        );
        let data = smb::read(handle as u64, file_offset as u64, len as u64)?;
        let capacity = env.get_direct_buffer_capacity(&buffer)?;
        let buffer_offset = buffer_offset as usize;
        ensure!(
            buffer_offset + data.len() <= capacity,
            "Buffer too small: {} + {} > {}",
            buffer_offset,
            data.len(),
            capacity
        );
        let ptr = env.get_direct_buffer_address(&buffer)?;
        unsafe { std::ptr::copy_nonoverlapping(data.as_ptr(), ptr.add(buffer_offset), data.len()) }
        Ok(data.len() as jint)
    })
}

#[jni_fn("com.hippo.ehviewer.jni.SmbKt")]
pub fn smbClose(mut env: JNIEnv, _: JClass, handle: jlong) {
    jni_throwing(&mut env, |_| {
        smb::close(handle as u64)?;
        Ok(())
    })
}

#[jni_fn("com.hippo.ehviewer.jni.SmbKt")]
pub fn smbInvalidate(mut env: JNIEnv, _: JClass) {
    jni_throwing(&mut env, |_| {
        smb::invalidate();
        Ok(())
    })
}

/// Creates (truncating) the remote file and returns an opaque write handle.
#[jni_fn("com.hippo.ehviewer.jni.SmbKt")]
pub fn smbCreate(
    mut env: JNIEnv,
    _: JClass,
    host: JString,
    port: jint,
    share: JString,
    sub: JString,
    user: JString,
    pass: JString,
    domain: JString,
) -> jlong {
    jni_throwing(&mut env, |env| {
        let target = read_target(env, &host, port, &share, &sub, &user, &pass, &domain)?;
        Ok(smb::create(&target)? as jlong)
    })
}

/// Writes `len` bytes from the direct buffer into the writer.
#[jni_fn("com.hippo.ehviewer.jni.SmbKt")]
pub fn smbWrite(mut env: JNIEnv, _: JClass, handle: jlong, buffer: JByteBuffer, len: jint) -> jint {
    jni_throwing(&mut env, |env| {
        ensure!(len >= 0, "Negative SMB write length");
        let capacity = env.get_direct_buffer_capacity(&buffer)?;
        let len = len as usize;
        ensure!(len <= capacity, "Buffer too small: {len} > {capacity}");
        let ptr = env.get_direct_buffer_address(&buffer)?;
        let data = unsafe { std::slice::from_raw_parts(ptr, len).to_vec() };
        smb::write(handle as u64, data)?;
        Ok(len as jint)
    })
}

/// Flushes and closes the writer, returning the total bytes written.
#[jni_fn("com.hippo.ehviewer.jni.SmbKt")]
pub fn smbWriteClose(mut env: JNIEnv, _: JClass, handle: jlong) -> jlong {
    jni_throwing(&mut env, |_| Ok(smb::write_close(handle as u64)? as jlong))
}

/// Creates a directory on the share.
#[jni_fn("com.hippo.ehviewer.jni.SmbKt")]
pub fn smbMkdir(
    mut env: JNIEnv,
    _: JClass,
    host: JString,
    port: jint,
    share: JString,
    sub: JString,
    user: JString,
    pass: JString,
    domain: JString,
) {
    jni_throwing(&mut env, |env| {
        let target = read_target(env, &host, port, &share, &sub, &user, &pass, &domain)?;
        smb::mkdir(&target)?;
        Ok(())
    })
}

/// Deletes a file or (empty) directory on the share.
#[jni_fn("com.hippo.ehviewer.jni.SmbKt")]
pub fn smbDelete(
    mut env: JNIEnv,
    _: JClass,
    host: JString,
    port: jint,
    share: JString,
    sub: JString,
    user: JString,
    pass: JString,
    domain: JString,
) {
    jni_throwing(&mut env, |env| {
        let target = read_target(env, &host, port, &share, &sub, &user, &pass, &domain)?;
        smb::delete(&target)?;
        Ok(())
    })
}

/// Renames `sub` to `toSub` within the same share.
#[jni_fn("com.hippo.ehviewer.jni.SmbKt")]
pub fn smbRename(
    mut env: JNIEnv,
    _: JClass,
    host: JString,
    port: jint,
    share: JString,
    sub: JString,
    user: JString,
    pass: JString,
    domain: JString,
    toSub: JString,
) {
    jni_throwing(&mut env, |env| {
        let target = read_target(env, &host, port, &share, &sub, &user, &pass, &domain)?;
        let to_sub = env.get_string(&toSub)?.to_string_lossy().into_owned();
        smb::rename(&target, &to_sub)?;
        Ok(())
    })
}

/// Returns `[fd, size, key]` for an in-memory (ashmem) cache of the file, or
/// `[-1, 0, 0]` when the platform/build cannot provide one (the caller falls
/// back to the streaming pipe). The descriptor must be released with
/// `smbReleaseAshmem(key)` once it is no longer needed.
#[jni_fn("com.hippo.ehviewer.jni.SmbKt")]
pub fn smbOpenAshmem(
    mut env: JNIEnv,
    _: JClass,
    host: JString,
    port: jint,
    share: JString,
    sub: JString,
    user: JString,
    pass: JString,
    domain: JString,
) -> jlongArray {
    let array = match env.new_long_array(3) {
        Ok(a) => a,
        Err(_) => return std::ptr::null_mut(),
    };
    let mut out = [-1i64, 0, 0];
    #[cfg(feature = "android-26")]
    {
        if let Ok(target) = read_target(&mut env, &host, port, &share, &sub, &user, &pass, &domain)
            && let Ok((fd, size, key)) = crate::smb_cache::open_ashmem(&target)
        {
            out = [fd as i64, size as i64, key as i64];
        }
    }
    let _ = env.set_long_array_region(&array, 0, &out);
    array.into_raw()
}

/// Release a reference obtained from `smbOpenAshmem`.
#[jni_fn("com.hippo.ehviewer.jni.SmbKt")]
pub fn smbReleaseAshmem(mut env: JNIEnv, _: JClass, key: jlong) {
    let _ = &mut env;
    #[cfg(feature = "android-26")]
    {
        crate::smb_cache::release_ashmem(key as u64);
    }
}

/// Configure the SMB in-memory (ashmem) cache cap, in MiB.
#[jni_fn("com.hippo.ehviewer.jni.SmbKt")]
pub fn smbSetCacheLimitMb(mut env: JNIEnv, _: JClass, mb: jint) {
    let _ = &mut env;
    #[cfg(feature = "android-26")]
    {
        crate::smb_cache::set_cache_limit_mb(mb as u64);
    }
}

/// Map an ashmem fd into a direct `ByteBuffer` of exactly `size`, with no copy.
///
/// The ashmem region is fully sized, but its `fstat` can report 0 on some
/// Android versions (notably large files on older kernels), so Java's
/// `FileChannel.map` cannot size the mapping and refuses to "extend" the
/// read-only file. We mmap natively with the size the provider already knows,
/// wrapping the region in a direct buffer whose backing is the ashmem shared
/// memory itself — never the JVM heap. Returns null on failure.
#[jni_fn("com.hippo.ehviewer.jni.SmbKt")]
pub fn smbMmapReadOnly(mut env: JNIEnv, _: JClass, fd: jint, size: jlong) -> jobject {
    if size <= 0 {
        return std::ptr::null_mut();
    }
    let size = size as usize;
    let ptr = unsafe {
        libc::mmap(
            std::ptr::null_mut(),
            size,
            libc::PROT_READ,
            libc::MAP_SHARED,
            fd,
            0,
        )
    };
    if ptr == libc::MAP_FAILED {
        log::error!(
            target: "ashmem",
            "smbMmapReadOnly: mmap failed for fd={fd} size={size}: {}",
            std::io::Error::last_os_error()
        );
        return std::ptr::null_mut();
    }
    let buf = unsafe { env.new_direct_byte_buffer(ptr as *mut u8, size) };
    match buf {
        Ok(buf) => buf.into_raw(),
        Err(e) => {
            log::error!(target: "ashmem", "smbMmapReadOnly: new_direct_byte_buffer failed: {e:#}");
            unsafe {
                libc::munmap(ptr, size);
            }
            std::ptr::null_mut()
        }
    }
}

/// Release a mapping created by `smbMmapReadOnly`. The underlying memory is
/// ashmem shared memory, so unmapping it lets the bounded cache reclaim the
/// region once the decoder is done with it.
#[jni_fn("com.hippo.ehviewer.jni.SmbKt")]
pub fn smbMunmap(mut env: JNIEnv, _: JClass, buffer: JByteBuffer) {
    jni_throwing(&mut env, |env| {
        let addr = env.get_direct_buffer_address(&buffer)? as *mut libc::c_void;
        let size = env.get_direct_buffer_capacity(&buffer)?;
        if !addr.is_null() && size > 0 {
            unsafe {
                libc::munmap(addr, size);
            }
        }
        Ok(())
    })
}
