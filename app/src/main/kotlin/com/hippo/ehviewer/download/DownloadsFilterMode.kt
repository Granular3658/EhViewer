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
        // Which location holds a download is recorded on it, not searched for:
        // the places that resolve a directory write down what they found, and
        // the list reads that. A download nothing has been written down for --
        // one that predates the record, or one whose location has since been
        // taken off the list -- is unknown, and is deliberately not looked up
        // again here: what is being filtered is the list, not the share.
        val location = info.location?.takeIf { saved -> allDownloadLocations.any { it.toString() == saved } }
        if (null == label) {
            location == null
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
