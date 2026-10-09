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
        ): Decoder? {
            val src = result.source.source()
            // Cheap pre-check on the fd-backed source. It works on most devices
            // and lets us skip the extra mmap for genuinely static images. But
            // the fd-backed okio Source *read* is intermittently unreliable on
            // older kernels: on the Linux 4.4 test device it produced ~20% false
            // negatives (genuinely animated WebPs read back as if empty), while
            // the Linux 4.19 device reproduced none. The exact kernel mechanism
            // is unconfirmed (likely an ashmem fd-read quirk/race), and it is
            // INDEPENDENT of the ashmem fstat=0 behaviour handled below — both
            // kernels report fstat=0, yet only 4.4 misreads the bytes.
            if (DecodeUtils.isAnimatedWebP(src)) {
                val mapped = result.source.toByteBufferOrNull() ?: return null
                val (buffer, release) = mapped
                return AnimatedWebPDecoder(buffer, release)
            }
            // Pre-check false. The fd-backed Source *read* is unreliable on older
            // kernels (see above), so do NOT trust it: verify against the
            // explicitly-sized mmap buffer (sized from the advertised length, not
            // fstat), which always carries the complete bytes — the exact buffer
            // the decoder would use, and the same bytes the static decoder
            // renders the (correct) first frame from.
            val mapped = result.source.toByteBufferOrNull() ?: return null
            val (buffer, release) = mapped
            return if (isAnimatedWebPBuffer(buffer)) {
                AnimatedWebPDecoder(buffer, release)
            } else {
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
            return file.toFile().inputStream().mapReadOnly() to null
        }
    }
    return when (val metadata = metadata) {
        // Local files are mmap'd zero-copy via FileChannel. SMB-served files
        // arrive as an in-memory ashmem fd whose fstat ALWAYS reports 0 —
        // Android's ashmem driver never fills i_size, on every Android version
        // and kernel (verified on both the LOS18.1 and LOS22 test devices), so
        // FileChannel.map() cannot size the mapping from fstat. Map it natively
        // with the real size the provider advertised: zero copy, the buffer's
        // backing is the ashmem region itself, never the JVM heap. The provider
        // advertises -1 for the pipe fallback, which we reject so the caller can
        // fall back.
        is ContentMetadata -> {
            val afd = metadata.assetFileDescriptor
            if (afd.length > 0) {
                val fd = afd.parcelFileDescriptor?.fd ?: -1
                if (fd < 0) throw IOException("cannot mmap ashmem: missing fd")
                val buffer = smbMmapReadOnly(fd, afd.length)
                    ?: throw IOException("cannot mmap ashmem fd=$fd size=${afd.length}")
                buffer to { smbMunmap(buffer) }
            } else {
                // Pipe / unknown-length source: cannot be mmap'd natively and we
                // don't have a declared size, so let Coil fall back to another
                // decoder rather than crash on a zero-length mapping.
                null
            }
        }
        is ByteBufferMetadata -> {
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
 * going through an fd-backed okio Source. The fd Source *read* is intermittently
 * unreliable on older kernels (hence the mmap fallback above); the ByteBuffer is
 * sized from the explicit length the SMB provider advertised, so it is always
 * complete. (Separately, an ashmem fd's fstat is always 0 on every Android
 * version — that is why we advertise the length rather than rely on fstat for
 * the mmap — but that universal behaviour is independent of the fd-read
 * flakiness above.)
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
