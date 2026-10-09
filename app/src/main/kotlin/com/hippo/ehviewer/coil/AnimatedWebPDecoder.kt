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
        // Local files are mmap'd zero-copy. SMB-served files arrive as an
        // in-memory ashmem file descriptor (bounded LRU cache); we copy the
        // region into a direct buffer here because ashmem fds report size 0 via
        // fstat on some Android versions, so a direct mmap is impossible even
        // though the region is fully sized. Pipes report a zero length, which we
        // reject so the caller can fall back.
        is ContentMetadata -> {
            val afd = metadata.assetFileDescriptor
            // ashmem fds report size 0 via fstat on some Android versions
            // (e.g. LOS 15 / Android 8.1). That makes FileChannel.map() refuse to
            // "extend" a read-only file even though the region is fully sized, so
            // a direct mmap is impossible. The provider advertises the real SMB
            // size via the AssetFileDescriptor length; read exactly that many
            // bytes into a direct buffer the native decoder can address.
            val size = if (afd.length > 0) afd.length else afd.createInputStream().channel.size()
            if (size <= 0L) throw IOException("cannot mmap zero-length source (pipe?)")
            val buffer = ByteBuffer.allocateDirect(size.toInt())
            afd.createInputStream().use { stream ->
                val tmp = ByteArray(64 * 1024)
                var remaining = size.toInt()
                while (remaining > 0) {
                    val n = stream.read(tmp, 0, minOf(tmp.size, remaining))
                    if (n < 0) break
                    buffer.put(tmp, 0, n)
                    remaining -= n
                }
            }
            buffer.flip()
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
