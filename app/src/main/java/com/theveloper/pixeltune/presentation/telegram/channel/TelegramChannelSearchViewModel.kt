package com.theveloper.pixeltune.presentation.telegram.channel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixeltune.data.model.Song
import com.theveloper.pixeltune.data.repository.MusicRepository
import com.theveloper.pixeltune.data.telegram.TelegramRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject

import com.theveloper.pixeltune.presentation.viewmodel.ConnectivityStateHolder

@HiltViewModel
class TelegramChannelSearchViewModel @Inject constructor(
    private val telegramRepository: TelegramRepository,
    private val musicRepository: MusicRepository,
    connectivityStateHolder: ConnectivityStateHolder
) : ViewModel() {

    val isOnline = connectivityStateHolder.isOnline

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _resolvedUsername = MutableStateFlow<String?>(null)

    private val _foundChat = MutableStateFlow<TdApi.Chat?>(null)
    val foundChat = _foundChat.asStateFlow()

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs = _songs.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    // Status message for errors or "Not Found"
    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage = _statusMessage.asStateFlow()

    private val _playbackRequest = kotlinx.coroutines.flow.MutableSharedFlow<Song>(extraBufferCapacity = 1)
    val playbackRequest = _playbackRequest.asSharedFlow()

    private fun extractUsername(input: String): String {
        val trimmed = input.trim()
        return when {
            trimmed.contains("t.me/") -> "@" + trimmed
                .substringAfterLast("t.me/")
                .substringBefore("?")
                .substringBefore("/")
                .removePrefix("@")
            trimmed.startsWith("@") -> trimmed
            else -> "@$trimmed"
        }
    }
    fun onQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun searchChannel() {
        val query = _searchQuery.value
        if (query.isNotEmpty()) {
            _isLoading.value = true
            _statusMessage.value = null
            _foundChat.value = null
            _songs.value = emptyList()

            val resolvedUsername = extractUsername(query)
            _resolvedUsername.value = resolvedUsername

            viewModelScope.launch {
                val chat = telegramRepository.searchPublicChat(resolvedUsername)
                _isLoading.value = false

                if (chat != null) {
                    _foundChat.value = chat
                    fetchSongs(chat.id)
                } else {
                    _statusMessage.value = "Channel not found"
                }
            }
        }
    }

    private fun fetchSongs(chatId: Long) {
        _isLoading.value = true
        _statusMessage.value = "Syncing songs from channel..."

        viewModelScope.launch {
            // Chunked, resumable channel sync: each committed chunk reports
            // progress so the status stays live during large imports.
            val result = telegramRepository.syncChannelAudio(chatId) { persistedSoFar ->
                _statusMessage.value = "Syncing songs from channel... $persistedSoFar"
            }

            if (result.interruptedBy != null) {
                _statusMessage.value = "Sync interrupted after ${result.newlyPersistedCount} songs — will resume next refresh"
            } else if (result.totalSongsForChat > 0 || result.newlyPersistedCount > 0) {
                // The chunked sync persists directly via TelegramDao; ask the
                // incremental worker to merge the new rows into the unified DB.
                musicRepository.requestIncrementalSync()

                // Save Channel Entity
                val chat = _foundChat.value
                if (chat != null) {
                    var localPhotoPath: String? = null
                    val photoFileId = chat.photo?.small?.id
                    if (photoFileId != null) {
                        localPhotoPath = telegramRepository.downloadFileAwait(photoFileId)
                    }

                    val entity = com.theveloper.pixeltune.data.database.TelegramChannelEntity(
                        chatId = chat.id,
                        title = chat.title,
                        username = _resolvedUsername.value,
                        songCount = result.totalSongsForChat,
                        lastSyncTime = System.currentTimeMillis(),
                        photoPath = localPhotoPath
                    )
                    musicRepository.saveTelegramChannel(entity)
                }

                _statusMessage.value = if (result.hitMessageCap) {
                    "Imported ${result.newlyPersistedCount} songs (message cap reached — more available on next refresh). You can close this window."
                } else {
                    "Success! ${result.newlyPersistedCount} songs added to library (${result.totalSongsForChat} total). You can close this window."
                }
            } else {
                _statusMessage.value = "No audio songs found in this channel."
            }
            // We do NOT update _songs to avoid showing the list
            _songs.value = emptyList()
            _isLoading.value = false
        }
    }

    fun downloadAndPlay(song: Song) {
        if (song.telegramFileId == null) return

        _isLoading.value = true
        _statusMessage.value = "Downloading ${song.title}..."

        viewModelScope.launch {
            val localPath = telegramRepository.downloadFileAwait(song.telegramFileId)
            _isLoading.value = false

            if (localPath != null) {
                // Create a new Song with the local path
                val playableSong = song.copy(path = localPath, contentUriString = localPath)
                musicRepository.saveTelegramSongs(listOf(playableSong)) // Update DB with path
                _playbackRequest.tryEmit(playableSong)
                _statusMessage.value = "Playing..."
            } else {
                _statusMessage.value = "Failed to download song"
            }
        }
    }

    fun resetState() {
        _searchQuery.value = ""
        _foundChat.value = null
        _songs.value = emptyList()
        _isLoading.value = false
        _statusMessage.value = null
        _resolvedUsername.value = null
    }
}
