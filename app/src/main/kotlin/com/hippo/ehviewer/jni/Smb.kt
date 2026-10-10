package com.hippo.ehviewer.jni

/**
 * Read-only SMB2/3 bridge. Credentials are supplied only for the duration of
 * the call; they are never encoded into the remote path.
 */
external fun smbTest(
    host: String,
    port: Int,
    share: String,
    sub: String,
    user: String,
    pass: String,
    domain: String,
): Long

/** Each entry is "1:name" for a directory and "0:name" for a regular file. */
external fun smbList(
    host: String,
    port: Int,
    share: String,
    sub: String,
    user: String,
    pass: String,
    domain: String,
): Array<String>

/** Returns [isDirectory, size, lastModifiedMillis]. */
external fun smbStat(
    host: String,
    port: Int,
    share: String,
    sub: String,
    user: String,
    pass: String,
    domain: String,
): LongArray

/** Returns [opaqueHandle, size]. */
external fun smbOpen(
    host: String,
    port: Int,
    share: String,
    sub: String,
    user: String,
    pass: String,
    domain: String,
): LongArray

/** Reads [length] bytes at [fileOffset] into the direct buffer at [bufferOffset]. */
external fun smbRead(
    handle: Long,
    buffer: java.nio.ByteBuffer,
    fileOffset: Long,
    bufferOffset: Int,
    length: Int,
): Int

external fun smbClose(handle: Long)

/** Creates (truncating) the remote file and returns an opaque write handle. */
external fun smbCreate(
    host: String,
    port: Int,
    share: String,
    sub: String,
    user: String,
    pass: String,
    domain: String,
): Long

/** Writes [length] bytes from the direct buffer to the writer opened by [smbCreate]. */
external fun smbWrite(
    handle: Long,
    buffer: java.nio.ByteBuffer,
    length: Int,
): Int

/** Flushes and closes the writer, returning the total bytes written. */
external fun smbWriteClose(handle: Long): Long

external fun smbMkdir(
    host: String,
    port: Int,
    share: String,
    sub: String,
    user: String,
    pass: String,
    domain: String,
)

external fun smbDelete(
    host: String,
    port: Int,
    share: String,
    sub: String,
    user: String,
    pass: String,
    domain: String,
)

external fun smbRename(
    host: String,
    port: Int,
    share: String,
    sub: String,
    user: String,
    pass: String,
    domain: String,
    toSub: String,
)

external fun smbInvalidate()

/**
 * Returns `[fd, size, key]` for an in-memory (memfd) cache of the file, or
 * `[-1, 0, 0]` if unsupported. The descriptor must be released with
 * [smbReleaseMemfd] once consumed.
 */
external fun smbOpenMemfd(
    host: String,
    port: Int,
    share: String,
    sub: String,
    user: String,
    pass: String,
    domain: String,
): LongArray

/** Release a reference obtained from [smbOpenMemfd]. */
external fun smbReleaseMemfd(key: Long)

/**
 * Maps the SMB provider's descriptor into a direct [java.nio.ByteBuffer] of
 * exactly [size], with no copy: the buffer's backing is the in-memory region
 * itself, never the JVM heap.
 *
 * The provider serves a memfd, whose `fstat` is accurate — but we still map
 * natively rather than via `FileChannel.map`, because the only `FileChannel`
 * available over an [android.content.res.AssetFileDescriptor] comes from
 * `createInputStream()`, whose stream owns and closes the provider's
 * `ParcelFileDescriptor`. Mapping natively also gives an explicit unmap on
 * dispose. Returns null if the mapping fails.
 */
external fun smbMmapReadOnly(fd: Int, size: Long): java.nio.ByteBuffer?

/** Releases a mapping created by [smbMmapReadOnly]. */
external fun smbMunmap(buffer: java.nio.ByteBuffer)

/** Configure the SMB in-memory (memfd) cache cap, in MiB. */
external fun smbSetCacheLimitMb(mb: Int)
