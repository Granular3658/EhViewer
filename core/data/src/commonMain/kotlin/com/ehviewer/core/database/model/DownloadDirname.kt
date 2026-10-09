package com.ehviewer.core.database.model

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity(tableName = "DOWNLOAD_DIRNAME")
data class DownloadDirname(
    @PrimaryKey
    @ColumnInfo(name = "GID")
    val gid: Long,

    @ColumnInfo(name = "DIRNAME")
    val dirname: String,
)
