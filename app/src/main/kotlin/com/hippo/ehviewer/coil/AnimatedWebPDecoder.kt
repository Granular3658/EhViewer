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
        // A real file fd (e.g. a content:// that resolves to a local file) can
        // be mmap'd zero-copy, which keeps local decoding OOM-safe. A pipe
        // (e.g. SMB served through the ContentProvider proxy) cannot be mmap'd,
        // so fall back to reading Coil's already-open source exactly once into
        // a direct buffer. Re-opening the fd a second time races the first
        // reader and truncates the WebP or throws a get_direct_buffer_address
        // NPE.
        is ContentMetadata -> runCatching {
            metadata.assetFileDescriptor.createInputStream().mapReadOnly()
        }.getOrNull() ?: source().readByteArray().toDirectBuffer()
        is ByteBufferMetadata -> metadata.byteBuffer
        else -> null
    }
}

private fun ByteArray.toDirectBuffer(): ByteBuffer = ByteBuffer.allocateDirect(size).apply {
    put(this@toDirectBuffer)
    flip()
}

private fun FileInputStream.mapReadOnly(): ByteBuffer = channel.use {
    val size = it.size()
    // A pipe (e.g. an SMB ContentProvider proxy) reports size 0 and cannot be
    // mmap'd; mapping 0 bytes would succeed but yield an empty buffer. Reject
    // it so the caller falls back to a one-shot direct read.
    if (size == 0L) throw IOException("cannot mmap zero-length source (pipe?)")
    it.map(FileChannel.MapMode.READ_ONLY, 0, size)
}
