package com.ehviewer.core.database.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "DOWNLOAD_DIRNAME")
data class DownloadDirname(
    @PrimaryKey
    @ColumnInfo(name = "GID")
    val gid: Long,

    @ColumnInfo(name = "DIRNAME")
    val dirname: String,

    /**
     * Which configured location holds this directory, as a path string, or null
     * when the app has not written one down yet.
     *
     * The name alone does not say where it lives -- the locations are searched
     * for it, and on a share that search is a round trip -- so this is recorded
     * at the points that have just resolved it. It answers "where does the app
     * keep this", which is what the download list groups by; finding the files
     * still goes through `downloadDir`, which answers "where is it really".
     */
    @ColumnInfo(name = "LOCATION")
    val location: String? = null,
)
