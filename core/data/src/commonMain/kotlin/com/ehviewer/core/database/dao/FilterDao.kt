package com.ehviewer.core.database.dao

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Update
import com.ehviewer.core.database.model.Filter

@Dao
interface FilterDao {
    @Query("SELECT * FROM FILTER")
    suspend fun list(): List<Filter>

    @Update
    suspend fun update(filter: Filter)

    @Insert
    suspend fun insert(t: Filter): Long

    @Insert
    suspend fun insert(filters: List<Filter>)

    @Delete
    suspend fun delete(filter: Filter)

    @Query("SELECT * FROM FILTER WHERE TEXT = :text AND MODE = :mode")
    suspend fun load(text: String, mode: Int): Filter?
}
