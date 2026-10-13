package com.saine.pixeltune.presentation.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saine.pixeltune.data.model.Genre
import com.saine.pixeltune.data.model.SearchResultItem
import com.saine.pixeltune.data.model.SearchFilterType
import com.saine.pixeltune.data.model.SearchPage
import com.saine.pixeltune.data.model.Song
import com.saine.pixeltune.data.repository.MusicRepository
import com.saine.pixeltune.data.surf.SurfFeedCache
import com.saine.pixeltune.data.surf.SurfFeedKeys
import com.saine.pixeltune.data.soundcloud.SoundCloudRepository
import com.saine.pixeltune.data.soundcloud.SoundCloudStreamProxy
import com.saine.pixeltune.data.youtube.YouTubeRepository
import com.saine.pixeltune.data.youtube.YouTubeStreamProxy
import com.saine.pixeltune.presentation.viewmodel.SearchStateHolder.OnlineProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import com.saine.pixeltune.data.surf.YoutubeTrendingFeedParser
import javax.inject.Inject
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Detects remaining `%XX` percent-encoding in a navigation argument. */
private val ENCODED_TOKEN_REGEX = Regex("%[0-9A-Fa-f]{2}")

/**
 * SURF: the paged "see all" list behind the discovery feed — the trending
 * chart's full page, the trending playlist pages and the online genre /
 * mood pages. State shape and loading/error handling follow
 * [CloudCatalogViewModel]; pagination follows the online search (opaque
 * [SearchPage] continuation handed back to the repository's `getMore*`
 * overload).
 *
 * The trending page shares the Surf tab's TTL cache entry (identical
 * [SurfFeedKeys.trending] key), so "see all" opens instantly with what the
 * section already shows; a trending playlist's page is cached the same way
 * ([SurfFeedKeys.playlist]). Genre pages run the same live provider search
 * the online search screen runs for the same query — deliberately
 * uncached, exactly like the search results themselves.
 */
@Immutable
data class SurfListUiState(
    val title: String = "",
    val subtitle: String = "",
    val songs: ImmutableList<Song> = persistentListOf(),
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val failed: Boolean = false
)

