package com.ehviewer.core.database.model

import androidx.room3.Entity
import androidx.room3.ForeignKey
import kotlin.time.Clock

@Entity(tableName = "HISTORY", foreignKeys = [ForeignKey(GalleryEntity::class, ["GID"], ["GID"])])
class HistoryInfo(gid: Long, time: Long = Clock.System.now().toEpochMilliseconds()) : TimeInfo(gid, time)
