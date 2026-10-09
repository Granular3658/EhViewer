package com.ehviewer.core.database.model

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(tableName = "DOWNLOAD_LABELS", indices = [Index("LABEL", unique = true)])
data class DownloadLabel(
    @ColumnInfo(name = "LABEL")
    var label: String,

    @ColumnInfo(name = "POSITION")
    var position: Int,

    @PrimaryKey
    @ColumnInfo(name = "_id")
    var id: Long? = null,
)
