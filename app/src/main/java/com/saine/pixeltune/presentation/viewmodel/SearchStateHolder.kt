package com.saine.pixeltune.presentation.viewmodel

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.saine.pixeltune.data.model.SearchFilterType
import com.saine.pixeltune.data.model.SearchHistoryItem
import com.saine.pixeltune.data.model.SearchPage
import com.saine.pixeltune.data.model.SearchResultItem
import com.saine.pixeltune.data.model.Song
import com.saine.pixeltune.data.repository.MusicRepository
import com.saine.pixeltune.data.preferences.UserPreferencesRepository
import com.saine.pixeltune.utils.SearchResultKeys
import com.saine.pixeltune.data.youtube.YouTubeRepository
import com.saine.pixeltune.data.youtube.YouTubeStreamProxy
import com.saine.pixeltune.data.soundcloud.SoundCloudRepository
import com.saine.pixeltune.data.soundcloud.SoundCloudStreamProxy
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
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
    private val soundCloudStreamProxy: SoundCloudStreamProxy,
    // IMPROVE(history-limit-settings): the max number of recent online
    // searches the app collects — user-tunable from Settings -> Appearance
    // ("Max Search History" slider). Observed live so slider changes trim +
    // reload the history without an app restart.
    private val userPreferencesRepository: UserPreferencesRepository
) {
    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L

        /** Debounce window for ONLINE AUTOCOMPLETE requests while typing. */
        const val SUGGESTION_DEBOUNCE_MS = 300L

        /** Autocomplete only fires for queries with at least this many characters. */
        const val MIN_SUGGESTION_QUERY_LENGTH = 2
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
        // IMPROVE(online-search-autocomplete): the visible suggestions came
        // from the PREVIOUS provider — drop them (a late response from the
        // old provider is also blocked by the request-id guard). The next
        // autocomplete request fetches fresh suggestions from the newly
        // selected provider.
        if (provider != _currentProvider.value) {
            _currentProvider.value = provider
            clearSuggestions()
        }
    }

    /**
     * IMPROVE(provider-switch): searches the given provider for playable songs
     * matching [query] and returns them as a list of [Song]s (best match first).
     *
     * Used by [com.saine.pixeltune.presentation.viewmodel.PlayerViewModel.setOnlineProvider]
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
     * IMPROVE(search-load-more): true while the provider reports MORE pages
     * for the current online search — drives the "Load more" row at the
     * bottom of the search results (every filter chip).
     */
    private val _hasMoreSearchResults = MutableStateFlow(false)
    val hasMoreSearchResults = _hasMoreSearchResults.asStateFlow()

    /** IMPROVE(search-load-more): true while a "Load more" page is in flight. */
    private val _isLoadingMoreSearchResults = MutableStateFlow(false)
    val isLoadingMoreSearchResults = _isLoadingMoreSearchResults.asStateFlow()

    /**
     * IMPROVE(search-load-more): the current online search's opaque
     * continuation (the provider's token for the next page). Reset on every
     * new search; advanced by [loadMoreSearchResults]. Null for local
     * searches (local search is instant and needs no pagination) and after
     * the last page has been consumed.
     */
    private var searchContinuation: Any? = null

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

    // ---------------------------------------------------------------------
    // IMPROVE(online-search-autocomplete): ONLINE SEARCH AUTOCOMPLETE
    //
    // While the user TYPES in Online mode the app no longer fires the full
    // provider search on every keystroke — it only fetches autocomplete
    // suggestions. The full search pipeline runs when a query is COMMITTED
    // (keyboard Search action, suggestion tap, or history entry). The
    // suggestion state is kept SEPARATE from [_searchResults] on purpose:
    // suggestions describe what the user may WANT to search; results
    // describe an already-committed search.
    // ---------------------------------------------------------------------

    private data class SuggestionRequest(
        val query: String,
        val requestId: Long,
        val provider: OnlineProvider
    )

    /** Autocomplete suggestions for the currently typed online query (empty = none). */
    private val _searchSuggestions = MutableStateFlow<ImmutableList<String>>(persistentListOf())
    val searchSuggestions = _searchSuggestions.asStateFlow()

    /** True while an autocomplete request is in flight. */
    private val _isLoadingSuggestions = MutableStateFlow(false)
    val isLoadingSuggestions = _isLoadingSuggestions.asStateFlow()

    private val suggestionRequests = MutableSharedFlow<SuggestionRequest>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /**
     * Monotonic id of the NEWEST autocomplete request. A fetch may only
     * publish its result while `requestId == latestSuggestionRequestId` —
     * the guard that keeps a STALE response (one the user already replaced,
     * cleared, switched away from, or that survived cancellation) from
     * overwriting the suggestions that belong to the CURRENT query.
     */
    private val latestSuggestionRequestId = AtomicLong(0L)

    private var suggestionJob: Job? = null

    /**
     * Dispatcher the autocomplete pipeline fetches on. Production always
     * uses [Dispatchers.IO]; unit tests replace it with a test dispatcher so
     * the debounce window, cancellation and stale-response guards can be
     * asserted with deterministic virtual time.
     */
    @VisibleForTesting
    internal var suggestionsIoDispatcher: CoroutineDispatcher = Dispatchers.IO

    private var scope: CoroutineScope? = null
    private var searchJob: Job? = null

    /**
     * IMPROVE(history-limit-settings): the user's search-history cap
     * (Settings -> Appearance). Starts at the DataStore default and is kept
     * in sync by the collector launched in [initialize]; volatile because the
     * collector coroutine and the UI-facing loaders read it concurrently.
     */
    @Volatile
    private var searchHistoryLimit: Int = UserPreferencesRepository.DEFAULT_ONLINE_SEARCH_HISTORY_LIMIT

    /**
     * Initialize with ViewModel scope.
     */
    fun initialize(scope: CoroutineScope) {
        this.scope = scope
        observeSearchRequests()
        // IMPROVE(online-search-autocomplete): the debounced suggestion
        // pipeline lives on the same scope as the search pipeline.
        observeSuggestionRequests()
        // IMPROVE(history-limit-settings): observe the "Max Search History"
        // preference. On change: trim the STORED history down to the new cap
        // (a lowered slider shrinks the collected history immediately — not
        // just on the next search) and reload the visible list with the new
        // limit so the recent-searches landing reflects it right away.
        scope.launch {
            userPreferencesRepository.onlineSearchHistoryLimitFlow.collect { limit ->
                if (limit != searchHistoryLimit) {
                    searchHistoryLimit = limit
                    try {
                        withContext(Dispatchers.IO) {
                            musicRepository.trimSearchHistory(limit)
                        }
                        loadSearchHistory()
                    } catch (e: Exception) {
                        Log.e("SearchStateHolder", "Error applying search history limit", e)
                    }
                }
            }
        }
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
                        // IMPROVE(search-load-more): a blank query has no pages.
                        searchContinuation = null
                        _hasMoreSearchResults.value = false
                        _isLoadingMoreSearchResults.value = false
                        return@collectLatest
                    }

                    try {
                        // IMPROVE(search-loading): surface the loading indicator
                        // the moment the (debounced) request starts executing.
                        // Only online searches are slow enough to warrant it.
                        _isSearching.value = request.isOnline

                        val currentFilter = _selectedSearchFilter.value

                        // IMPROVE(search-load-more): online searches now use
                        // the PAGED repository variants so the continuation —
                        // and with it the "Load more" row — is available on
                        // EVERY filter chip. A new search always resets the
                        // previous search's continuation first.
                        searchContinuation = null
                        _hasMoreSearchResults.value = false

                        val resultsList = withContext(Dispatchers.IO) {
                            if (request.isOnline) {
                                val page: SearchPage =
                                    if (_currentProvider.value == OnlineProvider.YOUTUBE) {
                                        youTubeRepository.searchYouTubePaged(normalizedQuery, currentFilter) { youtubeId ->
                                            youTubeStreamProxy.getProxyUrl(youtubeId)
                                        }
                                    } else {
                                        soundCloudRepository.searchSoundCloudPaged(normalizedQuery, currentFilter) { encodedUrl ->
                                            soundCloudStreamProxy.getProxyUrl(encodedUrl)
                                        }
                                    }
                                if (request.requestId == latestSearchRequestId.get()) {
                                    searchContinuation = page.continuation
                                    _hasMoreSearchResults.value = page.hasMore && page.continuation != null
                                }
                                page.results
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

                        // IMPROVE(search-load-more): remember the query its
                        // continuation pages belong to (a "Load more" always
                        // continues THIS query).
                        if (request.isOnline) {
                            lastCommittedQuery = normalizedQuery
                        }

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
                            searchContinuation = null
                            _hasMoreSearchResults.value = false
                        }
                    }
                }
        }
    }

    fun updateSearchFilter(filterType: SearchFilterType) {
        _selectedSearchFilter.value = filterType
    }

    /**
     * IMPROVE(search-load-more): loads the NEXT page of the current ONLINE
     * search ("Load more" row at the bottom of the results — available on
     * EVERY filter chip: All / Songs / Albums / Artists / Playlists).
     *
     * Follows the CURRENT provider + filter + query; appends the new page's
     * items (deduped by stable keys — providers occasionally repeat an item
     * on page boundaries); advances the continuation. Guarded so repeated
     * taps and concurrent loads are no-ops.
     */
    fun loadMoreSearchResults() {
        val isOnline = _isOnlineSearch.value
        if (!isOnline) return
        if (_isLoadingMoreSearchResults.value || !_hasMoreSearchResults.value) return
        val continuation = searchContinuation ?: return
        val query = lastCommittedQuery
        if (query.isBlank()) return

        scope?.launch {
            _isLoadingMoreSearchResults.value = true
            try {
                val filter = _selectedSearchFilter.value
                val previousPage = SearchPage(
                    results = emptyList(),
                    hasMore = true,
                    continuation = continuation
                )
                val nextPage = withContext(Dispatchers.IO) {
                    if (_currentProvider.value == OnlineProvider.YOUTUBE) {
                        youTubeRepository.getMoreYouTubeSearchResults(
                            query = query,
                            filter = filter,
                            previousPage = previousPage,
                            proxyUrlProvider = { youtubeId ->
                                youTubeStreamProxy.getProxyUrl(youtubeId)
                            }
                        )
                    } else {
                        soundCloudRepository.getMoreSoundCloudSearchResults(
                            query = query,
                            filter = filter,
                            previousPage = previousPage,
                            proxyUrlProvider = { encodedUrl ->
                                soundCloudStreamProxy.getProxyUrl(encodedUrl)
                            }
                        )
                    }
                }

                // A NEWER search superseded this load while it was in flight —
                // discard the stale page entirely.
                if (continuation !== searchContinuation) {
                    _isLoadingMoreSearchResults.value = false
                    return@launch
                }

                searchContinuation = nextPage.continuation
                _hasMoreSearchResults.value =
                    nextPage.hasMore && nextPage.continuation != null

                if (nextPage.results.isNotEmpty()) {
                    val existingIds = _searchResults.value.map { it.stableSearchKey() }.toHashSet()
                    val newItems = nextPage.results.filter { item ->
                        existingIds.add(item.stableSearchKey())
                    }
                    if (newItems.isNotEmpty()) {
                        _searchResults.value =
                            (_searchResults.value + newItems).toImmutableList()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SearchStateHolder", "Error loading more search results for query: $query", e)
                // Keep hasMore=true so the user can RETRY the failed page
                // (the "Load more" row surfaces the error inline).
            } finally {
                _isLoadingMoreSearchResults.value = false
            }
        }
    }

    /** The query of the last completed online search (for its continuation pages). */
    private var lastCommittedQuery: String = ""

    /**
     * IMPROVE(search-load-more): stable dedupe key of a search result — the
     * provider occasionally repeats an item across continuation page
     * boundaries, and the appended page must not duplicate visible rows.
     *
     * FIX(online-search-chip-crash): delegates to
     * [SearchResultKeys.baseKeyOf] so the load-more dedupe and the results
     * list's LazyColumn keys can never drift apart again.
     */
    private fun SearchResultItem.stableSearchKey(): String =
        SearchResultKeys.baseKeyOf(this)

    fun toggleSearchMode(isOnline: Boolean) {
        _isOnlineSearch.value = isOnline
        // IMPROVE(online-search-autocomplete): leaving Online mode hides the
        // suggestion panel and invalidates whatever autocomplete request is
        // still in flight — a late response must never repopulate the panel
        // while Local search is active, and Local search never triggers
        // online autocomplete itself.
        if (!isOnline) {
            clearSuggestions()
        }
    }

    fun loadSearchHistory(limit: Int = 0) {
        // IMPROVE(history-limit-settings): no explicit limit → use the user's
        // configured cap (Settings -> Appearance). An explicit positive limit
        // (e.g. from a caller requesting a preview window) still wins.
        val effectiveLimit = if (limit > 0) limit else searchHistoryLimit
        scope?.launch {
            try {
                val history = withContext(Dispatchers.IO) {
                    musicRepository.getRecentSearchHistory(effectiveLimit)
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
                        musicRepository.addSearchHistoryItem(
                            query = query,
                            maxEntries = searchHistoryLimit
                        )
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

    // ---------------------------------------------------------------------
    // IMPROVE(online-search-autocomplete): request / observe pipeline
    // ---------------------------------------------------------------------

    /**
     * Requests autocomplete [suggestions][searchSuggestions] for [query]
     * from the CURRENTLY SELECTED online provider.
     *
     * Called on every query edit while the user types in Online mode. The
     * request is debounced (see [SUGGESTION_DEBOUNCE_MS]) so rapid keystrokes
     * coalesce into a single provider request, and a newer request cancels /
     * supersedes any in-flight one. Queries below [MIN_SUGGESTION_QUERY_LENGTH]
     * — and blank ones, and any request while Local mode is active — never
     * reach the network: they just clear the current suggestions and
     * invalidate whatever is in flight.
     */
    fun requestSuggestions(query: String) {
        val normalizedQuery = query.trim()

        if (!_isOnlineSearch.value || normalizedQuery.length < MIN_SUGGESTION_QUERY_LENGTH) {
            clearSuggestions()
            return
        }

        suggestionRequests.tryEmit(
            SuggestionRequest(
                query = normalizedQuery,
                requestId = latestSuggestionRequestId.incrementAndGet(),
                provider = _currentProvider.value
            )
        )
    }

    /**
     * Hides the suggestion panel and invalidates any in-flight autocomplete
     * request: the request-id guard bumps past every pending response, so a
     * result arriving later can never repopulate the panel. Called when a
     * query is committed (suggestion tap / keyboard Search), when the query
     * is cleared, when Local mode is entered and when the provider changes.
     */
    fun clearSuggestions() {
        latestSuggestionRequestId.incrementAndGet()
        _searchSuggestions.value = persistentListOf()
        _isLoadingSuggestions.value = false
    }

    /**
     * The debounced autocomplete collector — the suggestion-pipeline twin of
     * [observeSearchRequests]. `debounce` coalesces rapid typing into one
     * provider request; `collectLatest` cancels a superseded request's
     * handling (and its repository fetch, when the underlying call is
     * cancellable). A response may only publish while it is still the newest
     * request AND its provider/mode are still current — every other outcome
     * is silently discarded, never published over newer suggestions.
     */
    private fun observeSuggestionRequests() {
        suggestionJob?.cancel()
        suggestionJob = scope?.launch {
            suggestionRequests
                .debounce(SUGGESTION_DEBOUNCE_MS)
                .collectLatest { request ->
                    // The user left Online mode while this request was
                    // debouncing — Local search must never surface online
                    // suggestions.
                    if (!_isOnlineSearch.value) {
                        _isLoadingSuggestions.value = false
                        return@collectLatest
                    }
                    // The provider changed while this request was debouncing —
                    // its suggestions would come from the WRONG provider.
                    if (request.provider != _currentProvider.value) {
                        _isLoadingSuggestions.value = false
                        return@collectLatest
                    }

                    _isLoadingSuggestions.value = true
                    try {
                        val suggestions = withContext(suggestionsIoDispatcher) {
                            when (request.provider) {
                                OnlineProvider.YOUTUBE ->
                                    youTubeRepository.getSuggestions(request.query)
                                OnlineProvider.SOUNDCLOUD ->
                                    soundCloudRepository.getSuggestions(request.query)
                            }
                        }

                        // Stale-response guard: only the NEWEST request may
                        // publish. A slower, older fetch — including one whose
                        // cancellation could not stop the underlying network
                        // call — must never overwrite the newer query's
                        // suggestions.
                        if (request.requestId != latestSuggestionRequestId.get()) {
                            return@collectLatest
                        }
                        _searchSuggestions.value = suggestions.toImmutableList()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Autocomplete is best-effort: a provider / extractor /
                        // network failure must never crash the app or block the
                        // normal search. Drop the suggestions for this query
                        // (never leave stale ones looking current) and let the
                        // user keep typing / submit normally.
                        if (request.requestId == latestSuggestionRequestId.get()) {
                            Log.e(
                                "SearchStateHolder",
                                "Error fetching search suggestions for query: ${request.query}",
                                e
                            )
                            _searchSuggestions.value = persistentListOf()
                        }
                    } finally {
                        // Only the request that still OWNS the panel may lower
                        // the loading flag — a superseded request must not
                        // cancel a newer one's indicator.
                        if (request.requestId == latestSuggestionRequestId.get()) {
                            _isLoadingSuggestions.value = false
                        }
                    }
                }
        }
    }

    fun onCleared() {
        searchJob?.cancel()
        // IMPROVE(online-search-autocomplete): stop the debounced
        // autocomplete pipeline with everything else when the holder dies.
        suggestionJob?.cancel()
        scope = null
    }
}
