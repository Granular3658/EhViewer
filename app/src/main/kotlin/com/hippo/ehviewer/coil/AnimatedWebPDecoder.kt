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
import com.ehviewer.core.util.logcat
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
            logcat("WebPDiag") { "AnimatedWebPDecoder.Factory: animated webp detected, mapping source (metadata=${result.source.metadata})" }
            val mapped = result.source.toByteBufferOrNull()
            if (mapped == null) {
                logcat("WebPDiag") { "AnimatedWebPDecoder.Factory: toByteBufferOrNull NULL -> falls back to static decoder (first frame only!)" }
                null
            } else {
                val (buffer, release) = mapped
                logcat("WebPDiag") { "AnimatedWebPDecoder.Factory: mmap OK size=${buffer.capacity()}" }
                AnimatedWebPDecoder(buffer, release)
            }
        } else {
            logcat("WebPDiag") { "AnimatedWebPDecoder.Factory: isAnimatedWebP=false -> skip (static decoder will handle)" }
            null
        }
    }
}

private fun ImageSource.toByteBufferOrNull(): Pair<ByteBuffer, (() -> Unit)?>? {
    if (fileSystem === FileSystem.SYSTEM) {
        val file = fileOrNull()
        if (file != null) {
            logcat("WebPDiag") { "toByteBufferOrNull: LOCAL file mmap" }
            return file.toFile().inputStream().mapReadOnly() to null
        }
    }
    return when (val metadata = metadata) {
        // Local files are mmap'd zero-copy via FileChannel. SMB-served files
        // arrive as an in-memory ashmem fd whose fstat reports 0 on some Android
        // versions (notably large files on older kernels), so FileChannel.map()
        // cannot size the mapping. Map it natively with the real size the
        // provider advertised: zero copy, the buffer's backing is the ashmem
        // region itself, never the JVM heap. The provider advertises -1 for the
        // pipe fallback, which we reject so the caller can fall back.
        is ContentMetadata -> {
            val afd = metadata.assetFileDescriptor
            if (afd.length > 0) {
                val fd = afd.parcelFileDescriptor?.fd ?: -1
                if (fd < 0) throw IOException("cannot mmap ashmem: missing fd")
                logcat("WebPDiag") { "toByteBufferOrNull: ASHMEM mmap fd=$fd len=${afd.length}" }
                val buffer = smbMmapReadOnly(fd, afd.length)
                    ?: throw IOException("cannot mmap ashmem fd=$fd size=${afd.length}")
                buffer to { smbMunmap(buffer) }
            } else {
                logcat("WebPDiag") { "toByteBufferOrNull: pipe/unknown length=${afd.length} -> null (fallback to non-mmap decoder)" }
                // Pipe / unknown-length source: cannot be mmap'd natively and we
                // don't have a declared size, so let Coil fall back to another
                // decoder rather than crash on a zero-length mapping.
                null
            }
        }
        is ByteBufferMetadata -> { logcat("WebPDiag") { "toByteBufferOrNull: ByteBufferMetadata" }; metadata.byteBuffer to null }
        else -> null
    }
}

private fun FileInputStream.mapReadOnly(): ByteBuffer {
    val channel = this.channel
    val size = channel.size()
    if (size == 0L) throw IOException("cannot mmap zero-length source (pipe?)")
    return channel.map(FileChannel.MapMode.READ_ONLY, 0, size)
}
