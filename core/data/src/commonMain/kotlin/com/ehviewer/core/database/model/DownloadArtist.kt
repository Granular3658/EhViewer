package com.ehviewer.core.database.model

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey

@Entity(
    tableName = "DOWNLOAD_ARTISTS",
    primaryKeys = ["GID", "ARTIST"],
    foreignKeys = [ForeignKey(DownloadEntity::class, ["GID"], ["GID"], onDelete = ForeignKey.CASCADE)],
)
data class DownloadArtist(
    @ColumnInfo(name = "GID")
    val gid: Long,

    @ColumnInfo(name = "ARTIST")
    val artist: String,
) {
    companion object {
        fun from(gid: Long, artists: Iterable<String>) = artists.map { DownloadArtist(gid, it) }
    }
}
