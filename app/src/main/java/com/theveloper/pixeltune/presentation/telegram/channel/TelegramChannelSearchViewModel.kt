package com.theveloper.pixeltune.presentation.telegram.channel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixeltune.data.model.Song
import com.theveloper.pixeltune.data.preferences.UserPreferencesRepository
import com.theveloper.pixeltune.data.repository.MusicRepository
import com.theveloper.pixeltune.data.telegram.TelegramRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject

import com.theveloper.pixeltune.presentation.viewmodel.ConnectivityStateHolder

@HiltViewModel
class TelegramChannelSearchViewModel @Inject constructor(
    private val telegramRepository: TelegramRepository,
    private val musicRepository: MusicRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
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
            val chat = _foundChat.value

            try {
                // PERF(sync): chunked backfill - the channel history is
                // fetched in ~500-song batches, each upserted in its own
                // transaction, with progress reported per batch. A minimal
                // channel row is created up-front so an interrupted first
                // import leaves a resume point; re-adding the channel then
                // continues instead of restarting the whole backfill.
                if (chat != null) {
                    val existing = musicRepository.getTelegramChannel(chat.id)
                    if (existing == null) {
                        musicRepository.saveTelegramChannel(
                            com.theveloper.pixeltune.data.database.TelegramChannelEntity(
                                chatId = chat.id,
                                title = chat.title,
                                username = _resolvedUsername.value
                            )
                        )
                    }
                }

                val cap = userPreferencesRepository.telegramSyncMessageCapFlow.first()
                val resumeFrom = musicRepository.getTelegramChannel(chatId)?.lastSyncedMessageId ?: 0L
                val keepSongIds = mutableListOf<String>()

                val result = telegramRepository.syncChannelAudioBatches(
                    chatId = chatId,
                    maxSongs = cap,
                    fromMessageId = resumeFrom
                ) { batch ->
                    musicRepository.upsertTelegramSongsBatch(chatId, batch.songs)
                    musicRepository.setTelegramChannelSyncProgress(chatId, batch.resumeFromMessageId)
                    keepSongIds.addAll(batch.songs.map { it.id })
                    _statusMessage.value = if (batch.totalCountEstimate > 0) {
                        "Syncing songs from channel... ${batch.processedCount}/${batch.totalCountEstimate}"
                    } else {
                        "Syncing songs from channel... ${batch.processedCount}"
                    }
                }

                // Prune channel-removed songs only after a full, exhausted walk.
                if (resumeFrom == 0L && result.exhausted) {
                    musicRepository.pruneTelegramSongsForChannel(chatId, keepSongIds)
                }

                if (result.totalSongs > 0) {
                    var localPhotoPath: String? = null
                    val photoFileId = chat?.photo?.small?.id
                    if (photoFileId != null) {
                        localPhotoPath = telegramRepository.downloadFileAwait(photoFileId)
                    }

                    val entity = com.theveloper.pixeltune.data.database.TelegramChannelEntity(
                        chatId = chatId,
                        title = chat?.title ?: "Unknown Channel",
                        username = _resolvedUsername.value,
                        songCount = musicRepository.countTelegramSongs(chatId),
                        lastSyncTime = System.currentTimeMillis(),
                        lastSyncedMessageId = 0L,
                        photoPath = localPhotoPath
                    )
                    musicRepository.saveTelegramChannel(entity)
                    musicRepository.requestTelegramLibraryResync()

                    _statusMessage.value =
                        "Success! ${entity.songCount} songs added to library. You can close this window."
                } else if (resumeFrom == 0L) {
                    _statusMessage.value = "No audio songs found in this channel."
                } else {
                    _statusMessage.value =
                        "Channel had no more songs below the resume point."
                }
            } catch (e: Exception) {
                // Persisted batches are kept along with the channel row's
                // resume point; re-adding the channel continues the backfill.
                _statusMessage.value =
                    "Sync interrupted - re-add the channel to resume. (${e.message})"
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
