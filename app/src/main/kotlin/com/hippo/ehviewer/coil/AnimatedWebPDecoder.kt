package com.hippo.ehviewer.coil

import coil3.ImageLoader
import coil3.asImage
import coil3.decode.ByteBufferMetadata
import coil3.decode.ContentMetadata
import coil3.decode.DecodeResult
import coil3.decode.DecodeUtils
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.gif.isAnimatedWebP
import coil3.request.Options
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import okio.FileSystem

class AnimatedWebPDecoder(private val source: ByteBuffer) : Decoder {
    override suspend fun decode() = DecodeResult(AnimatedWebPDrawable(source).asImage(), false)

    object Factory : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ) = if (DecodeUtils.isAnimatedWebP(result.source.source())) {
            result.source.toByteBufferOrNull()?.let { AnimatedWebPDecoder(it) }
        } else {
            null
        }
    }
}

private fun ImageSource.toByteBufferOrNull(): ByteBuffer? {
    if (fileSystem === FileSystem.SYSTEM) {
        val file = fileOrNull()
        if (file != null) {
            return file.toFile().inputStream().mapReadOnly()
        }
    }
    return when (val metadata = metadata) {
        // Both local files and SMB-served files now arrive as a real, mmap-able
        // file descriptor (SMB uses an in-memory ashmem region), so a zero-copy
        // mmap is always correct and OOM-safe. Pipes cannot be mmap'd and report
        // a zero length, which we reject so the caller can fall back.
        is ContentMetadata -> {
            val afd = metadata.assetFileDescriptor
            // Prefer the length the provider declared: an ashmem fd's fstat can
            // report 0 for large files on some Android versions (e.g. LOS 15 /
            // Android 8.1), even though the region is fully sized. The provider
            // advertises the real SMB size via the AssetFileDescriptor length.
            val size = if (afd.length > 0) afd.length else afd.createInputStream().channel.size()
            if (size <= 0L) throw IOException("cannot mmap zero-length source (pipe?)")
            afd.createInputStream().channel.map(FileChannel.MapMode.READ_ONLY, 0, size)
        }
        is ByteBufferMetadata -> metadata.byteBuffer
        else -> null
    }
}

private fun FileInputStream.mapReadOnly(): ByteBuffer {
    val channel = this.channel
    val size = channel.size()
    if (size == 0L) throw IOException("cannot mmap zero-length source (pipe?)")
    return channel.map(FileChannel.MapMode.READ_ONLY, 0, size)
}
