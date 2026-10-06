package com.hippo.ehviewer.coil

import coil3.Extras
import coil3.getExtra
import coil3.intercept.Interceptor
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import com.ehviewer.core.database.model.DownloadInfo
import android.util.Log
import com.ehviewer.core.files.delete
import com.ehviewer.core.files.isDirectory
import com.ehviewer.core.files.isFile
import com.ehviewer.core.files.isSmb
import com.ehviewer.core.files.sendTo
import com.ehviewer.core.files.toUri
import com.hippo.ehviewer.EhApplication.Companion.thumbCache
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.client.getThumbKey
import com.hippo.ehviewer.download.downloadDir

private val downloadInfoKey = Extras.Key<DownloadInfo?>(default = null)

fun ImageRequest.Builder.downloadInfo(info: DownloadInfo) = apply {
    extras[downloadInfoKey] = info
}

val ImageRequest.downloadInfo: DownloadInfo?
    get() = getExtra(downloadInfoKey)

object DownloadThumbInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val info = chain.request.downloadInfo
        if (info != null && !info.dirname.isNullOrBlank()) {
            val thumbKey = getThumbKey(chain.request.data as String)
            if (info.thumbKey != thumbKey) {
                info.thumbKey = thumbKey
                EhDB.putGalleryInfo(info.galleryInfo)
            }
            val dir = info.downloadDir ?: return chain.proceed()
            val format = thumbKey.substringAfterLast('.', "")
            check(format.isNotBlank())
            val thumb = dir / "thumb.$format"
            val v1Thumb = dir / "thumb.jpg"
            if (thumb.isFile) {
                val new = chain.request.newBuilder().data(thumb.toUri()).build()
                val result = chain.withRequest(new).proceed()
                if (result is SuccessResult) {
                    if (thumb != v1Thumb && !dir.isSmb) v1Thumb.delete()
                    return result
                }
            }
            val result = chain.proceed()
            if (result is SuccessResult && dir.isDirectory) {
                // Cache the just-loaded thumbnail into the gallery directory so it
                // shows without re-fetching next time. SMB shares are now writable
                // for this purpose; a write failure must not hide an already-loaded
                // image, so the whole step is best-effort.
                try {
                    val key = requireNotNull(chain.request.memoryCacheKey)
                    thumbCache.read(key) {
                        data sendTo thumb
                    }
                    if (thumb != v1Thumb && !dir.isSmb) v1Thumb.delete()
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to cache thumbnail to $dir", t)
                }
            }
            return result
        }
        return chain.proceed()
    }
}

private const val TAG = "DownloadThumbInterceptor"

