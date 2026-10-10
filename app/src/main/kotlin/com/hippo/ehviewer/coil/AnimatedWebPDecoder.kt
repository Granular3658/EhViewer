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
import com.hippo.ehviewer.jni.smbMmapReadOnly
import com.hippo.ehviewer.jni.smbMunmap
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import okio.FileSystem

class AnimatedWebPDecoder(
    private val source: ByteBuffer,
    private val release: (() -> Unit)? = null,
) : Decoder {
    override suspend fun decode() = DecodeResult(AnimatedWebPDrawable(source, release).asImage(), false)

    object Factory : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ) = if (DecodeUtils.isAnimatedWebP(result.source.source())) {
            result.source.toByteBufferOrNull()?.let { (buffer, release) -> AnimatedWebPDecoder(buffer, release) }
        } else {
            null
        }
    }
}

private fun ImageSource.toByteBufferOrNull(): Pair<ByteBuffer, (() -> Unit)?>? {
    if (fileSystem === FileSystem.SYSTEM) {
        val file = fileOrNull()
        if (file != null) {
            return file.toFile().inputStream().mapReadOnly() to null
        }
    }
    return when (val metadata = metadata) {
        // Local files are mmap'd zero-copy via FileChannel. SMB-served files
        // arrive as an in-memory memfd the provider advertises a real length
        // for; map it natively (see smbMmapReadOnly) — zero copy, the buffer's
        // backing is the memfd itself, never the JVM heap. FileChannel.map() is
        // not usable here because the only FileChannel over the provider's
        // AssetFileDescriptor comes from createInputStream(), whose stream closes
        // the provider's PFD. The provider advertises -1 for the pipe fallback,
        // which we reject so the caller can fall back.
        is ContentMetadata -> {
            val afd = metadata.assetFileDescriptor
            if (afd.length > 0) {
                val fd = afd.parcelFileDescriptor?.fd ?: -1
                if (fd < 0) throw IOException("cannot mmap memfd: missing fd")
                val buffer = smbMmapReadOnly(fd, afd.length)
                    ?: throw IOException("cannot mmap memfd fd=$fd size=${afd.length}")
                buffer to { smbMunmap(buffer) }
            } else {
                // Pipe / unknown-length source: cannot be mmap'd natively and we
                // don't have a declared size, so let Coil fall back to another
                // decoder rather than crash on a zero-length mapping.
                null
            }
        }
        is ByteBufferMetadata -> metadata.byteBuffer to null
        else -> null
    }
}

private fun FileInputStream.mapReadOnly(): ByteBuffer {
    val channel = this.channel
    val size = channel.size()
    if (size == 0L) throw IOException("cannot mmap zero-length source (pipe?)")
    return channel.map(FileChannel.MapMode.READ_ONLY, 0, size)
}
