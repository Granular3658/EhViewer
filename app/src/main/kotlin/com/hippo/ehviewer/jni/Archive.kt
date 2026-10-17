package com.hippo.ehviewer.jni

import java.nio.ByteBuffer

external fun releaseByteBuffer(buffer: ByteBuffer)
external fun openArchive(fd: Int, size: Long, sortEntries: Boolean): Int
external fun openArchiveSmb(handle: Long, size: Long, sortEntries: Boolean): Int
external fun extractToByteBuffer(index: Int): ByteBuffer?
external fun extractToFd(index: Int, fd: Int): Boolean
external fun getExtension(index: Int): String
external fun lastArchiveError(): String?
external fun needPassword(): Boolean
external fun providePassword(str: String): Boolean
external fun closeArchive()
external fun archiveWriterOpen(arcFd: Int): Int
external fun archiveWriterAppend(fdBatch: IntArray, sizes: LongArray, names: Array<String>, size: Int): Int
external fun archiveWriterClose(): Int
