package com.ehviewer.core.database.model

import androidx.room3.ColumnInfo
import androidx.room3.PrimaryKey

open class TimeInfo(
    @PrimaryKey
    @ColumnInfo(name = "GID")
    var gid: Long,

    @ColumnInfo(name = "TIME")
    val time: Long,
)
