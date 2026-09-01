package com.theveloper.pixeltune.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "telegram_channels")
data class TelegramChannelEntity(
    @PrimaryKey
    @ColumnInfo(name = "chat_id") val chatId: Long,
    
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "username") val username: String? = null,
    
    @ColumnInfo(name = "song_count") val songCount: Int = 0,
    @ColumnInfo(name = "last_sync_time") val lastSyncTime: Long = 0,

    // PERF(sync): backfill resume point. Zero means the channel is fully
    // synced (or was never synced); non-zero is the oldest message id of the
    // last persisted batch of an interrupted backfill - the next sync
    // continues from there instead of re-fetching from the newest message.
    // Added in migration 25->26.
    @ColumnInfo(name = "last_synced_message_id", defaultValue = "0") val lastSyncedMessageId: Long = 0,
    
    @ColumnInfo(name = "photo_path") val photoPath: String? = null // Local path to cached profile photo
)
