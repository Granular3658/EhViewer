package com.hippo.ehviewer.coil

import coil3.ImageLoader
import coil3.asImage
import coil3.decode.ByteBufferMetadata
import coil3.decode.DecodeResult
import coil3.decode.DecodeUtils
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.gif.isAnimatedWebP
import coil3.request.Options
import java.io.FileInputStream
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
            return runCatching {
                file.toFile().inputStream().mapReadOnly()
            }.getOrElse {
                file.toFile().inputStream().readDirectBuffer()
            }
        }
    }
    return when (val metadata = metadata) {
        // Reuse Coil's already-open source. ContentProvider-backed sources can
        // be pipes, so opening ContentMetadata's file descriptor a second time
        // races the first reader and can produce a truncated WebP.
        is ByteBufferMetadata -> metadata.byteBuffer.toDirectBuffer()
        else -> source().readByteArray().toDirectBuffer()
    }
}

private fun ByteArray.toDirectBuffer(): ByteBuffer = ByteBuffer.allocateDirect(size).apply {
    put(this@toDirectBuffer)
    flip()
}

private fun ByteBuffer.toDirectBuffer(): ByteBuffer = duplicate().let { source ->
    ByteBuffer.allocateDirect(source.remaining()).apply {
        put(source)
        flip()
    }
}

private fun java.io.InputStream.readDirectBuffer(): ByteBuffer = use {
    readBytes().let { bytes ->
        ByteBuffer.allocateDirect(bytes.size).apply {
            put(bytes)
            flip()
        }
    }
}

private fun FileInputStream.mapReadOnly(): ByteBuffer = channel.use { it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()) }
