package com.hippo.ehviewer.coil

import android.util.Log
import coil3.Extras
import coil3.getExtra
import coil3.intercept.Interceptor
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import com.ehviewer.core.database.model.DownloadInfo
import com.ehviewer.core.files.delete
import com.ehviewer.core.files.isDirectory
import com.ehviewer.core.files.isFile
import com.ehviewer.core.files.isSmb
import com.ehviewer.core.files.sendTo
import com.ehviewer.core.files.toUri
import com.hippo.ehviewer.EhApplication.Companion.thumbCache
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.client.getThumbKey
import com.hippo.ehviewer.download.THUMB_FILENAMES
import com.hippo.ehviewer.download.downloadDir
import okio.Path

private val downloadInfoKey = Extras.Key<DownloadInfo?>(default = null)

fun ImageRequest.Builder.downloadInfo(info: DownloadInfo) = apply {
    extras[downloadInfoKey] = info
}

val ImageRequest.downloadInfo: DownloadInfo?
    get() = getExtra(downloadInfoKey)

object DownloadThumbInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val info = chain.request.downloadInfo
        if (info == null || info.dirname.isNullOrBlank()) return chain.proceed()
        val dir = info.downloadDir ?: return chain.proceed()

        // The data of a request for a gallery with no thumbnail key is its
        // download directory; see `ehUrl`. Anything else is the site's URL.
        //
        // Not named `data`: inside the cache-write lambda below that name is the
        // source this interceptor is copying, and shadowing it does not compile.
        val payload = chain.request.data
        if (payload is Path) return serveLocalThumb(chain, info, dir)
        val url = payload as? String ?: return chain.proceed()

        val thumbKey = getThumbKey(url)
        if (info.thumbKey != thumbKey) {
            info.thumbKey = thumbKey
            EhDB.putGalleryInfo(info.galleryInfo)
        }
        val format = thumbKey.substringAfterLast('.', "")
        check(format.isNotBlank())
        val thumb = dir / "thumb.$format"
        val v1Thumb = dir / "thumb.jpg"
        // A name recorded last time is opened without asking whether it is there:
        // on a share that question is a round trip, and the file it names is
        // almost always still the one that was written.
        if (info.localThumb == thumb.name) {
            serve(chain, thumb)?.let { return it }
            // The name outlived the file; look again below.
            rememberLocalThumb(info, null)
        }
        if (thumb.isFile) {
            serve(chain, thumb)?.let {
                rememberLocalThumb(info, thumb.name)
                return it
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
                rememberLocalThumb(info, thumb.name)
                if (thumb != v1Thumb && !dir.isSmb) v1Thumb.delete()
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to cache thumbnail to $dir", t)
            }
        }
        return result
    }

    /**
     * The picture a restored gallery can still show.
     *
     * Such a gallery has no thumbnail key -- nothing was fetched from the site for
     * it, and neither the marker nor `ComicInfo.xml` holds one -- so the request
     * carries the download directory instead. An earlier session may have cached
     * the image next to the pages, which is the only picture there can be.
     */
    private suspend fun serveLocalThumb(chain: Interceptor.Chain, info: DownloadInfo, dir: Path): ImageResult {
        // A recorded name is opened first and for free; only a name that no
        // longer resolves costs the probe.
        info.localThumb?.let { name ->
            serve(chain, dir / name)?.let { return it }
            rememberLocalThumb(info, null)
        }
        val found = THUMB_FILENAMES.map { dir / it }.firstOrNull { it.isFile } ?: return chain.proceed()
        return serve(chain, found)?.also { rememberLocalThumb(info, found.name) } ?: chain.proceed()
    }

    /** Hands the request to the file, or null when that file cannot be decoded. */
    private suspend fun serve(chain: Interceptor.Chain, file: Path): ImageResult? = chain.withRequest(chain.request.newBuilder().data(file.toUri()).build()).proceed().takeIf {
        it is SuccessResult
    }

    /**
     * Records which file holds this gallery's thumbnail.
     *
     * Nothing is written when the name has not changed: this runs for every card
     * that is drawn, and the answer stays the same until the file moves.
     */
    private suspend fun rememberLocalThumb(info: DownloadInfo, name: String?) {
        if (info.localThumb == name) return
        info.localThumb = name
        EhDB.putDownloadInfo(info)
    }
}

private const val TAG = "DownloadThumbInterceptor"
