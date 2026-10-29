package com.hippo.ehviewer.jni

import java.nio.ByteBuffer

external fun releaseByteBuffer(buffer: ByteBuffer)
external fun openArchive(fd: Int, size: Long, sortEntries: Boolean): Int
external fun openArchiveSmb(handle: Long, size: Long, sortEntries: Boolean): Int

/**
 * Opens the same archive as a container: every regular file, in the archive's own
 * order. A conversion rewrites the file, so it must not decide that some of it is
 * not worth keeping, nor reorder what it keeps.
 */
external fun openArchiveAll(fd: Int, size: Long): Int
external fun openArchiveSmbAll(handle: Long, size: Long): Int
external fun extractToByteBuffer(index: Int): ByteBuffer?
external fun extractToFd(index: Int, fd: Int): Boolean
external fun getExtension(index: Int): String

/** The entry's name, as the archive holds it. */
external fun archiveEntryName(index: Int): String?

/** The entry's uncompressed length, or -1 when the archive did not say. */
external fun archiveEntrySize(index: Int): Long
external fun lastArchiveError(): String?
external fun needPassword(): Boolean
external fun providePassword(str: String): Boolean
external fun closeArchive()
external fun archiveWriterOpen(arcFd: Int): Int
external fun archiveWriterAppend(fdBatch: IntArray, sizes: LongArray, names: Array<String>, size: Int): Int
external fun archiveWriterClose(): Int
