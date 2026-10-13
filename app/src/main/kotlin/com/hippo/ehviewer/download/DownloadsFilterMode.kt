package com.hippo.ehviewer.download

import com.ehviewer.core.database.model.DownloadInfo
import com.ehviewer.core.database.model.artists

enum class DownloadsFilterMode(
    val flag: Int,
    val take: (info: DownloadInfo, label: String?) -> Boolean,
) {
    CUSTOM(flag = 0, take = { info, label ->
        label == "" || info.label == label
    }),
    ARTIST(flag = 1, take = { info, label ->
        if (null == label) {
            info.artistInfoList.isEmpty()
        } else {
            label == "" || info.artists.contains(label)
        }
    }),
    LOCATION(flag = 2, take = { info, label ->
        // Where a download lives is not stored on it: it is whichever configured
        // location holds its directory, which is what `downloadDir` resolves (and
        // caches). A location is therefore identified by that path, and the label
        // is its string form.
        val location = info.downloadDir?.parent?.toString()
        if (null == label) {
            // Saved somewhere that is no longer a configured location -- a
            // directory that was removed from the list, or the default the
            // resolver falls back to. The counterpart of an unknown artist.
            location != null && allDownloadLocations.none { it.toString() == location }
        } else {
            label == "" || location == label
        }
    }),
    ;

    companion object {
        val Default = ARTIST
        fun from(flag: Int) = DownloadsFilterMode.entries[flag]
    }
}
