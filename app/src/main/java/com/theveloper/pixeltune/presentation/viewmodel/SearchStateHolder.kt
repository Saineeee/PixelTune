package com.theveloper.pixeltune.presentation.viewmodel

import android.util.Log
import com.theveloper.pixeltune.data.model.SearchFilterType
import com.theveloper.pixeltune.data.model.SearchHistoryItem
import com.theveloper.pixeltune.data.model.SearchResultItem
import com.theveloper.pixeltune.data.model.Song
import com.theveloper.pixeltune.data.repository.MusicRepository
import com.theveloper.pixeltune.data.youtube.YouTubeRepository
import com.theveloper.pixeltune.data.youtube.YouTubeStreamProxy
import com.theveloper.pixeltune.data.soundcloud.SoundCloudRepository
import com.theveloper.pixeltune.data.soundcloud.SoundCloudStreamProxy
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages search state and operations.
 * Extracted from PlayerViewModel to improve modularity.
 *
 * Responsibilities:
 * - Search query execution
 * - Search filter management
 * - Search history CRUD operations
 */
@Singleton
class SearchStateHolder @Inject constructor(
    private val musicRepository: MusicRepository,
    private val youTubeRepository: YouTubeRepository,
    private val youTubeStreamProxy: YouTubeStreamProxy,
    private val soundCloudRepository: SoundCloudRepository,
    private val soundCloudStreamProxy: SoundCloudStreamProxy
) {
    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
    }

    private data class SearchRequest(
        val query: String,
        val requestId: Long,
        val isOnline: Boolean
    )

    enum class OnlineProvider {
        YOUTUBE, SOUNDCLOUD
    }

    private val _currentProvider = MutableStateFlow(OnlineProvider.YOUTUBE)
    val currentProvider = _currentProvider.asStateFlow()

    fun setOnlineProvider(provider: OnlineProvider) {
        _currentProvider.value = provider
    }

    /**
     * IMPROVE(provider-switch): searches the given provider for playable songs
     * matching [query] and returns them as a list of [Song]s (best match first).
     *
     * Used by [com.theveloper.pixeltune.presentation.viewmodel.PlayerViewModel.setOnlineProvider]
     * to re-fetch the currently playing song on the newly selected streaming
     * provider (YouTube -> SoundCloud handoff and vice versa) without touching
     * the regular search flow / search results UI state.
     *
     * The songs are built with the exact same repository calls the normal
     * search uses — their `contentUriString` therefore points at the CURRENT
     * session's stream proxy and they are immediately playable.
     */
    suspend fun searchSongsOnProvider(query: String, provider: OnlineProvider): List<Song> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                val results = if (provider == OnlineProvider.YOUTUBE) {
                    youTubeRepository.searchYouTube(normalizedQuery, SearchFilterType.SONGS) { youtubeId ->
                        youTubeStreamProxy.getProxyUrl(youtubeId)
                    }
                } else {
                    soundCloudRepository.searchSoundCloud(normalizedQuery, SearchFilterType.SONGS) { encodedUrl ->
                        soundCloudStreamProxy.getProxyUrl(encodedUrl)
                    }
                }
                results.mapNotNull { (it as? SearchResultItem.SongItem)?.song }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SearchStateHolder", "Error searching provider $provider for query: $normalizedQuery", e)
                emptyList()
            }
        }
    }

    // Search State
    private val _searchResults = MutableStateFlow<ImmutableList<SearchResultItem>>(persistentListOf())
    val searchResults = _searchResults.asStateFlow()

    /**
     * IMPROVE(search-loading): true while an ONLINE search request is in
     * flight (from the moment the debounced request starts executing until
     * its results are published / it fails / it is superseded by a newer
     * query). Drives the expressive Material 3 loading indicator on the
     * Search screen. Local (MediaStore) searches resolve in milliseconds and
     * deliberately never set this flag.
     */
    private val _isSearching = MutableStateFlow(false)
    val isSearching = _isSearching.asStateFlow()

    private val _selectedSearchFilter = MutableStateFlow(SearchFilterType.ALL)
    val selectedSearchFilter = _selectedSearchFilter.asStateFlow()

    private val _searchHistory = MutableStateFlow<ImmutableList<SearchHistoryItem>>(persistentListOf())
    val searchHistory = _searchHistory.asStateFlow()

    private val _isOnlineSearch = MutableStateFlow(true)
    val isOnlineSearch = _isOnlineSearch.asStateFlow()

    private val searchRequests = MutableSharedFlow<SearchRequest>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val latestSearchRequestId = AtomicLong(0L)

    private var scope: CoroutineScope? = null
    private var searchJob: Job? = null

    /**
     * Initialize with ViewModel scope.
     */
    fun initialize(scope: CoroutineScope) {
        this.scope = scope
        observeSearchRequests()
    }

    private fun observeSearchRequests() {
        searchJob?.cancel()
        searchJob = scope?.launch {
            searchRequests
                .debounce(SEARCH_DEBOUNCE_MS)
                .collectLatest { request ->
                    val normalizedQuery = request.query

                    if (normalizedQuery.isBlank()) {
                        if (_searchResults.value.isNotEmpty()) {
                            _searchResults.value = persistentListOf()
                        }
                        _isSearching.value = false
                        return@collectLatest
                    }

                    try {
                        // IMPROVE(search-loading): surface the loading indicator
                        // the moment the (debounced) request starts executing.
                        // Only online searches are slow enough to warrant it.
                        _isSearching.value = request.isOnline

                        val currentFilter = _selectedSearchFilter.value

                        val resultsList = withContext(Dispatchers.IO) {
                            if (request.isOnline) {
                                if (_currentProvider.value == OnlineProvider.YOUTUBE) {
                                    youTubeRepository.searchYouTube(normalizedQuery, currentFilter) { youtubeId ->
                                        youTubeStreamProxy.getProxyUrl(youtubeId)
                                    }
                                } else {
                                    soundCloudRepository.searchSoundCloud(normalizedQuery, currentFilter) { encodedUrl ->
                                        soundCloudStreamProxy.getProxyUrl(encodedUrl)
                                    }
                                }
                            } else {
                                musicRepository.searchAll(normalizedQuery, currentFilter).first()
                            }
                        }

                        if (request.requestId != latestSearchRequestId.get()) {
                            // Superseded by a newer query — that request owns
                            // the loading flag now; do not touch it.
                            return@collectLatest
                        }

                        _isSearching.value = false

                        val immutableResults = resultsList.toImmutableList()
                        if (_searchResults.value != immutableResults) {
                            _searchResults.value = immutableResults
                        }

                        // FIX(cloud-streaming-speed): warm the resolved stream
                        // URL for the TOP search result in the background.
                        //
                        // The stream proxies resolve a song's real streaming
                        // URL lazily — the first ExoPlayer request for a tapped
                        // song pays the full NewPipe extraction on the playback
                        // critical path. The MusicService prefetch already warms
                        // the NEXT queued song on every transition, but the FIRST
                        // tap of a fresh search had nothing warmed. The top result
                        // is by far the most likely tap target, so resolving its
                        // URL while the user scans the list makes that tap start
                        // playback from an already-cached URL.
                        //
                        // Cheap and safe by construction:
                        //  - the proxies' prefetch() validates the URL is one of
                        //    their own loopback URLs and silently ignores anything
                        //    else (empty strings when the proxy was not started,
                        //    local-library results, etc.);
                        //  - an in-flight guard dedupes concurrent prefetches and
                        //    fresh cache entries are skipped, so repeated searches
                        //    for the same query never re-extract;
                        //  - it runs on the proxies' own IO scope and never blocks
                        //    publishing the results above.
                        if (request.isOnline) {
                            (resultsList.firstOrNull() as? SearchResultItem.SongItem)
                                ?.song?.contentUriString
                                ?.takeIf { it.startsWith("http") }
                                ?.let { proxyUrl ->
                                    if (_currentProvider.value == OnlineProvider.YOUTUBE) {
                                        youTubeStreamProxy.prefetch(proxyUrl)
                                    } else {
                                        soundCloudStreamProxy.prefetch(proxyUrl)
                                    }
                                }
                        }
                    } catch (_: CancellationException) {
                        // Superseded by a newer query; the newer collector
                        // re-raises the flag immediately — leave the state
                        // untouched so the indicator doesn't flicker.
                    } catch (e: Exception) {
                        if (request.requestId == latestSearchRequestId.get()) {
                            Log.e("SearchStateHolder", "Error performing search for query: $normalizedQuery", e)
                            _searchResults.value = persistentListOf()
                            _isSearching.value = false
                        }
                    }
                }
        }
    }

    fun updateSearchFilter(filterType: SearchFilterType) {
        _selectedSearchFilter.value = filterType
    }

    fun toggleSearchMode(isOnline: Boolean) {
        _isOnlineSearch.value = isOnline
    }

    fun loadSearchHistory(limit: Int = 15) {
        scope?.launch {
            try {
                val history = withContext(Dispatchers.IO) {
                    musicRepository.getRecentSearchHistory(limit)
                }
                _searchHistory.value = history.toImmutableList()
            } catch (e: Exception) {
                Log.e("SearchStateHolder", "Error loading search history", e)
            }
        }
    }

    fun onSearchQuerySubmitted(query: String) {
        scope?.launch {
            if (query.isNotBlank()) {
                try {
                    withContext(Dispatchers.IO) {
                        musicRepository.addSearchHistoryItem(query)
                    }
                    loadSearchHistory()
                } catch (e: Exception) {
                    Log.e("SearchStateHolder", "Error adding search history item", e)
                }
            }
        }
    }

    fun performSearch(query: String) {
        val normalizedQuery = query.trim()

        val requestId = latestSearchRequestId.incrementAndGet()

        if (normalizedQuery.isBlank()) {
            if (_searchResults.value.isNotEmpty()) {
                _searchResults.value = persistentListOf()
            }
        }

        searchRequests.tryEmit(SearchRequest(normalizedQuery, requestId, _isOnlineSearch.value))
    }

    fun deleteSearchHistoryItem(query: String) {
        scope?.launch {
            try {
                withContext(Dispatchers.IO) {
                    musicRepository.deleteSearchHistoryItemByQuery(query)
                }
                loadSearchHistory()
            } catch (e: Exception) {
                Log.e("SearchStateHolder", "Error deleting search history item", e)
            }
        }
    }

    fun clearSearchHistory() {
        scope?.launch {
            try {
                withContext(Dispatchers.IO) {
                    musicRepository.clearSearchHistory()
                }
                _searchHistory.value = persistentListOf()
            } catch (e: Exception) {
                Log.e("SearchStateHolder", "Error clearing search history", e)
            }
        }
    }

    fun onCleared() {
        searchJob?.cancel()
        scope = null
    }
}
