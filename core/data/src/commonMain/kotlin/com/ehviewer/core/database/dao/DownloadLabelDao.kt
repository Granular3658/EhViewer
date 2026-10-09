package com.ehviewer.core.database.dao

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Update
import com.ehviewer.core.database.model.DownloadLabel

@Dao
interface DownloadLabelDao {
    @Query("SELECT * FROM DOWNLOAD_LABELS ORDER BY POSITION")
    suspend fun list(): List<DownloadLabel>

    @Query("SELECT * FROM DOWNLOAD_LABELS ORDER BY POSITION LIMIT :limit OFFSET :offset")
    suspend fun list(offset: Int, limit: Int): List<DownloadLabel>

    @Query("SELECT LABEL FROM DOWNLOAD_LABELS WHERE LABEL LIKE :pattern ORDER BY POSITION LIMIT :limit")
    suspend fun search(pattern: String, limit: Int): List<String>

    @Query("UPDATE DOWNLOAD_LABELS SET POSITION = POSITION - 1 WHERE POSITION > :position")
    suspend fun fill(position: Int)

    @Update
    suspend fun update(downloadLabels: List<DownloadLabel>)

    @Update
    suspend fun update(downloadLabel: DownloadLabel)

    @Insert
    suspend fun insert(downloadLabel: DownloadLabel): Long

    @Insert
    suspend fun insert(downloadLabels: List<DownloadLabel>)

    @Delete
    suspend fun delete(downloadLabel: DownloadLabel)
}
