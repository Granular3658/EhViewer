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
import com.ehviewer.core.util.logcat
import java.nio.ByteBuffer
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.updateAndFetch
import okio.Path
import splitties.init.appCtx

class Image private constructor(image: CoilImage, private val src: ImageSource) {
    val refcnt = AtomicInt(1)

    val diagId = System.identityHashCode(this)
    val isAnimatedDrawable: Boolean
        get() = (innerImage as? DrawableImage)?.drawable is AnimatedWebPDrawable
    val isDisposed: Boolean
        get() = ((innerImage as? DrawableImage)?.drawable as? AnimatedWebPDrawable)?.isDisposed ?: false

    fun pin() = refcnt.updateAndFetch { if (it != 0) it + 1 else 0 }.also { new ->
        if (new == 0) logcat("WebPDiag") { "Image#$diagId pin() REFUSED (already disposed) animated=${isAnimatedDrawable}" }
    } != 0

    fun unpin() = (refcnt.decrementAndFetch() == 0).also { zero ->
        if (zero) {
            logcat("WebPDiag") { "Image#$diagId unpin -> refcnt 0, recycling animated=${isAnimatedDrawable}" }
            recycle()
        }
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
                (image.drawable as? AnimatedWebPDrawable)?.let {
                    logcat("WebPDiag") { "Image#$diagId recycle: disposing AnimatedWebPDrawable#${System.identityHashCode(it)}" }
                    it.dispose()
                }
                src.close()
            }
            is BitmapImage -> {
                logcat("WebPDiag") { "Image#$diagId recycle: BitmapImage ${image.bitmap.width}x${image.bitmap.height}" }
                image.bitmap.recycle()
            }
        }
        innerImage = null
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

inline fun byteBufferSource(buffer: ByteBuffer, crossinline release: () -> Unit) = object : ByteBufferSource {
    override val source = buffer
    override fun close() = release()
}

external fun detectBorder(bitmap: Bitmap): IntArray
external fun hasQrCode(bitmap: Bitmap): Boolean
external fun copyBitmapToAHB(src: Bitmap, dst: HardwareBuffer, x: Int, y: Int)
