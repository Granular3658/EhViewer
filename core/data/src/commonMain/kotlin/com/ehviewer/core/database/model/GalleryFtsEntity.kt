package com.ehviewer.core.database.model

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Fts4

@Fts4(contentEntity = GalleryEntity::class)
@Entity(tableName = "GALLERIES_FTS")
class GalleryFtsEntity(
    @ColumnInfo(name = "TITLE")
    val title: String,

    @ColumnInfo(name = "TITLE_JPN")
    val titleJpn: String,

    @ColumnInfo(name = "SIMPLE_TAGS")
    val simpleTags: String?,
)
