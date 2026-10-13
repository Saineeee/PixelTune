package com.saine.pixeltune.presentation.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saine.pixeltune.data.database.EngagementDao
import com.saine.pixeltune.data.model.SearchResultItem
import com.saine.pixeltune.data.model.Song
import com.saine.pixeltune.data.repository.MusicRepository
import com.saine.pixeltune.data.surf.SurfFeedCache
import com.saine.pixeltune.data.surf.SurfFeedKeys
import com.saine.pixeltune.data.surf.SurfSectionsBuilder
import com.saine.pixeltune.data.surf.YoutubeTrendingFeedParser
import com.saine.pixeltune.data.soundcloud.SoundCloudRepository
import com.saine.pixeltune.data.soundcloud.SoundCloudStreamProxy
import com.saine.pixeltune.data.youtube.YouTubeRepository
import com.saine.pixeltune.data.youtube.YouTubeStreamProxy
import com.saine.pixeltune.presentation.viewmodel.SearchStateHolder.OnlineProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * SURF: the discovery feed's online sections — trending charts from the
 * CURRENT streaming provider, the "Fresh finds" trending playlist cards
 * (YouTube) and "Because you played X" related tracks seeded from the
 * listener's most played library song.
 *
 * Conventions follow [CloudCatalogViewModel]: plain-field [SurfUiState] over
 * a [MutableStateFlow], repository + stream-proxy calls identical to the
 * online search, and results cached with the shared [SurfFeedCache]
 * (30 min TTL, stale-while-revalidate) so re-opening the tab — or opening a
 * "see all" page for the same feed — is instant. Nothing is fetched until
 * the ViewModel is created, i.e. until the Surf tab is opened for the first
 * time, so app startup is untouched.
 *
 * The on-device personalisation (jump back in, top artists, the related
 * seed) is exposed as plain data; the stateless screen composable derives
 * the offline sections from it with [SurfSectionsBuilder], exactly like
 * HomeScreen derives Recently Played from the playback history flows.
 */
@Immutable
data class SurfUiState(
    val trendingSongs: ImmutableList<Song> = persistentListOf(),
    // Loading by default: the very first composition of the tab shows the
    // section's loading state instead of a one-frame empty carousel.
    val trendingIsLoading: Boolean = true,
    val trendingFailed: Boolean = false,
    val trendingProvider: OnlineProvider = OnlineProvider.YOUTUBE,
    val explorePlaylists: ImmutableList<YoutubeTrendingFeedParser.TrendingPlaylist> = persistentListOf(),
    // Fresh finds is YouTube-only (SoundCloud's public charts API only
    // serves the one "New & hot" list), so the section resolves to loaded
    // and empty — NOT failed — when the provider has nothing more to show.
    val exploreIsLoading: Boolean = true,
    val exploreFailed: Boolean = false,
    val relatedSeed: Song? = null,
    val relatedSongs: ImmutableList<Song> = persistentListOf(),
    val relatedIsLoading: Boolean = false,
    val relatedFailed: Boolean = false
)

