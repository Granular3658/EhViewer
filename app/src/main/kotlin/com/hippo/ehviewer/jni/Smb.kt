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

external fun smbInvalidate()
