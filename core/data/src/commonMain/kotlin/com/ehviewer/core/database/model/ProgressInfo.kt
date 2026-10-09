package com.ehviewer.core.database.model

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.PrimaryKey

@Entity(
    tableName = "PROGRESS",
    foreignKeys = [ForeignKey(GalleryEntity::class, ["GID"], ["GID"], onDelete = ForeignKey.CASCADE)],
)
class ProgressInfo(
    @PrimaryKey
    @ColumnInfo(name = "GID")
    val gid: Long,

    @ColumnInfo(name = "PAGE")
    val page: Int,
)
