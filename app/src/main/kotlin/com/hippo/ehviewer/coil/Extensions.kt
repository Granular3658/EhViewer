package com.hippo.ehviewer.coil

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.paging.compose.LazyPagingItems
import coil3.decode.BlackholeDecoder
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.SizeResolver
import com.ehviewer.core.database.model.DownloadInfo
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.model.GalleryPreview
import com.ehviewer.core.model.V2GalleryPreview
import com.hippo.ehviewer.client.getThumbKey
import com.hippo.ehviewer.client.getV2PreviewKey
import com.hippo.ehviewer.client.thumbUrl
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.download.downloadDir
import com.hippo.ehviewer.ktbuilder.execute

// Load in original size so the memory cache can be reused for preload requests
fun ImageRequest.Builder.ehUrl(info: GalleryInfo) = apply {
    val key = info.thumbKey
    val downloadInfo = (info as? DownloadInfo) ?: DownloadManager.getDownloadInfo(info.gid)
    if (downloadInfo != null) {
        downloadInfo(downloadInfo)
    }
    if (key == null) {
        // A restored gallery carries no thumbnail key: nothing was ever fetched
        // from the site for it, so the only picture left is one an earlier session
        // cached in the directory. Finding that file is IO, so the directory
        // itself is the data and the thumbnail interceptor looks inside.
        //
        // Not simply left unset: coil rejects a request with no data before any
        // interceptor runs, so an empty request never reaches the one place that
        // knows where the picture is.
        downloadInfo?.downloadDir?.let { data(it) }
        return@apply
    }
    data(info.thumbUrl)
    size(SizeResolver.ORIGINAL)
    memoryCacheKey(key)
    diskCacheKey(key)
}

// Load in original size so the memory cache can be reused for preload requests
fun ImageRequest.Builder.ehPreview(preview: GalleryPreview) = apply {
    with(preview) {
        val key = if (this is V2GalleryPreview) getV2PreviewKey(url) else getThumbKey(url)
        data(url)
        size(SizeResolver.ORIGINAL)
        memoryCacheKey(key)
        diskCacheKey(key)
    }
}

fun ImageRequest.Builder.justDownload() = apply {
    memoryCachePolicy(CachePolicy.DISABLED)
    decoderFactory(BlackholeDecoder.Factory())
}

@Composable
inline fun <T : Any> PrefetchAround(data: LazyPagingItems<T>, index: Int, distance: Int, crossinline f: (T) -> ImageRequest) {
    data.peek((index - distance).coerceAtLeast(0))?.let { fetchBefore ->
        LaunchedEffect(fetchBefore) {
            f(fetchBefore).execute()
        }
    }
    data.peek((index + distance).coerceAtMost(data.itemCount - 1))?.let { fetchAhead ->
        LaunchedEffect(fetchAhead) {
            f(fetchAhead).execute()
        }
    }
}
