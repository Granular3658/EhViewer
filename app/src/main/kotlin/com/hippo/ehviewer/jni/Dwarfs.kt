package com.hippo.ehviewer.jni

import java.nio.ByteBuffer

/**
 * Reading a DwarFS image.
 *
 * An image is a deduplicating, compressed, read-only filesystem, and it is read
 * through a descriptor the caller already holds -- nothing is mounted, so this
 * needs neither root nor `/dev/fuse`. A descriptor that is a pipe cannot serve
 * the positional reads an image needs, so those fail rather than read wrongly.
 *
 * [cacheMb] bounds the decompressed block cache. It has to be at least one
 * block, and a block is a build-time choice of the image: an image written with
 * blocks larger than this refuses to open, and says so.
 */
external fun dwarfsOpen(fd: Int, cacheMb: Int): Long
external fun dwarfsPageCount(handle: Long): Int
external fun dwarfsPageName(handle: Long, page: Int): String
external fun dwarfsReadPage(handle: Long, page: Int): ByteBuffer

/** Writes a page straight into a descriptor, the way an archive page is saved. */
external fun dwarfsReadPageToFd(handle: Long, page: Int, fd: Int): Boolean
external fun dwarfsReleaseByteBuffer(buffer: ByteBuffer)
external fun dwarfsClose(handle: Long)

/**
 * Writing an image, in the same shape as the archive writer: open against the
 * output descriptor, append bounded chunks of entries, close to write the
 * metadata and the index.
 *
 * The sources are read to their ends, so unlike the archive writer these take
 * no lengths: a descriptor for a remote file is a pipe, and a pipe cannot be
 * stat'ed anyway.
 */
external fun dwarfsWriterOpen(fd: Int): Long
external fun dwarfsWriterAppend(handle: Long, fdBatch: IntArray, names: Array<String>, size: Int): Int
external fun dwarfsWriterClose(handle: Long): Int
