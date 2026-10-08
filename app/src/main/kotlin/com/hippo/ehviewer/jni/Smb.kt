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

/** Each entry is "<flag>:<len>:<name>": flag 1 = directory, 0 = file; the name is everything after the second colon, so names may contain ':'. */
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
