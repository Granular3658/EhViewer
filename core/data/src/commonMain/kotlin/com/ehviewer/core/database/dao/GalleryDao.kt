package com.ehviewer.core.database.dao

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Update
import androidx.room3.Upsert
import com.ehviewer.core.database.model.GalleryEntity

@Dao
interface GalleryDao {
    @Query("SELECT * FROM GALLERIES WHERE GID = :gid")
    suspend fun load(gid: Long): GalleryEntity?

    @Query("SELECT * FROM GALLERIES")
    suspend fun list(): List<GalleryEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(galleryInfoList: List<GalleryEntity>)

    @Update
    suspend fun update(galleryInfo: GalleryEntity)

    @Update
    suspend fun update(galleryInfoList: List<GalleryEntity>)

    @Upsert
    suspend fun upsert(galleryInfo: GalleryEntity)

    @Delete
    suspend fun delete(galleryInfo: GalleryEntity)

    @Query("DELETE FROM GALLERIES WHERE GID = :gid")
    suspend fun deleteByKey(gid: Long)
}
