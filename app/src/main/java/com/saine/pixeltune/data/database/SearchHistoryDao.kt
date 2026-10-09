package com.saine.pixeltune.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SearchHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: SearchHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<SearchHistoryEntity>)

    @Query("SELECT * FROM search_history ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentSearches(limit: Int): List<SearchHistoryEntity>

    @Query("DELETE FROM search_history WHERE query = :query")
    suspend fun deleteByQuery(query: String)

    @Query("DELETE FROM search_history")
    suspend fun clearAll()

    @Query("SELECT * FROM search_history ORDER BY timestamp DESC")
    suspend fun getAll(): List<SearchHistoryEntity>

    /**
     * IMPROVE(history-limit-settings): keeps only the [limit] most recent
     * search_history rows (newest first by timestamp, then id as the
     * deterministic tie-breaker) and deletes everything older. Called after
     * every insert and whenever the user lowers the "Max Search History"
     * slider in Settings -> Appearance, so the collected history never
     * outgrows the user's cap.
     */
    @Query(
        "DELETE FROM search_history WHERE id NOT IN " +
            "(SELECT id FROM search_history ORDER BY timestamp DESC, id DESC LIMIT :limit)"
    )
    suspend fun trimToLimit(limit: Int)
}
