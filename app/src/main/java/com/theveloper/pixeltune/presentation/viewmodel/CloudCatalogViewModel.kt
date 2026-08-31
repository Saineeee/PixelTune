package com.theveloper.pixeltune.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixeltune.data.model.CloudArtist
import com.theveloper.pixeltune.data.model.CloudPlaylist
import com.theveloper.pixeltune.data.model.CloudStreamProvider
import com.theveloper.pixeltune.data.model.CloudTracksPage
import com.theveloper.pixeltune.data.model.Song
import com.theveloper.pixeltune.data.soundcloud.SoundCloudRepository
import com.theveloper.pixeltune.data.soundcloud.SoundCloudStreamProxy
import com.theveloper.pixeltune.data.youtube.YouTubeRepository
import com.theveloper.pixeltune.data.youtube.YouTubeStreamProxy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.net.URLDecoder
import javax.inject.Inject

/** Detects remaining `%XX` percent-encoding in a navigation argument. */
private val ENCODED_TOKEN_REGEX = Regex("%[0-9A-Fa-f]{2}")

/**
 * FIX(online-filter-chips): presentation state for the cloud catalog detail
 * screen (a cloud playlist / album / artist found via ONLINE search).
 *
 * The authoritative name/count/artwork NewPipe extracted during the SEARCH
 * are shown immediately; values extracted again while listing the tracks
 * (authoritative, fresher) override them once loaded.
 */
data class CloudCatalogUiState(
    val playlist: CloudPlaylist? = null,
    val artist: CloudArtist? = null,
    val headerTitle: String = "",
    val headerSubtitle: String = "",
    val headerArtworkUrl: String? = null,
    val trackCountLabel: String? = null,
    val songs: List<Song> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null
)

/**
 * FIX(online-filter-chips): ViewModel behind the cloud catalog detail screen
 * ([com.theveloper.pixeltune.presentation.screens.CloudCatalogScreen]).
 *
 * Tapping a cloud playlist/album/artist result on the ONLINE search now
 * navigates here instead of the LOCAL AlbumDetail / ArtistDetail /
 * PlaylistDetail screens (which can never resolve a cloud id and previously
 * rendered empty/broken pages). This ViewModel:
 *
 *  - decodes the tapped entry (a [CloudPlaylist] or [CloudArtist], passed as
 *    URL-encoded JSON through the navigation argument);
 *  - loads its tracks through the SAME repository + stream-proxy path the
 *    online search uses, so every song is immediately playable, likeable and
 *    downloadable exactly like a search result;
 *  - supports the provider's own pagination ("Load more");
 *  - refreshes the header metadata from the extraction.
 */
