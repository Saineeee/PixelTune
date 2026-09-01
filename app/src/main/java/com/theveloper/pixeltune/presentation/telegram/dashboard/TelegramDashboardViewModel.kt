package com.theveloper.pixeltune.presentation.telegram.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixeltune.data.database.TelegramChannelEntity
import com.theveloper.pixeltune.data.preferences.UserPreferencesRepository
import com.theveloper.pixeltune.data.repository.MusicRepository
import com.theveloper.pixeltune.data.telegram.TelegramRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import com.theveloper.pixeltune.presentation.viewmodel.ConnectivityStateHolder

/**
 * PERF(sync): live progress of a chunked Telegram channel backfill.
 * [totalSongsEstimate] is TDLib's approximate channel size (-1 unknown).
 */
data class TelegramSyncProgress(
    val chatId: Long,
    val processedSongs: Int,
    val totalSongsEstimate: Int
)

@HiltViewModel
class TelegramDashboardViewModel @Inject constructor(
    private val telegramRepository: TelegramRepository,
    private val musicRepository: MusicRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val connectivityStateHolder: ConnectivityStateHolder
) : ViewModel() {

    val isOnline = connectivityStateHolder.isOnline

    // Expose channels flow directly
    val channels = musicRepository.getAllTelegramChannels()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _isRefreshing = MutableStateFlow<Long?>(null) // ChatId being refreshed, or null
    val isRefreshing = _isRefreshing.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage = _statusMessage.asStateFlow()

    private val _syncProgress = MutableStateFlow<TelegramSyncProgress?>(null)
    val syncProgress = _syncProgress.asStateFlow()

    /**
     * PERF(sync): refreshes a channel with a chunked backfill.
     *
     * The channel history is fetched in ~500-song batches; each batch is
     * upserted in its own database transaction and advances the channel's
     * resume point, and progress is reported per batch. A sync interrupted
     * mid-way (process death, cancellation, network failure) leaves the
     * persisted batches in place and a non-zero resume point, so the next
     * refresh continues from the last persisted batch instead of starting
     * over. Only a full, exhausted sync prunes songs that were deleted from
     * the channel since the last complete sync.
     */
    fun refreshChannel(channel: TelegramChannelEntity) {
        if (_isRefreshing.value != null) return

        viewModelScope.launch {
            _isRefreshing.value = channel.chatId

            // Re-read the channel row: its resume point is the source of
            // truth for whether this is a fresh sync or a continuation.
            val current = musicRepository.getTelegramChannel(channel.chatId) ?: channel
            val resumeFrom = current.lastSyncedMessageId
            _statusMessage.value = if (resumeFrom > 0L) {
                "Resuming sync of ${current.title}..."
            } else {
                "Syncing ${current.title}..."
            }
            _syncProgress.value = TelegramSyncProgress(channel.chatId, 0, -1)

            try {
                val cap = userPreferencesRepository.telegramSyncMessageCapFlow.first()
                val keepSongIds = mutableListOf<String>()

                val result = telegramRepository.syncChannelAudioBatches(
                    chatId = current.chatId,
                    maxSongs = cap,
                    fromMessageId = resumeFrom
                ) { batch ->
                    // One transaction per batch, then advance the resume point.
                    musicRepository.upsertTelegramSongsBatch(current.chatId, batch.songs)
                    musicRepository.setTelegramChannelSyncProgress(
                        current.chatId,
                        batch.resumeFromMessageId
                    )
                    keepSongIds.addAll(batch.songs.map { it.id })
                    _syncProgress.value = TelegramSyncProgress(
                        chatId = current.chatId,
                        processedSongs = batch.processedCount,
                        totalSongsEstimate = batch.totalCountEstimate
                    )
                    _statusMessage.value = buildString {
                        append("Syncing ${current.title}... ${batch.processedCount}")
                        if (batch.totalCountEstimate > 0) append("/${batch.totalCountEstimate}")
                        append(" songs")
                    }
                }

                // Only a sync that started from the newest message and walked
                // the history to its end has the complete keep-set, so only
                // then is pruning stale rows safe.
                if (resumeFrom == 0L && result.exhausted) {
                    musicRepository.pruneTelegramSongsForChannel(current.chatId, keepSongIds)
                }

                val songCount = musicRepository.countTelegramSongs(current.chatId)
                musicRepository.saveTelegramChannel(
                    current.copy(
                        songCount = songCount,
                        lastSyncTime = System.currentTimeMillis(),
                        lastSyncedMessageId = 0L
                    )
                )
                // Mirror the updated telegram_songs rows into the unified library.
                musicRepository.requestTelegramLibraryResync()

                _statusMessage.value = if (songCount > 0) {
                    "Synced: $songCount songs in ${current.title}"
                } else {
                    "No songs found in ${current.title}"
                }
            } catch (e: Exception) {
                // Batches persisted so far are kept; the channel row still
                // holds the last batch's resume point, so the next refresh
                // continues from there.
                _statusMessage.value =
                    "Sync interrupted - will resume from the last batch. (${e.message})"
            } finally {
                _isRefreshing.value = null
                _syncProgress.value = null
            }
        }
    }

    fun removeChannel(chatId: Long) {
        viewModelScope.launch {
            musicRepository.deleteTelegramChannel(chatId)
            _statusMessage.value = "Channel removed"
        }
    }

    fun clearStatus() {
        _statusMessage.value = null
    }

    fun refreshChannels() {
        // Trigger a connectivity check
        connectivityStateHolder.refreshLocalConnectionInfo()
    }
}
