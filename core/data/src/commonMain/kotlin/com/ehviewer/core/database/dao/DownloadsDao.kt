package com.ehviewer.core.database.dao

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.MapColumn
import androidx.room3.Query
import androidx.room3.RoomWarnings
import androidx.room3.Transaction
import androidx.room3.Update
import androidx.room3.Upsert
import com.ehviewer.core.database.model.DownloadEntity
import com.ehviewer.core.database.model.DownloadInfo
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadsDao {
    // https://issuetracker.google.com/327583152
    @Suppress("ktlint:standard:annotation", RoomWarnings.UNNECESSARY_NULLABILITY_IN_DAO_RETURN_TYPE)
    @Query("SELECT LABEL, COUNT(*) AS COUNT FROM DOWNLOADS LEFT JOIN DOWNLOAD_LABELS USING(LABEL) GROUP BY LABEL")
    fun countByLabel(): Flow<Map<@MapColumn("LABEL") String?, @MapColumn("COUNT") Int>>

    @Suppress("ktlint:standard:annotation", RoomWarnings.UNNECESSARY_NULLABILITY_IN_DAO_RETURN_TYPE)
    @Query("SELECT ARTIST, COUNT(*) AS COUNT FROM DOWNLOADS LEFT JOIN DOWNLOAD_ARTISTS USING(GID) GROUP BY ARTIST ORDER BY COUNT DESC, ARTIST")
    fun countByArtist(): Flow<Map<@MapColumn("ARTIST") String?, @MapColumn("COUNT") Int>>

    @Query("SELECT * FROM DOWNLOADS ORDER BY TIME")
    suspend fun list(): List<DownloadEntity>

    @Transaction
    @Query("SELECT * FROM DOWNLOADS LEFT JOIN DOWNLOAD_DIRNAME USING(GID) ORDER BY TIME DESC")
    suspend fun joinList(): List<DownloadInfo>

    @Update
    suspend fun update(downloadInfo: List<DownloadEntity>)

    @Insert
    suspend fun insert(downloadInfo: List<DownloadEntity>)

    @Upsert
    suspend fun upsert(t: DownloadEntity)

    @Delete
    suspend fun delete(downloadInfo: DownloadEntity)

    @Delete
    suspend fun delete(downloadInfo: List<DownloadEntity>)
}
