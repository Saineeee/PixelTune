package com.theveloper.pixeltune.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TelegramDao {
    @Query("SELECT * FROM telegram_songs ORDER BY date_added DESC")
    fun getAllTelegramSongs(): Flow<List<TelegramSongEntity>>

    @Query("SELECT * FROM telegram_songs WHERE title LIKE '%' || :query || '%' OR artist LIKE '%' || :query || '%' ORDER BY date_added DESC")
    fun searchSongs(query: String): Flow<List<TelegramSongEntity>>

    @Query("SELECT * FROM telegram_songs WHERE id IN (:ids)")
    fun getSongsByIds(ids: List<String>): Flow<List<TelegramSongEntity>>

    @Query("SELECT * FROM telegram_songs WHERE chat_id = :chatId ORDER BY date_added DESC")
    suspend fun getSongsByChatId(chatId: Long): List<TelegramSongEntity>

    @Query("SELECT COUNT(*) FROM telegram_songs WHERE chat_id = :chatId")
    suspend fun countSongsByChatId(chatId: Long): Int

    @Query("SELECT * FROM telegram_channels WHERE chat_id = :chatId")
    suspend fun getChannelById(chatId: Long): TelegramChannelEntity?

    /**
     * PERF(sync): advances a channel's backfill resume point after a batch
     * was persisted. Cheap single-row UPDATE; called once per ~500-song batch.
     */
    @Query("UPDATE telegram_channels SET last_synced_message_id = :lastSyncedMessageId WHERE chat_id = :chatId")
    suspend fun updateSyncProgress(chatId: Long, lastSyncedMessageId: Long)

    @Query("SELECT last_synced_message_id FROM telegram_channels WHERE chat_id = :chatId")
    suspend fun getSyncProgress(chatId: Long): Long?

    /**
     * PERF(sync): batch prune of rows removed from their channel; callers
     * chunk [ids] to stay under SQLite's host-variable limit.
     */
    @Query("DELETE FROM telegram_songs WHERE id IN (:ids)")
    suspend fun deleteSongsByIds(ids: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongs(songs: List<TelegramSongEntity>)
    
    @Query("DELETE FROM telegram_songs WHERE id = :id")
    suspend fun deleteSong(id: String)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannel(channel: TelegramChannelEntity)

    @Query("SELECT * FROM telegram_channels ORDER BY title ASC")
    fun getAllChannels(): Flow<List<TelegramChannelEntity>>

    @Query("DELETE FROM telegram_channels WHERE chat_id = :chatId")
    suspend fun deleteChannel(chatId: Long)

    @Query("DELETE FROM telegram_songs WHERE chat_id = :chatId")
    suspend fun deleteSongsByChatId(chatId: Long)

    @Query("DELETE FROM telegram_songs")
    suspend fun clearAll()
}