@HiltViewModel
class CloudCatalogViewModel @Inject constructor(
    private val youTubeRepository: YouTubeRepository,
    private val soundCloudRepository: SoundCloudRepository,
    private val youTubeStreamProxy: YouTubeStreamProxy,
    private val soundCloudStreamProxy: SoundCloudStreamProxy,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }

    private val _uiState = MutableStateFlow(CloudCatalogUiState())
    val uiState: StateFlow<CloudCatalogUiState> = _uiState.asStateFlow()

    /** The last loaded page — holds the opaque continuation for "Load more". */
    private var lastPage: CloudTracksPage? = null

    /** True when the screen was opened from a result row's PLAY button. */
    val autoPlay: Boolean = savedStateHandle.get<String>("autoplay") == "true"

    init {
        val encodedEntry = savedStateHandle.get<String>("entry") ?: ""
        val entryType = savedStateHandle.get<String>("type") ?: ""
        val entry = decodeEntry(encodedEntry, entryType)
        if (entry != null) {
            when (entry) {
                is CloudPlaylist -> _uiState.value = CloudCatalogUiState(
                    playlist = entry,
                    headerTitle = entry.name,
                    headerSubtitle = buildPlaylistSubtitle(entry),
                    headerArtworkUrl = entry.artworkUrl,
                    trackCountLabel = formatTrackCount(entry.trackCount)
                )
                is CloudArtist -> _uiState.value = CloudCatalogUiState(
                    artist = entry,
                    headerTitle = entry.name,
                    headerSubtitle = buildArtistSubtitle(entry),
                    headerArtworkUrl = entry.artworkUrl
                )
            }
            loadFirstPage()
        } else {
            _uiState.value = CloudCatalogUiState(
                error = "This online item could not be opened."
            )
        }
    }

    /**
     * Decodes the navigation argument back into a [CloudPlaylist] or
     * [CloudArtist]. The `type` path argument ("playlist" / "artist") selects
     * the concrete class — both classes' REQUIRED fields overlap, so the type
     * MUST be carried explicitly instead of trying to sniff it from the JSON.
     *
     * The `entry` value is percent-encoded when the route is built
     * ([Screen.CloudCatalog.createRoute]); depending on the navigation
     * component's own decoding, the SavedStateHandle may already hold the
     * decoded JSON. Decoding is therefore applied only when the value still
     * LOOKS percent-encoded (contains `%XX` sequences) — a second decode of
     * plain JSON would corrupt "+" into spaces or throw on stray "%".
     */
    private fun decodeEntry(encoded: String, type: String): Any? {
        if (encoded.isBlank() || type.isBlank()) return null
        val candidate = if (ENCODED_TOKEN_REGEX.containsMatchIn(encoded)) {
            runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(encoded)
        } else {
            encoded
        }
        return when (type) {
            "playlist" -> runCatching { json.decodeFromString<CloudPlaylist>(candidate) }.getOrNull()
            "artist" -> runCatching { json.decodeFromString<CloudArtist>(candidate) }.getOrNull()
            else -> null
        }
    }

    /** (Re)loads the first page of tracks. */
    fun retry() {
        val state = _uiState.value
        if (state.playlist == null && state.artist == null) return
        loadFirstPage()
    }

    /**
     * Removes one song from the visible listing (the song info sheet's
     * "remove from list" action — the counterpart of AlbumDetailScreen's
     * update()).
     */
    fun removeSong(songId: String) {
        _uiState.update { current ->
            if (current.songs.any { it.id == songId }) {
                current.copy(songs = current.songs.filterNot { it.id == songId })
            } else {
                current
            }
        }
    }

    private fun loadFirstPage() {
        val state = _uiState.value
        val playlist = state.playlist
        val artist = state.artist
        if (playlist == null && artist == null) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(isLoading = true, error = null, songs = emptyList(), hasMore = false)
            }
            val result = when {
                playlist != null && playlist.provider == CloudStreamProvider.YOUTUBE ->
                    youTubeRepository.getCloudPlaylistTracks(playlist) { id ->
                        youTubeStreamProxy.getProxyUrl(id)
                    }
                playlist != null ->
                    soundCloudRepository.getCloudPlaylistTracks(playlist) { encoded ->
                        soundCloudStreamProxy.getProxyUrl(encoded)
                    }
                artist != null && artist.provider == CloudStreamProvider.YOUTUBE ->
                    youTubeRepository.getCloudArtistTracks(artist) { id ->
                        youTubeStreamProxy.getProxyUrl(id)
                    }
                else ->
                    soundCloudRepository.getCloudArtistTracks(artist!!) { encoded ->
                        soundCloudStreamProxy.getProxyUrl(encoded)
                    }
            }
            result
                .onSuccess { page ->
                    lastPage = page
                    _uiState.update { current ->
                        current.copy(
                            isLoading = false,
                            songs = page.songs,
                            hasMore = page.hasMore,
                            error = null,
                            // Keep the SEARCH-time title the user tapped —
                            // the extractor's own name is only a fallback (for
                            // YT Music albums it reports e.g. "Album – Recovery",
                            // worse than the clean search title).
                            headerTitle = current.headerTitle.takeIf { it.isNotBlank() }
                                ?: page.refreshedTitle?.takeIf { it.isNotBlank() }
                                ?: current.headerTitle,
                            headerSubtitle = refreshedSubtitleFor(current, page),
                            trackCountLabel = page.refreshedTrackCount?.let { formatTrackCount(it) }
                                ?: current.trackCountLabel
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update { current ->
                        current.copy(
                            isLoading = false,
                            songs = emptyList(),
                            hasMore = false,
                            error = e.message?.takeIf { it.isNotBlank() }
                                ?: "Could not load this item's tracks. Check your connection and try again."
                        )
                    }
                }
        }
    }

    /** Loads the next page of tracks (the "Load more" action). */
    fun loadMore() {
        val state = _uiState.value
        val continuation = lastPage ?: return
        if (state.isLoadingMore || !state.hasMore) return
        val playlist = state.playlist
        val artist = state.artist
        if (playlist == null && artist == null) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            val result = when {
                playlist != null && playlist.provider == CloudStreamProvider.YOUTUBE ->
                    youTubeRepository.getMoreCloudPlaylistTracks(playlist, continuation) { id ->
                        youTubeStreamProxy.getProxyUrl(id)
                    }
                playlist != null ->
                    soundCloudRepository.getMoreCloudPlaylistTracks(playlist, continuation) { encoded ->
                        soundCloudStreamProxy.getProxyUrl(encoded)
                    }
                artist != null && artist.provider == CloudStreamProvider.YOUTUBE ->
                    youTubeRepository.getMoreCloudArtistTracks(artist, continuation) { id ->
                        youTubeStreamProxy.getProxyUrl(id)
                    }
                else ->
                    soundCloudRepository.getMoreCloudArtistTracks(artist!!, continuation) { encoded ->
                        soundCloudStreamProxy.getProxyUrl(encoded)
                    }
            }
            result
                .onSuccess { page ->
                    lastPage = page
                    _uiState.update { current ->
                        // Merge songs, dropping entries whose id already loaded
                        // (providers occasionally repeat an item on a
                        // continuation boundary).
                        val existing = current.songs.associateBy { it.id }
                        val merged = current.songs + page.songs.filterNot { existing.containsKey(it.id) }
                        current.copy(
                            isLoadingMore = false,
                            songs = merged,
                            hasMore = page.hasMore
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update { current ->
                        current.copy(
                            isLoadingMore = false,
                            error = e.message?.takeIf { it.isNotBlank() }
                                ?: "Could not load more tracks."
                        )
                    }
                }
        }
    }

    /**
     * Recomposes the header subtitle after the first page loaded. Playlist
     * extraction reports the uploader as [CloudTracksPage.refreshedSubtitle],
     * artist extraction reports the formatted follower count — the type label
     * (Album/Playlist/Artist) and provider name are re-composed around it.
     */
    private fun refreshedSubtitleFor(current: CloudCatalogUiState, page: CloudTracksPage): String {
        val refreshed = page.refreshedSubtitle?.takeIf { it.isNotBlank() }
        return when {
            current.playlist != null -> {
                val uploader = refreshed
                    ?: current.playlist.uploaderName?.takeIf { it.isNotBlank() }
                    ?: providerLabel(current.playlist.provider)
                val label = if (current.playlist.isAlbum) "Album" else "Playlist"
                "$uploader • $label"
            }
            current.artist != null -> {
                val provider = providerLabel(current.artist.provider)
                val followers = refreshed ?: formatCount(
                    current.artist.subscriberCount,
                    followerUnitFor(current.artist.provider)
                )
                return if (followers != null) "$provider • $followers" else provider
            }
            else -> current.headerSubtitle
        }
    }

    private fun buildPlaylistSubtitle(playlist: CloudPlaylist): String {
        val uploader = playlist.uploaderName?.takeIf { it.isNotBlank() }
            ?: providerLabel(playlist.provider)
        return if (playlist.isAlbum) "$uploader • Album" else "$uploader • Playlist"
    }

    private fun buildArtistSubtitle(artist: CloudArtist): String {
        val provider = providerLabel(artist.provider)
        val followers = formatCount(artist.subscriberCount, followerUnitFor(artist.provider))
        return if (followers != null) "$provider • $followers" else provider
    }

    private fun providerLabel(provider: CloudStreamProvider): String =
        if (provider == CloudStreamProvider.YOUTUBE) "YouTube" else "SoundCloud"

    private fun followerUnitFor(provider: CloudStreamProvider): String =
        if (provider == CloudStreamProvider.YOUTUBE) "subscribers" else "followers"

    private fun formatCount(count: Long, unit: String): String? {
        if (count < 0) return null
        return when {
            count >= 1_000_000L -> {
                val v = count / 1_000_000L
                val frac = (count % 1_000_000L) / 100_000L
                if (frac > 0) "$v.${frac}M $unit" else "$v M $unit"
            }
            count >= 1_000L -> "${count / 1_000L}K $unit"
            else -> "$count $unit"
        }
    }

    private fun formatTrackCount(count: Long): String? {
        if (count < 0) return null
        return if (count == 1L) "1 track" else "$count tracks"
    }
}
