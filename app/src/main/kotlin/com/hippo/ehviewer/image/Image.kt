/*
 * Copyright 2022 Tarsin Norbin
 *
 * This file is part of EhViewer
 *
 * EhViewer is free software: you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * EhViewer is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with EhViewer.
 * If not, see <https://www.gnu.org/licenses/>.
 */
package com.hippo.ehviewer.image

import android.graphics.Bitmap
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.hardware.HardwareBuffer
import androidx.compose.ui.unit.IntSize
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import arrow.fx.coroutines.ExitCase
import arrow.fx.coroutines.bracketCase
import coil3.BitmapImage
import coil3.DrawableImage
import coil3.Image as CoilImage
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.maxBitmapSize
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import coil3.size.SizeResolver
import com.ehviewer.core.files.isSmb
import com.ehviewer.core.files.openFileDescriptor
import com.ehviewer.core.files.toUri
import com.ehviewer.core.util.isAtLeastP
import com.ehviewer.core.util.isAtLeastU
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.coil.AnimatedWebPDrawable
import com.hippo.ehviewer.coil.BitmapImageWithExtraInfo
import com.hippo.ehviewer.coil.detectQrCode
import com.hippo.ehviewer.coil.hardwareThreshold
import com.hippo.ehviewer.coil.maybeCropBorder
import com.hippo.ehviewer.jni.isGif
import com.hippo.ehviewer.jni.mmap
import com.hippo.ehviewer.jni.munmap
import com.hippo.ehviewer.jni.rewriteGifSource
import com.hippo.ehviewer.ktbuilder.execute
import com.hippo.ehviewer.ktbuilder.imageRequest
import java.lang.ref.PhantomReference
import java.lang.ref.ReferenceQueue
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.updateAndFetch
import kotlin.concurrent.thread
import okio.Path
import splitties.init.appCtx

class Image private constructor(image: CoilImage, private val src: ImageSource) {
    val refcnt = AtomicInt(1)

    val isAnimatedDrawable: Boolean
        get() = (innerImage as? DrawableImage)?.drawable is AnimatedWebPDrawable
    val isDisposed: Boolean
        get() = ((innerImage as? DrawableImage)?.drawable as? AnimatedWebPDrawable)?.isDisposed ?: false

    fun pin() = refcnt.updateAndFetch { if (it != 0) it + 1 else 0 } != 0

    fun unpin() = (refcnt.decrementAndFetch() == 0).also { zero ->
        if (zero) recycle()
    }

    val intrinsicSize = with(image) { IntSize(width, height) }
    val allocationSize = image.size

    // Extra bytes held alive for the page (e.g. the animated WebP source buffer
    // that libwebp reads lazily). Counted by the page cache so it actually
    // evicts pages instead of growing without bound.
    var sourceBufferSize = 0L
        private set
    val hasQrCode = when (image) {
        is BitmapImageWithExtraInfo -> image.hasQrCode
        else -> false
    }

    var innerImage: CoilImage? = when (image) {
        is BitmapImageWithExtraInfo -> image.image
        else -> image
    }

    private fun recycle() {
        when (val image = innerImage!!) {
            is DrawableImage -> {
                // The platform's animated drawable (Coil's AnimatedImageDecoder, which
                // is what a GIF page becomes) decodes frames on a thread of its own and
                // reads the source buffer as it goes. Nothing in the platform both
                // stops it and waits for the frame in flight -- `stop()` only flips a
                // flag -- so freeing the buffer here frees it under that read, and the
                // read is a SIGSEGV on the animated image thread. That is what took the
                // process down when a page was turned away from while still animating.
                // So a source that drawable is reading is not freed here: the drawable
                // keeps it, and it goes when the drawable is collected, which cannot
                // happen while it is still able to decode.
                if (!handOverToAnimatedDrawable(image.drawable)) {
                    (image.drawable as? AnimatedWebPDrawable)?.close()
                    src.close()
                }
            }
            is BitmapImage -> {
                image.bitmap.recycle()
            }
        }
        innerImage = null
    }

    /**
     * Stops a platform animated drawable and gives it the source to release once it is
     * collected, returning whether it took it.
     *
     * Only that drawable is treated this way. Ours is disposed synchronously -- it
     * cancels and joins its decode before its decoder goes, which is the guarantee
     * the platform does not offer -- and a static drawable never reads the source.
     */
    private fun handOverToAnimatedDrawable(drawable: Drawable?): Boolean {
        if (!isAtLeastP || drawable !is AnimatedImageDrawable) return false
        drawable.stop()
        SourceRelease.track(drawable, src)
        return true
    }

