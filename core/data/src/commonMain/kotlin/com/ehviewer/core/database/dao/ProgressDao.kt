package com.ehviewer.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Upsert
import com.ehviewer.core.database.model.ProgressInfo
import kotlinx.coroutines.flow.Flow

@Dao
interface ProgressDao {
    @Query("SELECT IFNULL(PAGE, 0) FROM PROGRESS WHERE GID = :gid")
    suspend fun getPage(gid: Long): Int

    @Query("SELECT IFNULL(PAGE, 0) FROM PROGRESS WHERE GID = :gid")
    fun getPageFlow(gid: Long): Flow<Int>

    @Query("SELECT * FROM PROGRESS")
    suspend fun list(): List<ProgressInfo>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(progress: List<ProgressInfo>)

    @Upsert
    suspend fun upsert(progress: ProgressInfo)

    @Query("DELETE FROM PROGRESS")
    suspend fun deleteAll()
}
