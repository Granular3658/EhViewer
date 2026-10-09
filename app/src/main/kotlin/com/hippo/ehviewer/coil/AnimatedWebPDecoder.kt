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
        ): Decoder? {
            val src = result.source.source()
            // Cheap pre-check on the fd-backed source. Reliable on most devices
            // and avoids an extra mmap for genuinely static images. On some
            // kernels an ashmem fd's read() is unreliable (its st_size is
            // reported as 0, so the stream looks empty), so a false negative
            // here is possible even though the bytes are valid.
            if (DecodeUtils.isAnimatedWebP(src)) {
                logcat("WebPDiag") { "AnimatedWebPDecoder.Factory: source pre-check isAnimated=true, mapping (meta=${result.source.metadata})" }
                val mapped = result.source.toByteBufferOrNull() ?: run {
                    logcat("WebPDiag") { "AnimatedWebPDecoder.Factory: toByteBufferOrNull NULL -> static fallback" }
                    return null
                }
                val (buffer, release) = mapped
                logcat("WebPDiag") { "AnimatedWebPDecoder.Factory: mmap OK size=${buffer.capacity()} isAnimated=true" }
                return AnimatedWebPDecoder(buffer, release)
            }
            // Pre-check false. The fd-backed source is unreliable on some
            // devices, so do NOT trust it: verify against the explicitly-sized
            // mmap buffer (sized from the advertised length, not fstat), which
            // always carries the complete bytes — the exact buffer the decoder
            // would use, and the same bytes the static decoder renders the
            // (correct) first frame from.
            val mapped = result.source.toByteBufferOrNull() ?: run {
                logcat("WebPDiag") { "AnimatedWebPDecoder.Factory: pre-check false, toByteBufferOrNull NULL -> static" }
                return null
            }
            val (buffer, release) = mapped
            return if (isAnimatedWebPBuffer(buffer)) {
                logcat("WebPDiag") { "AnimatedWebPDecoder.Factory: PRE-CHECK FALSE but mmap buffer IS animated! size=${buffer.capacity()} (fd-source detection unreliable)" }
                AnimatedWebPDecoder(buffer, release)
            } else {
                logcat("WebPDiag") { "AnimatedWebPDecoder.Factory: truly not animated (mmap) size=${buffer.capacity()} -> static" }
                release?.invoke()
                null
            }
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
        is ByteBufferMetadata -> {
            logcat("WebPDiag") { "toByteBufferOrNull: ByteBufferMetadata" }
            metadata.byteBuffer to null
        }
        else -> null
    }
}

private fun FileInputStream.mapReadOnly(): ByteBuffer {
    val channel = this.channel
    val size = channel.size()
    if (size == 0L) throw IOException("cannot mmap zero-length source (pipe?)")
    return channel.map(FileChannel.MapMode.READ_ONLY, 0, size)
}

/**
 * Detect an animated (extended) WebP directly from the backing bytes, without
 * going through an fd-backed okio Source. The fd Source is unreliable on some
 * kernels (ashmem fstat reports size 0), but the ByteBuffer is sized from the
 * explicit length the SMB provider advertised, so it is always complete.
 *
 * Layout check (big-endian int reads):
 *   bytes  0-3  = "RIFF" (0x52494646)
 *   bytes  8-11 = "WEBP" (0x57454250)
 *   bytes 12-15 = "VP8X" (0x56503858)
 *   byte     20 = flags; animation flag is bit 1 (0x02)
 */
private fun isAnimatedWebPBuffer(buffer: ByteBuffer): Boolean {
    val b = buffer.asReadOnlyBuffer()
    if (b.remaining() < 21) return false
    if (b.getInt(0) != 0x52494646 || b.getInt(8) != 0x57454250) return false
    if (b.getInt(12) != 0x56503858) return false
    return (b.get(20).toInt() and 0xFF and 0x02) != 0
}
