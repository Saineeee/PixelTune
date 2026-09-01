package com.theveloper.pixeltune.presentation.telegram.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixeltune.data.database.TelegramChannelEntity
import com.theveloper.pixeltune.data.repository.MusicRepository
import com.theveloper.pixeltune.data.telegram.TelegramRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import com.theveloper.pixeltune.presentation.viewmodel.ConnectivityStateHolder

@HiltViewModel
class TelegramDashboardViewModel @Inject constructor(
    private val telegramRepository: TelegramRepository,
    private val musicRepository: MusicRepository,
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

    fun refreshChannel(channel: TelegramChannelEntity) {
        if (_isRefreshing.value != null) return

        viewModelScope.launch {
            _isRefreshing.value = channel.chatId
            _statusMessage.value = "Syncing ${channel.title}..."

            try {
                // Chunked, resumable channel sync: each committed chunk reports
                // progress so the status stays live during large refreshes.
                val result = telegramRepository.syncChannelAudio(channel.chatId) { persistedSoFar ->
                    _statusMessage.value = "Syncing ${channel.title}... $persistedSoFar songs"
                }

                // The chunked sync persists directly via TelegramDao; ask the
                // incremental worker to merge the new rows into the unified DB.
                musicRepository.requestIncrementalSync()

                // Update metadata
                val updatedChannel = channel.copy(
                    songCount = result.totalSongsForChat,
                    lastSyncTime = System.currentTimeMillis()
                )
                musicRepository.saveTelegramChannel(updatedChannel)

                _statusMessage.value = when {
                    result.interruptedBy != null ->
                        "Sync interrupted after ${result.newlyPersistedCount} songs — will resume next refresh"
                    result.hitMessageCap ->
                        "Synced ${result.newlyPersistedCount} songs (message cap reached — more available next refresh)"
                    result.newlyPersistedCount > 0 ->
                        "Synced ${result.newlyPersistedCount} songs from ${channel.title} (${result.totalSongsForChat} total)"
                    else ->
                        "No new songs found in ${channel.title} (${result.totalSongsForChat} total)"
                }
            } catch (e: Exception) {
                _statusMessage.value = "Sync failed: ${e.message}"
            } finally {
                _isRefreshing.value = null
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
