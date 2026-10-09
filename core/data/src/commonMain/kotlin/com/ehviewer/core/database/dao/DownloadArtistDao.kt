package com.ehviewer.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.ehviewer.core.database.model.DownloadArtist

@Dao
interface DownloadArtistDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(downloadArtists: List<DownloadArtist>)

    @Query("DELETE FROM DOWNLOAD_ARTISTS WHERE GID = :gid")
    suspend fun deleteByGid(gid: Long)
}
