package com.theveloper.pixeltune.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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

    /**
     * Resume watermark for chunked channel sync: the oldest message id already
     * persisted for a chat. Channel history is paged newest-to-oldest, so the
     * rows themselves are the durable "last persisted batch" marker — a sync
     * interrupted mid-way resumes by continuing from (strictly older than)
     * this id, without any extra schema.
     */
    @Query("SELECT MIN(message_id) FROM telegram_songs WHERE chat_id = :chatId")
    suspend fun getOldestPersistedMessageId(chatId: Long): Long?

    @Query("SELECT COUNT(*) FROM telegram_songs WHERE chat_id = :chatId")
    suspend fun countSongsForChat(chatId: Long): Int

    /**
     * Upserts one sync chunk in a single transaction: all song rows plus the
     * channel row (kept in the same transaction so a partially-persisted
     * batch can never be observed, and so the resume watermark above is
     * always consistent with the channel metadata).
     */
    @Transaction
    suspend fun upsertChannelBatch(songs: List<TelegramSongEntity>, channel: TelegramChannelEntity?) {
        if (songs.isNotEmpty()) {
            insertSongs(songs)
        }
        channel?.let { insertChannel(it) }
    }
}
