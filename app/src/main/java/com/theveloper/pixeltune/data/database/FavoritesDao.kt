package com.theveloper.pixeltune.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoritesDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setFavorite(favorite: FavoritesEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(favorites: List<FavoritesEntity>)

    @Query("DELETE FROM favorites WHERE songId = :songId")
    suspend fun removeFavorite(songId: Long)

    @Query("SELECT isFavorite FROM favorites WHERE songId = :songId")
    suspend fun isFavorite(songId: Long): Boolean?

    @Query("SELECT songId FROM favorites WHERE isFavorite = 1")
    fun getFavoriteSongIds(): Flow<List<Long>>

    @Query("SELECT songId FROM favorites WHERE isFavorite = 1")
    suspend fun getFavoriteSongIdsOnce(): List<Long>

    /**
     * Favorite song IDs whose songs row no longer exists. These entries can
     * never surface in the Liked tab (INNER JOIN with songs) and are removed
     * by the favorites reconciliation as self-healing.
     */
    @Query("""
        SELECT songId FROM favorites
        WHERE isFavorite = 1
        AND songId NOT IN (SELECT id FROM songs)
    """)
    suspend fun getOrphanedFavoriteSongIds(): List<Long>

    @Query("SELECT * FROM favorites")
    suspend fun getAllFavoritesOnce(): List<FavoritesEntity>

    @Query("DELETE FROM favorites")
    suspend fun clearAll()
}