@HiltViewModel
class SurfListViewModel @Inject constructor(
    private val youTubeRepository: YouTubeRepository,
    private val soundCloudRepository: SoundCloudRepository,
    private val youTubeStreamProxy: YouTubeStreamProxy,
    private val soundCloudStreamProxy: SoundCloudStreamProxy,
    private val musicRepository: MusicRepository,
    private val feedCache: SurfFeedCache<List<Song>>,
    private val searchStateHolder: SearchStateHolder,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private sealed interface Source {
        data object Trending : Source
        data class Genre(val genreId: String) : Source

        /** A "Fresh finds" trending playlist card (YouTube, VL browse id). */
        data class Playlist(val browseId: String, val title: String) : Source
    }

    private val _uiState = MutableStateFlow(SurfListUiState())
    val uiState: StateFlow<SurfListUiState> = _uiState.asStateFlow()

    /** The last loaded genre page — holds the opaque continuation for "Load more". */
    private var lastSearchPage: SearchPage? = null

    private val source: Source

    init {
        val rawSource = savedStateHandle.get<String>("sourceId") ?: "trending"
        val decoded = if (ENCODED_TOKEN_REGEX.containsMatchIn(rawSource)) {
            runCatching { java.net.URLDecoder.decode(rawSource, "UTF-8") }.getOrDefault(rawSource)
        } else {
            rawSource
        }
        source = when {
            decoded.startsWith(GENRE_SOURCE_PREFIX) ->
                Source.Genre(decoded.removePrefix(GENRE_SOURCE_PREFIX))
            decoded.startsWith(PLAYLIST_SOURCE_PREFIX) -> {
                // "playlist:<browseId>|<title>" — the browse id never contains
                // the separator, so the title keeps any '|' of its own.
                val body = decoded.removePrefix(PLAYLIST_SOURCE_PREFIX)
                val separatorIndex = body.indexOf('|')
                if (separatorIndex >= 0) {
                    Source.Playlist(body.take(separatorIndex), body.substring(separatorIndex + 1))
                } else {
                    Source.Playlist(body, "")
                }
            }
            else -> Source.Trending
        }
        load()
    }

    /**
     * Removes one song from the visible listing (the song info sheet's
     * "remove from list" action after an on-device delete) — the counterpart
     * of CloudCatalogViewModel.removeSong.
     */
    fun removeSong(songId: String) {
        _uiState.update { current ->
            if (current.songs.any { it.id == songId }) {
                current.copy(songs = current.songs.filterNot { it.id == songId }.toImmutableList())
            } else {
                current
            }
        }
    }

    /** Retry from the list's error state. */
    fun retry() {
        when (source) {
            is Source.Trending -> feedCache.invalidate(
                SurfFeedKeys.trending(searchStateHolder.currentProvider.value.name)
            )
            is Source.Playlist -> feedCache.invalidate(
                SurfFeedKeys.playlist(
                    searchStateHolder.currentProvider.value.name,
                    source.browseId
                )
            )
            else -> Unit
        }
        load()
    }

    /** Loads the next page (the "Load more" action — genre sources only). */
    fun loadMore() {
        val continuation = lastSearchPage ?: return
        if (_uiState.value.isLoadingMore || !_uiState.value.hasMore) return
        val genreSource = source as? Source.Genre ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            val provider = searchStateHolder.currentProvider.value
            val result = runCatching {
                if (provider == OnlineProvider.YOUTUBE) {
                    youTubeRepository.getMoreYouTubeSearchResults(
                        query = genreQueryFor(_uiState.value.title),
                        filter = SearchFilterType.SONGS,
                        previousPage = continuation,
                        proxyUrlProvider = { youtubeId -> youTubeStreamProxy.getProxyUrl(youtubeId) }
                    )
                } else {
                    soundCloudRepository.getMoreSoundCloudSearchResults(
                        query = genreQueryFor(_uiState.value.title),
                        filter = SearchFilterType.SONGS,
                        previousPage = continuation,
                        proxyUrlProvider = { encodedUrl -> soundCloudStreamProxy.getProxyUrl(encodedUrl) }
                    )
                }
            }
            result
                .onSuccess { page ->
                    lastSearchPage = page
                    _uiState.update { current ->
                        val existing = current.songs.associateBy { it.id }
                        val merged = current.songs + page.results
                            .mapNotNull { (it as? SearchResultItem.SongItem)?.song }
                            .filterNot { existing.containsKey(it.id) }
                        current.copy(
                            isLoadingMore = false,
                            songs = merged.toImmutableList(),
                            hasMore = page.hasMore
                        )
                    }
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    Timber.e(e, "Error loading more Surf list tracks")
                    _uiState.update { it.copy(isLoadingMore = false) }
                }
        }
    }

    private fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, failed = false) }
            val provider = searchStateHolder.currentProvider.value
            try {
                when (source) {
                    is Source.Trending -> {
                        // The title is known without any I/O — publish it so
                        // the loading state can name the feed immediately.
                        _uiState.update {
                            it.copy(
                                title = TRENDING_TITLE,
                                subtitle = providerSubtitle(provider)
                            )
                        }
                        // Same cache key as the Surf tab's section — opening
                        // the full list reuses the feed already shown there.
                        val songs = feedCache.getOrFetch(
                            SurfFeedKeys.trending(provider.name)
                        ) { fetchTrending(provider) }
                        lastSearchPage = null
                        _uiState.update {
                            it.copy(
                                songs = songs.toImmutableList(),
                                isLoading = false,
                                hasMore = false,
                                failed = songs.isEmpty()
                            )
                        }
                    }
                    is Source.Playlist -> {
                        // The card's own metadata already names the page — no
                        // I/O needed before the tracks arrive.
                        _uiState.update {
                            it.copy(
                                title = source.title,
                                subtitle = providerSubtitle(provider)
                            )
                        }
                        // Same cache key the card's own page would use, so
                        // re-opening the playlist is instant within the TTL.
                        val songs = feedCache.getOrFetch(
                            SurfFeedKeys.playlist(provider.name, source.browseId)
                        ) { fetchPlaylistTracks(source, provider) }
                        lastSearchPage = null
                        _uiState.update {
                            it.copy(
                                songs = songs.toImmutableList(),
                                isLoading = false,
                                hasMore = false,
                                failed = songs.isEmpty()
                            )
                        }
                    }
                    is Source.Genre -> {
                        // Resolve the genre locally first (fast Room read) so
                        // the header and loading copy stabilise before the
                        // network page arrives.
                        val genre = resolveGenre(source.genreId)
                        _uiState.update {
                            it.copy(
                                title = genre.name,
                                subtitle = providerSubtitle(provider)
                            )
                        }
                        val page = fetchGenrePage(genre, provider)
                        lastSearchPage = page
                        _uiState.update {
                            it.copy(
                                songs = page.results
                                    .mapNotNull { (it as? SearchResultItem.SongItem)?.song }
                                    .toImmutableList(),
                                isLoading = false,
                                hasMore = page.hasMore,
                                failed = page.results.isEmpty()
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Error loading Surf list")
                _uiState.update { it.copy(isLoading = false, failed = true) }
            }
        }
    }

    private suspend fun fetchTrending(provider: OnlineProvider): List<Song> {
        val songs = if (provider == OnlineProvider.YOUTUBE) {
            youTubeRepository.getTrendingMusic { youtubeId ->
                youTubeStreamProxy.getProxyUrl(youtubeId)
            }
        } else {
            soundCloudRepository.getTrendingTracks { encodedUrl ->
                soundCloudStreamProxy.getProxyUrl(encodedUrl)
            }
        }
        // Providers occasionally repeat a chart entry — distinct ids keep
        // LazyColumn keys unique (the search results guard against the same).
        return songs.distinctBy { it.id }
    }

    /**
     * A trending playlist's tracks — resolved through the raw WEB_REMIX
     * browse ([YouTubeRepository.getTrendingPlaylistTracks]); only YouTube
     * routes here, so any other provider simply yields an empty page.
     */
    private suspend fun fetchPlaylistTracks(
        source: Source.Playlist,
        provider: OnlineProvider
    ): List<Song> {
        if (provider != OnlineProvider.YOUTUBE) return emptyList()
        val playlist = YoutubeTrendingFeedParser.TrendingPlaylist(
            browseId = source.browseId,
            title = source.title,
            artists = "",
            artworkUrl = null
        )
        return youTubeRepository.getTrendingPlaylistTracks(
            playlist = playlist,
            proxyUrlProvider = { youtubeId -> youTubeStreamProxy.getProxyUrl(youtubeId) }
        ).distinctBy { it.id }
    }

    private suspend fun fetchGenrePage(genre: Genre, provider: OnlineProvider): SearchPage {
        val query = genreQueryFor(genre.name)
        return if (provider == OnlineProvider.YOUTUBE) {
            youTubeRepository.searchYouTubePaged(query, SearchFilterType.SONGS) { youtubeId ->
                youTubeStreamProxy.getProxyUrl(youtubeId)
            }
        } else {
            soundCloudRepository.searchSoundCloudPaged(query, SearchFilterType.SONGS) { encodedUrl ->
                soundCloudStreamProxy.getProxyUrl(encodedUrl)
            }
        }
    }

    /**
     * Resolves the route's genre id to a display [Genre] — same resolution as
     * [GenreDetailViewModel]: the library's genre list first (keeping its
     * themed colours), then a graceful name-derived fallback.
     */
    private suspend fun resolveGenre(genreId: String): Genre = withContext(Dispatchers.Default) {
        musicRepository.getGenres().first()
            .find { it.id.equals(genreId, ignoreCase = true) }
            ?: Genre(
                id = genreId,
                name = genreId.replace("_", " ")
                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            )
    }

    private fun providerSubtitle(provider: OnlineProvider): String =
        if (provider == OnlineProvider.YOUTUBE) "YouTube" else "SoundCloud"

    private fun genreQueryFor(name: String): String = "$name music"

    private companion object {
        const val GENRE_SOURCE_PREFIX = "genre:"
        const val PLAYLIST_SOURCE_PREFIX = "playlist:"
        const val TRENDING_TITLE = "Trending"
    }
}