@HiltViewModel
class SurfViewModel @Inject constructor(
    private val youTubeRepository: YouTubeRepository,
    private val soundCloudRepository: SoundCloudRepository,
    private val youTubeStreamProxy: YouTubeStreamProxy,
    private val soundCloudStreamProxy: SoundCloudStreamProxy,
    private val musicRepository: MusicRepository,
    private val engagementDao: EngagementDao,
    private val feedCache: SurfFeedCache<List<Song>>,
    searchStateHolder: SearchStateHolder,
    connectivityStateHolder: ConnectivityStateHolder
) : ViewModel() {

    /** Live connectivity — lets the trending section say "offline" instead of a generic error. */
    val isOnline: StateFlow<Boolean> = connectivityStateHolder.isOnline

    private val _uiState = MutableStateFlow(SurfUiState())
    val uiState: StateFlow<SurfUiState> = _uiState.asStateFlow()

    /**
     * The Fresh finds cards are tab-private state (only this ViewModel reads
     * them), so they keep a private cache instance — the injected
     * [SurfFeedCache] singleton is reserved for the feeds the "see all"
     * lists re-read (trending chart, playlist track pages, related picks),
     * whose value type is Song lists.
     */
    private val exploreFeedCache =
        SurfFeedCache<List<YoutubeTrendingFeedParser.TrendingPlaylist>>()

    /**
     * Live per-song engagement stats from Room — the on-device ranking signal
     * for the top-artists row and the related-tracks seed. Room's reactive
     * flow keeps the personalisation current as the listener plays.
     */
    val engagementStats: StateFlow<Map<String, SurfSectionsBuilder.SongEngagementStats>> =
        engagementDao.getAllEngagementsFlow()
            .map { entities ->
                entities.associate { entity ->
                    entity.songId to SurfSectionsBuilder.SongEngagementStats(
                        playCount = entity.playCount,
                        totalPlayDurationMs = entity.totalPlayDurationMs,
                        lastPlayedTimestamp = entity.lastPlayedTimestamp
                    )
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyMap()
            )

    private var trendingJob: Job? = null
    private var exploreJob: Job? = null
    private var relatedJob: Job? = null
    private var relatedSeedId: String? = null

    init {
        // Provider switch swaps the trending chart, the fresh finds and the
        // related picks; the feed cache serves the other provider's pages
        // instantly and refreshes them in the background when stale.
        viewModelScope.launch {
            searchStateHolder.currentProvider.collect { provider ->
                loadTrending(provider)
                loadExplore(provider)
                loadRelated(provider)
            }
        }
        // The related seed is derived on-device from the library + engagement
        // stats; it only changes when the listener's most played song does.
        viewModelScope.launch {
            combine(musicRepository.getAudioFiles(), engagementStats) { songs, stats ->
                SurfSectionsBuilder.pickRelatedSeed(stats, songs)
            }
                .distinctUntilChanged()
                .collect { seed ->
                    if (seed?.id != relatedSeedId) {
                        relatedSeedId = seed?.id
                        _uiState.update {
                            it.copy(relatedSeed = seed, relatedSongs = persistentListOf())
                        }
                        loadRelated(_uiState.value.trendingProvider)
                    }
                }
        }
    }

    /**
     * Drops a deleted song from the visible feed sections (the song info
     * sheet's "remove from list" action after an on-device delete) — the
     * counterpart of CloudCatalogViewModel.removeSong.
     */
    fun removeSong(songId: String) {
        _uiState.update { current ->
            val trendingChanged = current.trendingSongs.any { it.id == songId }
            val relatedChanged = current.relatedSongs.any { it.id == songId }
            if (!trendingChanged && !relatedChanged) {
                current
            } else {
                current.copy(
                    trendingSongs = if (trendingChanged) {
                        current.trendingSongs.filterNot { it.id == songId }.toImmutableList()
                    } else {
                        current.trendingSongs
                    },
                    relatedSongs = if (relatedChanged) {
                        current.relatedSongs.filterNot { it.id == songId }.toImmutableList()
                    } else {
                        current.relatedSongs
                    }
                )
            }
        }
    }

    /** Manual retry for the trending section's error state. */
    fun refreshTrending() {
        feedCache.invalidate(SurfFeedKeys.trending(_uiState.value.trendingProvider.name))
        loadTrending(_uiState.value.trendingProvider)
    }

    /** Manual retry for the fresh-finds section's error state. */
    fun refreshExplore() {
        exploreFeedCache.invalidate(SurfFeedKeys.explore(_uiState.value.trendingProvider.name))
        loadExplore(_uiState.value.trendingProvider)
    }

    /**
     * Pull-to-refresh — re-fetches every online section of the feed at once
     * (trending chart, fresh finds and the related picks when a seed exists),
     * the Library tabs' single onRefresh action applied to the discovery feed.
     */
    fun refreshAll() {
        val provider = _uiState.value.trendingProvider
        feedCache.invalidate(SurfFeedKeys.trending(provider.name))
        exploreFeedCache.invalidate(SurfFeedKeys.explore(provider.name))
        _uiState.value.relatedSeed?.id?.let { seedId ->
            feedCache.invalidate(SurfFeedKeys.related(provider.name, seedId))
        }
        loadTrending(provider)
        loadExplore(provider)
        loadRelated(provider)
    }

    /** Manual retry for the related section's error state. */
    fun refreshRelated() {
        val seedId = _uiState.value.relatedSeed?.id
        if (seedId != null) {
            feedCache.invalidate(SurfFeedKeys.related(_uiState.value.trendingProvider.name, seedId))
        }
        loadRelated(_uiState.value.trendingProvider)
    }

    private fun loadTrending(provider: OnlineProvider) {
        trendingJob?.cancel()
        trendingJob = viewModelScope.launch {
            val cacheKey = SurfFeedKeys.trending(provider.name)
            feedCache.peek(cacheKey)?.let { cached ->
                _uiState.update {
                    it.copy(
                        trendingSongs = cached.toImmutableList(),
                        trendingIsLoading = false,
                        trendingFailed = false,
                        trendingProvider = provider
                    )
                }
            } ?: _uiState.update {
                it.copy(trendingIsLoading = true, trendingFailed = false, trendingProvider = provider)
            }
            try {
                val fresh = feedCache.getOrFetch(cacheKey) { fetchTrending(provider) }
                _uiState.update {
                    it.copy(
                        trendingSongs = fresh.toImmutableList(),
                        trendingIsLoading = false,
                        trendingFailed = fresh.isEmpty() && it.trendingSongs.isEmpty()
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Error loading Surf trending feed")
                _uiState.update {
                    it.copy(
                        trendingIsLoading = false,
                        // Keep serving stale data (stale-while-revalidate);
                        // only surface the failure when there is nothing to show.
                        trendingFailed = it.trendingSongs.isEmpty()
                    )
                }
            }
        }
    }

    private suspend fun fetchTrending(provider: OnlineProvider): List<Song> {
        val songs = when (provider) {
            OnlineProvider.YOUTUBE ->
                youTubeRepository.getTrendingMusic { youtubeId ->
                    youTubeStreamProxy.getProxyUrl(youtubeId)
                }
            OnlineProvider.SOUNDCLOUD ->
                soundCloudRepository.getTrendingTracks { encodedUrl ->
                    soundCloudStreamProxy.getProxyUrl(encodedUrl)
                }
        }
        // Providers occasionally repeat a chart entry — distinct ids keep
        // LazyColumn keys unique (the search results guard against the same).
        return songs.distinctBy { it.id }
    }

    /**
     * Loads the "Fresh finds" section — the YouTube trending playlist cards.
     * SoundCloud has no equivalent chart (its public API only serves the one
     * "New & hot" list), so the section settles empty-and-loaded there and
     * the screen simply hides it.
     */
    private fun loadExplore(provider: OnlineProvider) {
        exploreJob?.cancel()
        exploreJob = viewModelScope.launch {
            if (provider != OnlineProvider.YOUTUBE) {
                _uiState.update {
                    it.copy(
                        explorePlaylists = persistentListOf(),
                        exploreIsLoading = false,
                        exploreFailed = false
                    )
                }
                return@launch
            }
            val cacheKey = SurfFeedKeys.explore(provider.name)
            exploreFeedCache.peek(cacheKey)?.let { cached ->
                _uiState.update {
                    it.copy(
                        explorePlaylists = cached.toImmutableList(),
                        exploreIsLoading = false,
                        exploreFailed = false
                    )
                }
            } ?: _uiState.update { it.copy(exploreIsLoading = true, exploreFailed = false) }
            try {
                val fresh = exploreFeedCache.getOrFetch(cacheKey) { fetchExplore() }
                _uiState.update {
                    it.copy(
                        explorePlaylists = fresh.toImmutableList(),
                        exploreIsLoading = false,
                        exploreFailed = false
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Error loading Surf fresh finds")
                _uiState.update {
                    it.copy(
                        exploreIsLoading = false,
                        exploreFailed = it.explorePlaylists.isEmpty()
                    )
                }
            }
        }
    }

    private suspend fun fetchExplore(): List<YoutubeTrendingFeedParser.TrendingPlaylist> =
        youTubeRepository.getTrendingPlaylists()
            .distinctBy { it.browseId }

    private fun loadRelated(provider: OnlineProvider) {
        val seed = _uiState.value.relatedSeed ?: return
        relatedJob?.cancel()
        relatedJob = viewModelScope.launch {
            val cacheKey = SurfFeedKeys.related(provider.name, seed.id)
            _uiState.update { it.copy(relatedIsLoading = it.relatedSongs.isEmpty(), relatedFailed = false) }
            try {
                val fresh = feedCache.getOrFetch(cacheKey) { fetchRelated(seed, provider) }
                _uiState.update {
                    it.copy(relatedSongs = fresh.toImmutableList(), relatedIsLoading = false, relatedFailed = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Error loading Surf related tracks")
                _uiState.update { it.copy(relatedIsLoading = false, relatedFailed = it.relatedSongs.isEmpty()) }
            }
        }
    }

    private suspend fun fetchRelated(seed: Song, provider: OnlineProvider): List<Song> {
        val songs = when (provider) {
            OnlineProvider.YOUTUBE ->
                youTubeRepository.getMultipleAutoplayRecommendations(
                    currentSong = seed,
                    currentQueueIds = emptyList(),
                    proxyUrlProvider = { youtubeId -> youTubeStreamProxy.getProxyUrl(youtubeId) },
                    limit = RELATED_LIMIT
                )
            OnlineProvider.SOUNDCLOUD -> {
                val query = "${seed.displayArtist} ${seed.title}".trim()
                if (query.isBlank()) {
                    emptyList()
                } else {
                    soundCloudRepository.searchSoundCloud(query) { encodedUrl ->
                        soundCloudStreamProxy.getProxyUrl(encodedUrl)
                    }.mapNotNull { (it as? SearchResultItem.SongItem)?.song }
                        .filter { it.id != seed.id }
                }
            }
        }
        return songs.distinctBy { it.id }.take(RELATED_LIMIT)
    }

    private companion object {
        const val RELATED_LIMIT = 10
    }
}