    companion object {
        private val sizeResolver = with(appCtx.resources.displayMetrics) {
            val targetSize = minOf(widthPixels, heightPixels) * 4 / 3
            SizeResolver(Size(targetSize, targetSize))
        }

        private suspend fun Either<ByteBufferSource, PathSource>.decodeCoil(checkExtraneousAds: Boolean): CoilImage {
            val request = with(appCtx) {
                imageRequest {
                    onLeft { data(it.source) }
                    onRight { data(it.source.toUri()) }
                    size(sizeResolver)
                    scale(Scale.FILL)
                    precision(Precision.INEXACT)
                    maxBitmapSize(Size.ORIGINAL)
                    allowHardware(false)
                    hardwareThreshold(Settings.hardwareBitmapThreshold.value)
                    maybeCropBorder(Settings.cropBorder.value)
                    detectQrCode(checkExtraneousAds)
                    memoryCachePolicy(CachePolicy.DISABLED)
                }
            }
            return when (val result = request.execute()) {
                is SuccessResult -> result.image
                is ErrorResult -> throw result.throwable
            }
        }

        suspend fun decode(src: ImageSource, checkExtraneousAds: Boolean = false): Image {
            val image = when (src) {
                is PathSource -> {
                    if (isAtLeastP && !isAtLeastU && !src.source.isSmb) {
                        src.source.openFileDescriptor("rw").use {
                            val fd = it.fd
                            if (isGif(fd)) {
                                return bracketCase(
                                    { mmap(fd)!! },
                                    { buffer -> decode(byteBufferSource(buffer) { munmap(buffer).also { src.close() } }, checkExtraneousAds) },
                                    { buffer, case -> if (case !is ExitCase.Completed) munmap(buffer) },
                                )
                            }
                        }
                    }
                    src.right().decodeCoil(checkExtraneousAds)
                }
                is ByteBufferSource -> {
                    if (isAtLeastP && !isAtLeastU) {
                        rewriteGifSource(src.source)
                    }
                    src.left().decodeCoil(checkExtraneousAds)
                }
            }
            return Image(image, src).apply {
                if (innerImage is BitmapImage) src.close()
                (innerImage as? DrawableImage)?.drawable?.let { d ->
                    if (d is AnimatedWebPDrawable) sourceBufferSize = d.sourceSize.toLong()
                }
            }
        }
    }
}

sealed interface ImageSource : AutoCloseable

interface PathSource : ImageSource {
    val source: Path
    val type: String
}

interface ByteBufferSource : ImageSource {
    val source: ByteBuffer
}

/**
 * A source that lends out `buffer` and calls `release` once it is done with.
 *
 * `close()` is guarded because closing twice is normal, not exceptional: this is
 * a `Closeable`, and a source reaches its end through more than one route -- the
 * loader that produced it, and the `Image` that took it, both release it. The
 * release frees native memory (`dwarfsReleaseByteBuffer` frees a page buffer
 * `malloc`ed on the native side; the others unmap), so a second call frees the
 * same block twice. The allocator notices -- "invalid chunk state when
 * deallocating" -- and the process dies with a SIGABRT, which is what made
 * DwarFS pages crash at random: whichever of the two routes ran first decided
 * whether the second call found live memory or not.
 */
inline fun byteBufferSource(buffer: ByteBuffer, crossinline release: () -> Unit) = object : ByteBufferSource {
    private val released = AtomicBoolean(false)
    override val source = buffer
    override fun close() {
        if (released.compareAndSet(false, true)) {
            release()
        } else {
            // Both routes releasing the same source is what used to free the page
            // buffer twice, so it is worth knowing when it happens -- and it costs
            // a line only when it does.
            logcat("bufferSource") { "a second close of the same page buffer was refused" }
        }
    }
}

external fun detectBorder(bitmap: Bitmap): IntArray
external fun hasQrCode(bitmap: Bitmap): Boolean
external fun copyBitmapToAHB(src: Bitmap, dst: HardwareBuffer, x: Int, y: Int)

/**
 * Releases an [ImageSource] once the drawable that reads it has been collected.
 *
 * A phantom reference rather than `java.lang.ref.Cleaner`: this app's `minSdk` is 26 and
 * `Cleaner` arrived in API 33. The reference itself is held in [pending] because one that
 * nothing reaches is collected before its referent is, and its action would never run.
 */
private object SourceRelease {
    private val queue = ReferenceQueue<Drawable>()
    private val pending = Collections.newSetFromMap(ConcurrentHashMap<DrawableSource, Boolean>())

    init {
        thread(isDaemon = true, name = "image-source-release") {
            while (true) {
                try {
                    val released = queue.remove() as DrawableSource
                    pending.remove(released)
                    released.release()
                } catch (_: InterruptedException) {
                    // A daemon that holds nothing else; an interrupt leaves nothing to
                    // unwind, so the next collection is waited for instead.
                }
            }
        }
    }

    fun track(drawable: Drawable, source: ImageSource) {
        pending.add(DrawableSource(drawable, source))
    }

    private class DrawableSource(drawable: Drawable, private val source: ImageSource) :
        PhantomReference<Drawable>(drawable, queue) {
        fun release() = source.close()
    }
}
