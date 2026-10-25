package com.saine.pixeltune.presentation.viewmodel

import android.util.Log
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
import android.util.LruCache
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

        /**
         * IMPROVE(search-suggestions): autocomplete suggestions debounce —
         * deliberately FASTER than the search debounce above so the dropdown
         * reacts to the user's keystrokes noticeably before the (heavier)
         * search results page lands, exactly like Spotify / YT Music's
         * suggestion sheets do.
         */
        const val SUGGESTION_DEBOUNCE_MS = 200L

        /** IMPROVE(search-suggestions): max suggestions kept per query. */
        const val MAX_SUGGESTIONS = 10

        /**
         * IMPROVE(search-suggestions): entries in the per-session suggestion
         * cache (provider-tagged query -> suggestions). Small on purpose —
         * covers the "type, delete a char, retype" pattern instantly.
         */
        const val SUGGESTION_CACHE_SIZE = 24
    }

    private data class SearchRequest(
        val query: String,
        val requestId: Long,
        val isOnline: Boolean
    )

    /**
     * IMPROVE(search-suggestions): one debounced autocomplete request —
     * [requestId] races newer requests exactly like [SearchRequest] does for
     * the search flow, and [provider] remembers which online provider the
     * suggestions belong to.
     */
    private data class SuggestionRequest(
        val query: String,
        val requestId: Long,
        val provider: OnlineProvider
    )

    enum class OnlineProvider {
        YOUTUBE, SOUNDCLOUD
    }

    private val _currentProvider = MutableStateFlow(OnlineProvider.YOUTUBE)
    val currentProvider = _currentProvider.asStateFlow()

    fun setOnlineProvider(provider: OnlineProvider) {
        _currentProvider.value = provider
        // IMPROVE(search-suggestions): the provider changed — whatever
        // suggestions are currently held belong to the OLD provider's index,
        // so drop them (and the request that would publish them) right now.
        // The next keystroke re-requests for the NEW provider; the per-session
        // cache stays valid because its keys are provider-tagged.
        resetSuggestionState()
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

    // =================================================================================
    // IMPROVE(search-suggestions): LIVE autocomplete suggestions for the Online
    // search mode.
    //
    // Mirrors the search-request pipeline above (debounced SharedFlow +
    // racing request ids + collectLatest), but with three deliberate
    // differences so it feels like Spotify / YT Music's suggestion sheets:
    //
    //   1. FASTER debounce (200ms vs the search's 300ms) — suggestions are one
    //      tiny request and should land before the results page does.
    //   2. A small per-session cache keyed by provider + query, so the common
    //      "type a char, delete it, retype" dance resolves INSTANTLY from
    //      memory instead of hammering the suggestion endpoint.
    //   3. Failures are silent — suggestions are a progressive enhancement,
    //      never a user-facing error (the dropdown simply collapses).
    //
    // Only the ONLINE mode ever requests suggestions (Local search searches
    // the device's MediaStore and has no remote suggestion source).
    // =================================================================================

    /** The suggestions for the CURRENT (trimmed) query — empty when none. */
    private val _searchSuggestions = MutableStateFlow<ImmutableList<String>>(persistentListOf())
    val searchSuggestions = _searchSuggestions.asStateFlow()

    /**
     * True while the suggestions for the latest keystroke are being fetched
     * — drives the compact expressive loading indicator in the search bar's
     * trailing slot and the dropdown's skeleton state.
     */
    private val _isLoadingSuggestions = MutableStateFlow(false)
    val isLoadingSuggestions = _isLoadingSuggestions.asStateFlow()

    private val suggestionRequests = MutableSharedFlow<SuggestionRequest>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val latestSuggestionRequestId = AtomicLong(0L)

    private var suggestionJob: Job? = null

    /**
     * The (provider, query) the last emitted suggestion request belongs to —
     * dedupes re-requests triggered by filter changes / mode toggles that
     * did NOT change what the user typed.
     */
    private var lastSuggestionRequestKey: String? = null

    /**
     * Per-session suggestion cache (provider-tagged query -> cleaned
     * suggestions). [LruCache] is internally synchronized, so the collector
     * coroutine and the request emitters can share it safely.
     */
    private val suggestionCache = LruCache<String, ImmutableList<String>>(SUGGESTION_CACHE_SIZE)

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
    }

    /**
     * IMPROVE(search-suggestions): the debounced autocomplete pipeline.
     *
     * collectLatest cancels the in-flight suggestion request the moment a
     * newer keystroke lands (the request-id guard then keeps the older
     * response from publishing), the per-session cache short-circuits the
     * network for recently seen (provider, query) pairs, and every failure
     * collapses to "no suggestions" without ever surfacing an error.
     */
    private fun observeSuggestionRequests() {
        suggestionJob?.cancel()
        suggestionJob = scope?.launch {
            suggestionRequests
                .debounce(SUGGESTION_DEBOUNCE_MS)
                .collectLatest { request ->
                    // Cache hit — publish instantly, no loading flash.
                    val cached = suggestionCache.get(cacheKeyFor(request.provider, request.query))
                    if (cached != null) {
                        _isLoadingSuggestions.value = false
                        if (_searchSuggestions.value != cached) {
                            _searchSuggestions.value = cached
                        }
                        return@collectLatest
                    }

                    _isLoadingSuggestions.value = true
                    try {
                        val fetched = withContext(Dispatchers.IO) {
                            if (request.provider == OnlineProvider.YOUTUBE) {
                                youTubeRepository.getSearchSuggestions(request.query)
                            } else {
                                soundCloudRepository.getSearchSuggestions(request.query)
                            }
                        }

                        val cleaned = fetched
                            .asSequence()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() && !it.equals(request.query, ignoreCase = true) }
                            .distinct()
                            .take(MAX_SUGGESTIONS)
                            .toImmutableList()

                        suggestionCache.put(cacheKeyFor(request.provider, request.query), cleaned)

                        if (request.requestId == latestSuggestionRequestId.get()) {
                            _isLoadingSuggestions.value = false
                            if (_searchSuggestions.value != cleaned) {
                                _searchSuggestions.value = cleaned
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // IMPROVE(search-suggestions): suggestion failures are
                        // silent by design — a bot-check HTML page, a timeout,
                        // or airplane mode simply collapse the dropdown; the
                        // actual search flow is never disturbed.
                        if (request.requestId == latestSuggestionRequestId.get()) {
                            Log.e(
                                "SearchStateHolder",
                                "Error fetching search suggestions for query: ${request.query}",
                                e
                            )
                            _isLoadingSuggestions.value = false
                            _searchSuggestions.value = persistentListOf()
                        }
                    }
                }
        }
    }

    /**
     * IMPROVE(search-suggestions): emits an autocomplete request for [query]
     * when (and only when) it makes sense — Online mode, a non-blank query,
     * and something that actually changed since the last request.
     */
    private fun maybeRequestSuggestions(query: String) {
        if (!_isOnlineSearch.value) {
            // Local mode has no remote suggestion source — drop any stale
            // Online suggestions immediately.
            resetSuggestionState()
            return
        }

        if (query.isBlank()) {
            // A blank query has nothing to autocomplete — drop whatever is
            // held so a stale list can never re-appear on the next keystroke.
            resetSuggestionState()
            return
        }

        val provider = _currentProvider.value
        val requestKey = cacheKeyFor(provider, query)
        if (requestKey == lastSuggestionRequestKey) return
        lastSuggestionRequestKey = requestKey

        val requestId = latestSuggestionRequestId.incrementAndGet()
        suggestionRequests.tryEmit(SuggestionRequest(query, requestId, provider))
    }

    /**
     * IMPROVE(search-suggestions): requests autocomplete suggestions for
     * [query] WITHOUT re-running the search itself.
     *
     * Entering the compose phase by tapping the search field (a focus, not a
     * keystroke) does not re-trigger the search pipeline, so a programmatic
     * nudge is needed for the cases where no suggestions are held for the
     * current query — the online provider was switched (which drops the old
     * provider's list), a fetch silently failed, or the process was restored.
     * The per-session cache and the last-request dedupe make this an instant
     * no-op whenever fresh suggestions are already in hand.
     */
    fun requestSearchSuggestions(query: String) {
        maybeRequestSuggestions(query.trim())
    }

    /**
     * IMPROVE(search-suggestions): drops the held suggestions + loading flag
     * and forgets the last request key (so the same query is re-requested the
     * next time it becomes relevant). Used when the query goes blank, when
     * Local mode takes over, and when the online provider switches.
     */
    private fun resetSuggestionState() {
        lastSuggestionRequestKey = null
        _isLoadingSuggestions.value = false
        if (_searchSuggestions.value.isNotEmpty()) {
            _searchSuggestions.value = persistentListOf()
        }
    }

    /**
     * IMPROVE(search-suggestions): provider-tagged cache key — suggestions
     * from YouTube and SoundCloud never collide.
     */
    private fun cacheKeyFor(provider: OnlineProvider, query: String): String =
        "${provider.name}:$query"

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

        // IMPROVE(search-suggestions): every search trigger is also a
        // potential autocomplete trigger — typing in the search bar routes
        // through here (SearchScreen's LaunchedEffect on the query), so the
        // suggestion request rides along with the search request while
        // keeping its own, faster debounce + race handling.
        maybeRequestSuggestions(normalizedQuery)

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
        suggestionJob?.cancel()
        scope = null
    }
}
