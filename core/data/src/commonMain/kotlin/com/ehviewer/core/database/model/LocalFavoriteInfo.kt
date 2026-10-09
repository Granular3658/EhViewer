package com.ehviewer.core.database.model

import androidx.room3.Entity
import androidx.room3.ForeignKey
import kotlin.time.Clock

@Entity(tableName = "LOCAL_FAVORITES", foreignKeys = [ForeignKey(GalleryEntity::class, ["GID"], ["GID"])])
class LocalFavoriteInfo(gid: Long = 0, time: Long = Clock.System.now().toEpochMilliseconds()) : TimeInfo(gid, time)
