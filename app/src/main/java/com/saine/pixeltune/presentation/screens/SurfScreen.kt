@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.saine.pixeltune.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarDefaults.topAppBarColors
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.carousel.CarouselItemScope
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import com.saine.pixeltune.R
import com.saine.pixeltune.data.model.Genre
import com.saine.pixeltune.data.model.Song
import com.saine.pixeltune.data.surf.SurfExploreCard
import com.saine.pixeltune.data.surf.SurfJumpBackInEntry
import com.saine.pixeltune.data.surf.SurfSectionsBuilder
import com.saine.pixeltune.data.surf.SurfTopArtist
import com.saine.pixeltune.presentation.components.ExpressiveScrollBar
import com.saine.pixeltune.presentation.components.MiniPlayerHeight
import com.saine.pixeltune.presentation.components.NavBarContentHeight
import com.saine.pixeltune.presentation.components.PlaylistBottomSheet
import com.saine.pixeltune.presentation.components.SmartImage
import com.saine.pixeltune.presentation.components.SongInfoBottomSheet
import com.saine.pixeltune.presentation.components.StreamingProviderSheet
import com.saine.pixeltune.presentation.components.rememberCanScrollMore
import com.saine.pixeltune.presentation.components.subcomps.EnhancedSongListItem
import com.saine.pixeltune.presentation.navigation.Screen
import com.saine.pixeltune.presentation.navigation.navigateSafely
import com.saine.pixeltune.presentation.screens.search.components.GenreTypography
import com.saine.pixeltune.presentation.utils.GenreIconProvider
import com.saine.pixeltune.presentation.viewmodel.PlayerViewModel
import com.saine.pixeltune.presentation.viewmodel.PlaylistViewModel
import com.saine.pixeltune.presentation.viewmodel.SearchStateHolder.OnlineProvider
import com.saine.pixeltune.presentation.viewmodel.SurfUiState
import com.saine.pixeltune.presentation.viewmodel.SurfViewModel
import com.saine.pixeltune.ui.theme.GoogleSansRounded
import com.saine.pixeltune.ui.theme.GenreThemeUtils
import com.saine.pixeltune.ui.theme.LocalPixelTuneDarkTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

/**
 * SURF: the discovery tab — helps the listener find new songs, artists and
 * genres, and re-find things they'd enjoy from their own listening.
 *
 * The chrome is the Library screen's grammar, applied to a discovery feed
 * instead of a local collection: the tinted gradient + big GoogleSansRounded
 * "Surf" title over a primaryContainer TopAppBar, and the sectioned feed
 * scrolling inside the Library's rounded content sheet (34dp smooth top
 * corners over the surface colour) with its own ExpressiveScrollBar and
 * pull-to-refresh (which re-fetches every online section at once, the
 * Library tabs' single onRefresh applied to the feed). The feed's list
 * reserves the Library tabs' scroll-bar clearance — the sheet insets its
 * end padding only while the feed can scroll, so the expressive thumb
 * never sits on top of the section content. The carousels ("Jump back in",
 * the provider chart and "Fresh finds") are Material 3 Expressive
 * multi-browse carousels: swiping back and forth morphs the cards through
 * the carousel's size tiers and snaps them to its keylines. The sections
 * themselves keep the app's shelf grammar: personalised sections first and
 * working offline (derived on-device from playback history + engagement
 * stats, like Home's Recently Played), then the online sections following
 * the current streaming provider — the trending chart, the "Fresh finds"
 * trending playlist cards (YouTube) — degrading to compact retry rows when
 * offline, and the genres & moods grid closing the tab like the search
 * screen's category browse, in the two-column card grid the Library's tabs
 * use.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SurfScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    surfViewModel: SurfViewModel = hiltViewModel(),
    playlistViewModel: PlaylistViewModel = hiltViewModel()
) {
    val allSongs by playerViewModel.allSongsFlow.collectAsStateWithLifecycle()
    val playbackHistory by playerViewModel.playbackHistory.collectAsStateWithLifecycle()
    val localGenres by playerViewModel.genres.collectAsStateWithLifecycle()
    val currentSongId by remember(playerViewModel.stablePlayerState) {
        playerViewModel.stablePlayerState.map { it.currentSong?.id }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = null)
    val isPlaying by remember(playerViewModel.stablePlayerState) {
        playerViewModel.stablePlayerState.map { it.isPlaying }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)
    val surfUiState by surfViewModel.uiState.collectAsStateWithLifecycle()
    val engagementStats by surfViewModel.engagementStats.collectAsStateWithLifecycle()
    val isOnline by surfViewModel.isOnline.collectAsStateWithLifecycle()

    // Queue names are resolved once per composition — stringResource() can't
    // be called from the non-composable click lambdas below. SoundCloud's own
    // chart is named "New & hot", so the section (and the queue it starts)
    // carries the provider's own name for the chart.
    val isSoundCloudProvider = surfUiState.trendingProvider == OnlineProvider.SOUNDCLOUD
    val trendingSectionTitle = stringResource(
        if (isSoundCloudProvider) R.string.surf_trending_title_soundcloud
        else R.string.surf_trending_title
    )
    val trendingQueueName = trendingSectionTitle
    val jumpBackQueueName = stringResource(R.string.surf_jump_back_in_title)
    val relatedQueueName = stringResource(
        R.string.surf_because_you_played_title,
        surfUiState.relatedSeed?.title.orEmpty()
    )

    // On-device personalisation, derived exactly like HomeScreen derives its
    // sections: remember over the live flows + the pure SurfSectionsBuilder.
    // Cloud plays participate — a listener who only streams still gets their
    // Jump back in songs, cloud Top artists and the related-tracks seed.
    val jumpBackIn = remember(playbackHistory, allSongs) {
        SurfSectionsBuilder.buildJumpBackIn(
            playbackHistory = playbackHistory,
            allSongs = allSongs
        )
    }
    val topArtists = remember(engagementStats, allSongs, playbackHistory) {
        SurfSectionsBuilder.buildTopArtists(
            engagementStats = engagementStats,
            allSongs = allSongs,
            playbackHistory = playbackHistory
        )
    }
    // Browse = the curated genre & mood seeds first (the discovery grid), then
    // the library's own genres that the curated set doesn't cover — a local
    // genre keeps its themed colours when it collides with a curated card.
    val browseGenres = remember(localGenres) { buildBrowseGenres(localGenres) }
    val localGenreIds = remember(localGenres) {
        localGenres.mapTo(HashSet(localGenres.size)) { it.id.lowercase() }
    }

    var showStreamingProviderSheet by remember { mutableStateOf(false) }
    var showSongInfoBottomSheet by remember { mutableStateOf(false) }
    var selectedSongForInfo by remember { mutableStateOf<Song?>(null) }
    val currentSong = selectedSongForInfo

    // Pull-to-refresh — the Library tabs' own affordance, refreshing every
    // online section at once. The indicator runs until all three loaders
    // have settled (with the same short floor the tab transitions use so a
    // cache hit doesn't flash the spinner away in a single frame).
    var isRefreshing by remember { mutableStateOf(false) }
    val pullToRefreshState = rememberPullToRefreshState()
    val refreshStartedAt = remember { mutableStateOf(0L) }
    LaunchedEffect(isRefreshing, surfUiState) {
        if (!isRefreshing) return@LaunchedEffect
        val settled = !surfUiState.trendingIsLoading &&
                !surfUiState.exploreIsLoading &&
                !surfUiState.relatedIsLoading
        val elapsed = System.currentTimeMillis() - refreshStartedAt.value
        if (settled && elapsed >= PULL_REFRESH_MIN_DURATION_MS) {
            isRefreshing = false
        } else if (settled) {
            kotlinx.coroutines.delay(PULL_REFRESH_MIN_DURATION_MS - elapsed)
            isRefreshing = false
        }
    }

    // The Library's bottom model — the sheet scrolls under the (hiding)
    // navigation bar with the mini-player gap, self-managed like every
    // Library tab instead of the main scaffold's static inner padding.
    val systemNavBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomBarHeight = NavBarContentHeight + systemNavBarInset
    val miniPlayerGap = if (currentSongId != null) MiniPlayerHeight else 0.dp
    val lazyListState = rememberLazyListState()

    // The Library's tinted backdrop: the theme's container colour washing
    // down through the title zone into the content sheet.
    val isDarkTheme = LocalPixelTuneDarkTheme.current
    val gradientColors = if (isDarkTheme) {
        listOf(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
            Color.Transparent
        )
    } else {
        listOf(
            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f),
            Color.Transparent
        )
    }.toImmutableList()
    val gradientBrush = remember(gradientColors) {
        Brush.verticalGradient(colors = gradientColors)
    }

    // The header band glides away with the feed (enter-always) — the big
    // title never pins over scrolled content. The content sheet's rounded
    // top edge follows the collapse: its 48dp corners relax to 0dp as the
    // sheet rises under the status bar, so the edge never shows a clipped
    // curve against the system bar.
    val topBarScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val sheetTopCornerRadius by remember(topBarScrollBehavior) {
        derivedStateOf {
            lerp(
                SurfSheetTopCornerRadius,
                0.dp,
                topBarScrollBehavior.state.collapsedFraction.coerceIn(0f, 1f)
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .background(brush = gradientBrush)
                .nestedScroll(topBarScrollBehavior.nestedScrollConnection),
            topBar = {
                SurfTopBar(
                    scrollBehavior = topBarScrollBehavior,
                    onCloudStreamClick = { showStreamingProviderSheet = true },
                    onSettingsClick = {
                        navController.navigateSafely(Screen.Settings.route)
                    }
                )
            }
        ) { innerPadding ->
            // The Library's content sheet — the feed sits on the surface
            // colour inside the 48dp smooth top corners, over the tinted zone.
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = innerPadding.calculateTopPadding()),
                shape = AbsoluteSmoothCornerShape(
                    cornerRadiusTL = sheetTopCornerRadius,
                    smoothnessAsPercentTL = 60,
                    cornerRadiusTR = sheetTopCornerRadius,
                    smoothnessAsPercentTR = 60,
                    cornerRadiusBL = 0.dp,
                    smoothnessAsPercentBL = 60,
                    cornerRadiusBR = 0.dp,
                    smoothnessAsPercentBR = 60
                ),
                color = MaterialTheme.colorScheme.surface
            ) {
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = {
                        refreshStartedAt.value = System.currentTimeMillis()
                        isRefreshing = true
                        surfViewModel.refreshAll()
                    },
                    state = pullToRefreshState,
                    modifier = Modifier.fillMaxSize(),
                    indicator = {
                        PullToRefreshDefaults.LoadingIndicator(
                            state = pullToRefreshState,
                            isRefreshing = isRefreshing,
                            modifier = Modifier.align(Alignment.TopCenter)
                        )
                    }
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = lazyListState,
                            modifier = Modifier
                                .fillMaxSize()
                                // The Library tabs' scroll-bar clearance: the
                                // feed insets its end edge only while there is
                                // somewhere to scroll to, keeping the
                                // ExpressiveScrollBar's thumb beside the content
                                // instead of over it (the sections carry their
                                // own 14dp base padding, so the sheet insets by
                                // the track's outer bound alone).
                                .padding(
                                    end = if (rememberCanScrollMore(lazyListState)) {
                                        SurfScrollBarEndInset
                                    } else {
                                        0.dp
                                    }
                                ),
                            contentPadding = PaddingValues(
                                top = 16.dp,
                                bottom = bottomBarHeight + miniPlayerGap + ListExtraBottomGap
                            ),
                            verticalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            if (jumpBackIn.isNotEmpty()) {
                                item(key = "surf_jump_back_in", contentType = "surf_section_jump_back_in") {
                                    SurfSectionHeader(title = stringResource(R.string.surf_jump_back_in_title))
                                }
                                item(key = "surf_jump_back_in_row", contentType = "surf_section_jump_back_in_row") {
                                    // The cloud songs in the row play as one
                                    // context queue — tapping one resumes it
                                    // with the other recent finds following it.
                                    val jumpBackCloudSongs = remember(jumpBackIn) {
                                        jumpBackIn.filterIsInstance<SurfJumpBackInEntry.Song>()
                                            .map { it.song }
                                    }
                                    SurfJumpBackInRow(
                                        entries = jumpBackIn,
                                        currentSongId = currentSongId,
                                        onEntryClick = { entry ->
                                            when (entry) {
                                                is SurfJumpBackInEntry.Album ->
                                                    navController.navigateSafely(
                                                        Screen.AlbumDetail.createRoute(entry.albumId)
                                                    )
                                                is SurfJumpBackInEntry.Artist ->
                                                    navController.navigateSafely(
                                                        Screen.ArtistDetail.createRoute(entry.artistId)
                                                    )
                                                is SurfJumpBackInEntry.Song ->
                                                    playerViewModel.showAndPlaySong(
                                                        song = entry.song,
                                                        contextSongs = jumpBackCloudSongs,
                                                        queueName = jumpBackQueueName
                                                    )
                                            }
                                        },
                                        onEntryLongClick = { entry ->
                                            (entry as? SurfJumpBackInEntry.Song)?.let { songEntry ->
                                                selectedSongForInfo = songEntry.song
                                                showSongInfoBottomSheet = true
                                            }
                                        }
                                    )
                                }
                            }

                            if (topArtists.isNotEmpty()) {
                                item(key = "surf_top_artists", contentType = "surf_section_top_artists") {
                                    SurfSectionHeader(title = stringResource(R.string.surf_top_artists_title))
                                }
                                item(key = "surf_top_artists_row", contentType = "surf_section_top_artists_row") {
                                    SurfTopArtistsRow(
                                        artists = topArtists,
                                        onArtistClick = { artist ->
                                            if (artist.isCloud) {
                                                // A streamed artist the library
                                                // doesn't know — its page is the
                                                // provider's own search for the
                                                // artist's tracks.
                                                navController.navigateSafely(
                                                    Screen.SurfList.createRoute(
                                                        SurfListSource.artist(artist.name)
                                                    )
                                                )
                                            } else {
                                                navController.navigateSafely(
                                                    Screen.ArtistDetail.createRoute(artist.artistId)
                                                )
                                            }
                                        }
                                    )
                                }
                            }

                            item(key = "surf_trending", contentType = "surf_section_trending") {
                                SurfTrendingSection(
                                    title = trendingSectionTitle,
                                    uiState = surfUiState,
                                    isOnline = isOnline,
                                    currentSongId = currentSongId,
                                    onSongClick = { song ->
                                        playerViewModel.playSongs(
                                            songsToPlay = surfUiState.trendingSongs,
                                            startSong = song,
                                            queueName = trendingQueueName
                                        )
                                    },
                                    onSongMoreOptionsClick = { song ->
                                        selectedSongForInfo = song
                                        showSongInfoBottomSheet = true
                                    },
                                    onRetry = { surfViewModel.refreshTrending() },
                                    onOpenAllClick = {
                                        navController.navigateSafely(
                                            Screen.SurfList.createRoute(SurfListSource.TRENDING)
                                        )
                                    }
                                )
                            }

                            // "Fresh finds" — the provider's own discovery cards:
                            // YouTube's trending playlists, SoundCloud's fresh
                            // station cards. Loading / failed / ready-with-content
                            // are the only states that compose; a provider whose
                            // fetch resolved empty simply renders nothing here.
                            if (surfUiState.exploreIsLoading || surfUiState.exploreFailed ||
                                surfUiState.exploreCards.isNotEmpty()
                            ) {
                                item(key = "surf_explore", contentType = "surf_section_explore") {
                                    SurfExploreSection(
                                        cards = surfUiState.exploreCards,
                                        isLoading = surfUiState.exploreIsLoading,
                                        failed = surfUiState.exploreFailed,
                                        isOnline = isOnline,
                                        onRetry = { surfViewModel.refreshExplore() },
                                        onCardClick = { card ->
                                            navController.navigateSafely(
                                                Screen.SurfList.createRoute(
                                                    SurfListSource.playlist(
                                                        browseId = card.browseId,
                                                        title = card.title
                                                    )
                                                )
                                            )
                                        }
                                    )
                                }
                            }

                            if (surfUiState.relatedSeed != null) {
                                item(
                                    key = "surf_because_you_played",
                                    contentType = "surf_section_related"
                                ) {
                                    SurfRelatedSection(
                                        uiState = surfUiState,
                                        currentSongId = currentSongId,
                                        isPlaying = isPlaying,
                                        onShuffle = {
                                            if (surfUiState.relatedSongs.isNotEmpty()) {
                                                playerViewModel.playSongsShuffled(
                                                    songsToPlay = surfUiState.relatedSongs,
                                                    queueName = relatedQueueName
                                                )
                                            }
                                        },
                                        onSongClick = { song ->
                                            playerViewModel.playSongs(
                                                songsToPlay = surfUiState.relatedSongs,
                                                startSong = song,
                                                queueName = relatedQueueName
                                            )
                                        },
                                        onSongMoreOptionsClick = { song ->
                                            selectedSongForInfo = song
                                            showSongInfoBottomSheet = true
                                        },
                                        onRetry = { surfViewModel.refreshRelated() }
                                    )
                                }
                            }

                            // "Browse genres & moods" — the search screen's
                            // genre-card visual in the Library tabs' two-column
                            // card grid, closing the feed.
                            item(key = "surf_browse_genres", contentType = "surf_section_browse_genres") {
                                SurfSectionHeader(title = stringResource(R.string.surf_browse_genres_title))
                            }
                            browseGenres.chunked(BROWSE_GENRE_GRID_COLUMNS).forEachIndexed { rowIndex, rowGenres ->
                                item(
                                    key = "surf_browse_genre_row_$rowIndex",
                                    contentType = "surf_browse_genre_row"
                                ) {
                                    SurfBrowseGenreRow(
                                        genres = rowGenres,
                                        onGenreClick = { genre ->
                                            // Discovery opens the ONLINE genre page
                                            // for the current provider — a genre the
                                            // library also carries only falls back to
                                            // the local GenreDetail page while offline
                                            // (where the online page has nothing to
                                            // show but a retry row).
                                            val encodedGenreId =
                                                java.net.URLEncoder.encode(genre.id, "UTF-8")
                                            if (!isOnline && genre.id.lowercase() in localGenreIds) {
                                                navController.navigateSafely(
                                                    Screen.GenreDetail.createRoute(encodedGenreId)
                                                )
                                            } else {
                                                navController.navigateSafely(
                                                    Screen.SurfList.createRoute(SurfListSource.genre(genre.id))
                                                )
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        // The Library's scroll affordance — content scrolls
                        // under the navigation bar, the expressive thumb
                        // follows the sheet's list.
                        ExpressiveScrollBar(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(
                                    end = 4.dp,
                                    top = 16.dp,
                                    bottom = bottomBarHeight + miniPlayerGap + 16.dp
                                ),
                            listState = lazyListState
                        )
                    }
                }
            }
        }
    }

    if (showStreamingProviderSheet) {
        StreamingProviderSheetHost(
            playerViewModel = playerViewModel,
            onDismissRequest = { showStreamingProviderSheet = false }
        )
    }

    if (showSongInfoBottomSheet && currentSong != null) {
        SongInfoBottomSheetHost(
            song = currentSong,
            playerViewModel = playerViewModel,
            playlistViewModel = playlistViewModel,
            navController = navController,
            onRemoveFromList = { song -> surfViewModel.removeSong(song.id) },
            onDismiss = {
                showSongInfoBottomSheet = false
                selectedSongForInfo = null
            }
        )
    }
}

/** Route argument values for [Screen.SurfList] — see [SurfListViewModel]. */
private object SurfListSource {
    const val TRENDING = "trending"
    fun genre(genreId: String): String = "genre:$genreId"

    /** A trending playlist card's see-all page — "playlist:<id>|<title>". */
    fun playlist(browseId: String, title: String): String = "playlist:$browseId|$title"

    /** A cloud Top artists avatar's page — "artist:<name>". */
    fun artist(artistName: String): String = "artist:$artistName"
}

/**
 * Builds the browse grid: the curated genre & mood seeds first (the
 * discovery experience — Rock, Pop, Jazz…), then the library's own genres
 * the curated set doesn't already cover, so a library full of idiosyncratic
 * tags (YouTube-ripper categories like "Film & Animation") trails the grid
 * instead of leading it. A local genre whose id collides with a curated card
 * replaces it so the card keeps the library's themed colours.
 */
private fun buildBrowseGenres(localGenres: ImmutableList<Genre>): List<Genre> {
    val curated = ArrayList<Genre>(GenreIconProvider.DEFAULT_GENRES.size)
    val curatedIds = HashSet<String>(GenreIconProvider.DEFAULT_GENRES.size)
    GenreIconProvider.DEFAULT_GENRES.forEach { name ->
        val curatedId = deriveGenreId(name)
        curatedIds += curatedId.lowercase()
        // The local object wins on collision — it carries the same id but
        // with the library's resolved theme colours.
        val local = localGenres.firstOrNull { it.id.equals(curatedId, ignoreCase = true) }
        curated += (local ?: Genre(id = curatedId, name = name))
    }
    val extras = localGenres.filter { it.id.lowercase() !in curatedIds }
    return curated + extras
}

private fun deriveGenreId(name: String): String =
    name.lowercase().replace(" ", "_").replace("/", "_")

private const val BROWSE_GENRE_GRID_COLUMNS = 2

/** 600ms — the pull-to-refresh indicator's minimum run so cache hits don't flash. */
private const val PULL_REFRESH_MIN_DURATION_MS = 600L

/**
 * The feed's scroll-bar clearance — the ExpressiveScrollBar's track sits
 * 4dp..12dp in from the sheet's edge; the feed insets its end edge by the
 * track's outer bound while the list can scroll, so scrolling content
 * abuts the thumb instead of sliding under it (the Library tabs' lists
 * reserve the same clearance through their 12dp -> 22dp end padding; the
 * feed's sections carry their own 14dp base padding, so the sheet's inset
 * is the track's outer bound alone).
 */
private val SurfScrollBarEndInset = 12.dp

/**
 * The carousels' shared geometry — the Material 3 multi-browse carousel's
 * tiers for the feed's cover cards. The carousel measures every card at the
 * large tier's width and masks the non-focal cards down to the small tiers'
 * widths (centred slices) as they round through the carousel, so the caption
 * zone keeps its text inside the smallest tier's slice — centred and capped
 * at [SurfCarouselCaptionMaxWidth] — while the artwork fills the card and
 * crops with the mask.
 */
private val SurfCarouselHeight = 204.dp
private val SurfCarouselArtHeight = 158.dp
private val SurfCarouselPreferredItemWidth = 188.dp
private val SurfCarouselMinSmallItemWidth = 120.dp
private val SurfCarouselMaxSmallItemWidth = 152.dp
private val SurfCarouselCaptionMaxWidth = 108.dp

/**
 * The content sheet's rounded top edge — the Library chrome's corner, at the
 * discovery feed's bolder 48dp radius. The corner relaxes to 0dp while the
 * header band collapses so the edge stays clean under the status bar.
 */
private val SurfSheetTopCornerRadius = 48.dp

/**
 * The tab's top bar — the Library screen's own chrome: the big
 * GoogleSansRounded title in the theme's primary colour over the
 * primaryContainer-tinted bar, with the same FilledIconButton actions
 * (the streaming provider sheet and Settings). Enter-always: the band
 * glides away with the feed's scroll and returns on the first scroll up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SurfTopBar(
    scrollBehavior: TopAppBarScrollBehavior,
    onCloudStreamClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    TopAppBar(
        scrollBehavior = scrollBehavior,
        title = {
            Text(
                modifier = Modifier.padding(start = 8.dp),
                text = stringResource(R.string.surf_tab_label),
                fontFamily = GoogleSansRounded,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 40.sp,
                letterSpacing = 1.sp
            )
        },
        actions = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 14.dp)
            ) {
                FilledIconButton(
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    onClick = onCloudStreamClick
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Cloud,
                        contentDescription = stringResource(R.string.surf_provider_sheet)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                FilledIconButton(
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    onClick = onSettingsClick
                ) {
                    Icon(
                        painter = painterResource(R.drawable.rounded_settings_24),
                        contentDescription = stringResource(R.string.surf_settings)
                    )
                }
            }
        },
        colors = topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        )
    )
}

/** Section header — RecentlyPlayedSection's header pattern exactly. */
@Composable
private fun SurfSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            modifier = Modifier.padding(start = 6.dp),
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        action?.invoke()
    }
}

/** The header's "see all" affordance — the same pill button the Home uses. */
@Composable
private fun SurfSeeAllButton(
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    FilledIconButton(
        modifier = Modifier
            .height(40.dp)
            .width(64.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.secondary
        ),
        onClick = onClick,
        enabled = enabled
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
            contentDescription = contentDescription,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * "Jump back in": a Material 3 Expressive multi-browse carousel of cover
 * cards for the albums and artists behind the listener's recent LOCAL plays
 * and the cloud songs themselves. Swiping back and forth morphs the cards
 * through the carousel's size tiers and snaps them to its keylines. Local
 * entries re-open the existing AlbumDetail / ArtistDetail pages; cloud
 * entries replay straight from the history snapshot, with the row's other
 * cloud finds as the queue that follows them.
 */
@Composable
private fun SurfJumpBackInRow(
    entries: List<SurfJumpBackInEntry>,
    currentSongId: String?,
    onEntryClick: (SurfJumpBackInEntry) -> Unit,
    onEntryLongClick: (SurfJumpBackInEntry) -> Unit
) {
    val carouselState = rememberCarouselState(itemCount = { entries.size })
    HorizontalMultiBrowseCarousel(
        state = carouselState,
        preferredItemWidth = SurfCarouselPreferredItemWidth,
        modifier = Modifier.fillMaxWidth(),
        itemSpacing = 12.dp,
        minSmallItemWidth = SurfCarouselMinSmallItemWidth,
        maxSmallItemWidth = SurfCarouselMaxSmallItemWidth,
        contentPadding = PaddingValues(start = 14.dp, end = 24.dp)
    ) { itemIndex ->
        val entry = entries[itemIndex]
        key(surfJumpBackInEntryKey(entry)) {
            val subtitle = when (entry) {
                is SurfJumpBackInEntry.Album -> entry.artistName
                is SurfJumpBackInEntry.Artist ->
                    pluralStringResource(
                        R.plurals.surf_artist_song_count,
                        entry.songCount,
                        entry.songCount
                    )
                is SurfJumpBackInEntry.Song -> entry.song.displayArtist
            }
            SurfMediaCard(
                artworkUriString = entry.artworkUriString,
                title = entry.title,
                subtitle = subtitle,
                contentDescription = when (entry) {
                    is SurfJumpBackInEntry.Album ->
                        stringResource(R.string.surf_open_album, entry.title)
                    is SurfJumpBackInEntry.Artist ->
                        stringResource(R.string.surf_open_artist, entry.title)
                    is SurfJumpBackInEntry.Song ->
                        stringResource(R.string.surf_play_song, entry.title)
                },
                onClick = { onEntryClick(entry) },
                onLongClick = { onEntryLongClick(entry) },
                isCurrent = entry is SurfJumpBackInEntry.Song &&
                    currentSongId == entry.song.id
            )
        }
    }
}

/** Stable identity of a carousel entry — the key the section's items use. */
private fun surfJumpBackInEntryKey(entry: SurfJumpBackInEntry): String = when (entry) {
    is SurfJumpBackInEntry.Album -> "jump_album_${entry.albumId}"
    is SurfJumpBackInEntry.Artist -> "jump_artist_${entry.artistId}"
    is SurfJumpBackInEntry.Song -> "jump_song_${entry.song.id}"
}

/**
 * "Top artists": circular avatars from the on-device engagement rollup —
 * local artists open their ArtistDetail page, cloud artists the provider's
 * own search page for their tracks.
 */
@Composable
private fun SurfTopArtistsRow(
    artists: List<SurfTopArtist>,
    onArtistClick: (SurfTopArtist) -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 14.dp, end = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(
            items = artists,
            key = { artist ->
                if (artist.isCloud) {
                    "top_artist_cloud_${artist.name.lowercase()}"
                } else {
                    "top_artist_${artist.artistId}"
                }
            },
            contentType = { "top_artist" }
        ) { artist ->
            Column(
                modifier = Modifier
                    .width(88.dp)
                    .clickable(onClick = { onArtistClick(artist) }),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SmartImage(
                    model = artist.representativeArtUriString,
                    contentDescription = stringResource(R.string.surf_open_artist, artist.name),
                    modifier = Modifier
                        .size(88.dp)
                        .clip(CircleShape),
                    shape = CircleShape
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = artist.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * The provider's chart — "Trending now" on YouTube, "New & hot" on
 * SoundCloud — as a Material 3 Expressive multi-browse carousel: swiping
 * back and forth morphs the cover cards through the carousel's size tiers
 * and snaps them to its keylines. The header's see-all pill opens the full
 * paged list; loading shows the app's expressive LoadingIndicator; a
 * failure with nothing stale to show degrades to the compact retry row
 * (offline copy when the connection is down).
 */
@Composable
private fun SurfTrendingSection(
    title: String,
    uiState: SurfUiState,
    isOnline: Boolean,
    currentSongId: String?,
    onSongClick: (Song) -> Unit,
    onSongMoreOptionsClick: (Song) -> Unit,
    onRetry: () -> Unit,
    onOpenAllClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SurfSectionHeader(
            title = title,
            action = {
                SurfSeeAllButton(
                    contentDescription = stringResource(R.string.surf_trending_see_all),
                    onClick = onOpenAllClick,
                    enabled = uiState.trendingSongs.isNotEmpty()
                )
            }
        )
        when {
            uiState.trendingIsLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(SurfCarouselHeight),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingIndicator(
                        modifier = Modifier.size(36.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            uiState.trendingFailed -> {
                SurfFeedErrorRow(
                    message = if (isOnline) {
                        stringResource(R.string.surf_trending_error)
                    } else {
                        stringResource(R.string.surf_offline_message)
                    },
                    onRetry = onRetry
                )
            }
            else -> {
                if (uiState.trendingSongs.isNotEmpty()) {
                    val carouselState = rememberCarouselState(itemCount = {
                        uiState.trendingSongs.size
                    })
                    HorizontalMultiBrowseCarousel(
                        state = carouselState,
                        preferredItemWidth = SurfCarouselPreferredItemWidth,
                        modifier = Modifier.fillMaxWidth(),
                        itemSpacing = 12.dp,
                        minSmallItemWidth = SurfCarouselMinSmallItemWidth,
                        maxSmallItemWidth = SurfCarouselMaxSmallItemWidth,
                        contentPadding = PaddingValues(start = 14.dp, end = 24.dp)
                    ) { itemIndex ->
                        val song = uiState.trendingSongs[itemIndex]
                        key("trending_${song.id}") {
                            SurfMediaCard(
                                artworkUriString = song.albumArtUriString,
                                title = song.title,
                                subtitle = song.displayArtist,
                                contentDescription = stringResource(
                                    R.string.surf_play_trending, song.title
                                ),
                                onClick = { onSongClick(song) },
                                onLongClick = { onSongMoreOptionsClick(song) },
                                isCurrent = currentSongId == song.id
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * "Fresh finds": the provider's own discovery cards — YouTube's "Today's
 * fresh & popular" trending playlists, SoundCloud's fresh-upload stations —
 * as the feed's second online shelf, in a Material 3 Expressive multi-browse
 * carousel whose cards morph through the size tiers and snap to the
 * keylines while swiping back and forth, each card opening the playlist's
 * own track page. Only loading/failed/ready-with-content ever compose; a
 * provider whose fetch resolved empty renders nothing here.
 */
@Composable
private fun SurfExploreSection(
    cards: ImmutableList<SurfExploreCard>,
    isLoading: Boolean,
    failed: Boolean,
    isOnline: Boolean,
    onRetry: () -> Unit,
    onCardClick: (SurfExploreCard) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SurfSectionHeader(title = stringResource(R.string.surf_explore_title))
        when {
            isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(SurfCarouselHeight),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingIndicator(
                        modifier = Modifier.size(36.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            failed -> {
                SurfFeedErrorRow(
                    message = if (isOnline) {
                        stringResource(R.string.surf_explore_error)
                    } else {
                        stringResource(R.string.surf_offline_message)
                    },
                    onRetry = onRetry
                )
            }
            else -> {
                if (cards.isNotEmpty()) {
                    val carouselState = rememberCarouselState(itemCount = { cards.size })
                    HorizontalMultiBrowseCarousel(
                        state = carouselState,
                        preferredItemWidth = SurfCarouselPreferredItemWidth,
                        modifier = Modifier.fillMaxWidth(),
                        itemSpacing = 12.dp,
                        minSmallItemWidth = SurfCarouselMinSmallItemWidth,
                        maxSmallItemWidth = SurfCarouselMaxSmallItemWidth,
                        contentPadding = PaddingValues(start = 14.dp, end = 24.dp)
                    ) { itemIndex ->
                        val card = cards[itemIndex]
                        key("explore_${card.browseId}") {
                            SurfMediaCard(
                                artworkUriString = card.artworkUrl,
                                title = card.title,
                                subtitle = card.subtitle,
                                contentDescription = stringResource(
                                    R.string.surf_open_playlist, card.title
                                ),
                                onClick = { onCardClick(card) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * "Because you played X": related tracks for the listener's most played
 * song, seeded on-device and fetched through the same provider path the
 * endless radio uses. Rows are the app's standard EnhancedSongListItem; the
 * header's shuffle pill plays the whole set.
 */
@Composable
private fun SurfRelatedSection(
    uiState: SurfUiState,
    currentSongId: String?,
    isPlaying: Boolean,
    onShuffle: () -> Unit,
    onSongClick: (Song) -> Unit,
    onSongMoreOptionsClick: (Song) -> Unit,
    onRetry: () -> Unit
) {
    val seedTitle = uiState.relatedSeed?.title.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SurfSectionHeader(
            title = stringResource(R.string.surf_because_you_played_title, seedTitle),
            action = {
                if (uiState.relatedSongs.isNotEmpty()) {
                    FilledIconButton(
                        modifier = Modifier
                            .height(40.dp)
                            .width(64.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.secondary
                        ),
                        onClick = onShuffle
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Shuffle,
                            contentDescription = stringResource(R.string.surf_shuffle_related),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        )
        when {
            uiState.relatedIsLoading -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(3) { index ->
                        EnhancedSongListItem(
                            song = Song.emptySong(),
                            isPlaying = false,
                            isLoading = true,
                            onMoreOptionsClick = {},
                            onClick = {},
                            modifier = Modifier
                                .padding(horizontal = 2.dp)
                                .testTag("surf_related_skeleton_$index")
                        )
                    }
                }
            }
            uiState.relatedFailed -> {
                SurfFeedErrorRow(
                    message = stringResource(R.string.surf_related_error),
                    onRetry = onRetry
                )
            }
            else -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    uiState.relatedSongs.forEach { song ->
                        EnhancedSongListItem(
                            song = song,
                            isPlaying = currentSongId == song.id && isPlaying,
                            isCurrentSong = currentSongId == song.id,
                            onMoreOptionsClick = onSongMoreOptionsClick,
                            onClick = { onSongClick(song) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * One row of the "Browse genres & moods" grid — the Library tabs'
 * two-column card rhythm. The row is a plain Row (not a nested lazy grid)
 * so it composes as a single LazyColumn item with stable keys.
 */
@Composable
private fun SurfBrowseGenreRow(
    genres: List<Genre>,
    onGenreClick: (Genre) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        genres.forEach { genre ->
            SurfGenreCard(
                genre = genre,
                modifier = Modifier.weight(1f),
                onClick = { onGenreClick(genre) }
            )
        }
        if (genres.size == 1) {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

/**
 * One browse card — GenreCard's visual in the Library's grid proportions:
 * genre-themed container colour, tinted illustration and GenreTypography,
 * filling the column it sits in.
 */
@Composable
private fun SurfGenreCard(
    genre: Genre,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val themeColor = remember(genre, isDark) {
        GenreThemeUtils.getGenreThemeColor(
            genre = genre,
            isDark = isDark,
            fallbackGenreId = genre.id
        )
    }
    val cardShape = remember {
        AbsoluteSmoothCornerShape(
            cornerRadiusTL = 24.dp,
            smoothnessAsPercentTL = 70,
            cornerRadiusTR = 24.dp,
            smoothnessAsPercentTR = 70,
            cornerRadiusBL = 24.dp,
            smoothnessAsPercentBL = 70,
            cornerRadiusBR = 24.dp,
            smoothnessAsPercentBR = 70
        )
    }

    Card(
        modifier = modifier
            .height(118.dp)
            .clip(cardShape)
            .clickable(onClick = onClick),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = themeColor.container),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(cardShape)
                .background(themeColor.container)
        ) {
            SmartImage(
                model = GenreIconProvider.getGenreImageResource(genre.name),
                contentDescription = genre.name,
                modifier = Modifier
                    .size(76.dp)
                    .align(Alignment.BottomEnd)
                    .offset(x = 10.dp, y = 10.dp)
                    .alpha(0.55f),
                colorFilter = ColorFilter.tint(themeColor.onContainer),
                contentScale = ContentScale.Crop
            )
            val style = remember(genre.id, genre.name) {
                GenreTypography.getGenreStyle(genre.id, genre.name)
            }
            Text(
                text = genre.name,
                style = style.copy(lineHeight = 22.sp),
                color = themeColor.onContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth(0.7f)
                    .padding(start = 12.dp, top = 12.dp)
            )
        }
    }
}

/**
 * A carousel card — the shared shape of the "Jump back in", "Trending now"
 * and "Fresh finds" carousels. The card is measured at the carousel's large
 * tier and [CarouselItemScope.maskClip] rounds it along the carousel's mask,
 * so the masked small-tier slices keep rounded edges; the artwork fills the
 * card's width at a fixed height with the title and subtitle centred beneath
 * it, capped to the smallest tier's slice so the caption never gets masked
 * off. Artwork corner and title colour morph while the song plays, mirroring
 * the pill/list-item morphs across the app.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CarouselItemScope.SurfMediaCard(
    artworkUriString: String?,
    title: String,
    subtitle: String,
    contentDescription: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    isCurrent: Boolean = false
) {
    val artworkCorner by animateDpAsState(
        targetValue = if (isCurrent) 28.dp else 16.dp,
        animationSpec = tween(durationMillis = 280),
        label = "surfCardArtworkCorner"
    )
    val titleColor by animateColorAsState(
        targetValue = if (isCurrent) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(durationMillis = 280),
        label = "surfCardTitleColor"
    )
    val artworkShape = remember(artworkCorner) {
        AbsoluteSmoothCornerShape(cornerRadius = artworkCorner, smoothnessAsPercent = 60)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(SurfCarouselHeight)
            .maskClip(artworkShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(SurfCarouselArtHeight)
                .clip(artworkShape)
        ) {
            SmartImage(
                model = artworkUriString,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                shape = artworkShape
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = titleColor,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = SurfCarouselCaptionMaxWidth)
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = SurfCarouselCaptionMaxWidth)
            )
        }
    }
}

/**
 * The online sections' failure row — the online result row card grammar
 * (surfaceContainerLow, 26dp smooth corners) with the empty-state icon
 * treatment and a TextButton retry, matching the search Load-more row.
 */
@Composable
private fun SurfFeedErrorRow(
    message: String,
    onRetry: () -> Unit
) {
    val rowShape = remember {
        AbsoluteSmoothCornerShape(
            cornerRadiusTL = 26.dp,
            smoothnessAsPercentTR = 60,
            cornerRadiusTR = 26.dp,
            smoothnessAsPercentBR = 60,
            cornerRadiusBR = 26.dp,
            smoothnessAsPercentBL = 60,
            cornerRadiusBL = 26.dp,
            smoothnessAsPercentTL = 60
        )
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
        shape = rowShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(
                onClick = onRetry,
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = stringResource(R.string.surf_retry),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/** HomeScreen's StreamingProviderSheetHost, verbatim. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun StreamingProviderSheetHost(
    playerViewModel: PlayerViewModel,
    onDismissRequest: () -> Unit
) {
    val currentOnlineProvider by playerViewModel.currentOnlineProvider.collectAsStateWithLifecycle()
    StreamingProviderSheet(
        onDismissRequest = onDismissRequest,
        activeProvider = currentOnlineProvider,
        onProviderSelected = { provider ->
            playerViewModel.setOnlineProvider(provider)
            playerViewModel.sendToast(
                when (provider) {
                    OnlineProvider.YOUTUBE -> "Switched to YouTube streaming"
                    OnlineProvider.SOUNDCLOUD -> "Switched to SoundCloud streaming"
                }
            )
        }
    )
}

/** SearchScreen's SongInfoBottomSheet wiring for cloud songs, verbatim. */
@Composable
private fun SongInfoBottomSheetHost(
    song: Song,
    playerViewModel: PlayerViewModel,
    playlistViewModel: PlaylistViewModel,
    navController: NavController,
    onRemoveFromList: (Song) -> Unit,
    onDismiss: () -> Unit
) {
    val favoriteSongIds by playerViewModel.favoriteSongIds.collectAsStateWithLifecycle()
    val isFavorite = song.id in favoriteSongIds
    // IMPROVE(offline-downloads): live download state for the cloud songs
    // shown through this sheet — same wiring the search results use.
    val downloadedSongs by playerViewModel.downloadedSongs.collectAsStateWithLifecycle()
    val downloadStates by playerViewModel.downloadStates.collectAsStateWithLifecycle()
    val downloadStatus = com.saine.pixeltune.data.downloads.songDownloadStatus(
        song = song,
        isCloud = playerViewModel.isSongCloudStreamed(song),
        downloaded = downloadedSongs,
        states = downloadStates
    )
    var showPlaylistBottomSheet by remember { mutableStateOf(false) }
    val bottomBarHeightDp = NavBarContentHeight +
            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    SongInfoBottomSheet(
        song = song,
        isFavorite = isFavorite,
        removeFromListTrigger = { onRemoveFromList(song) },
        downloadStatus = downloadStatus,
        onDownloadToggle = { playerViewModel.toggleDownloadForSong(song) },
        onToggleFavorite = {
            playerViewModel.toggleFavoriteSpecificSong(song)
        },
        onDismiss = {
            showPlaylistBottomSheet = false
            onDismiss()
        },
        onPlaySong = {
            playerViewModel.showAndPlaySong(song)
            onDismiss()
        },
        onAddToQueue = {
            playerViewModel.addSongToQueue(song)
            onDismiss()
        },
        onAddNextToQueue = {
            playerViewModel.addSongNextToQueue(song)
            onDismiss()
        },
        onAddToPlayList = {
            showPlaylistBottomSheet = true
        },
        onDeleteFromDevice = playerViewModel::deleteFromDevice,
        onNavigateToAlbum = {
            navController.navigateSafely(Screen.AlbumDetail.createRoute(song.albumId))
            onDismiss()
        },
        onNavigateToArtist = {
            navController.navigateSafely(Screen.ArtistDetail.createRoute(song.artistId))
            onDismiss()
        },
        onEditSong = { newTitle, newArtist, newAlbum, newGenre, newLyrics, newTrackNumber, coverArtUpdate ->
            playerViewModel.editSongMetadata(
                song,
                newTitle,
                newArtist,
                newAlbum,
                newGenre,
                newLyrics,
                newTrackNumber,
                coverArtUpdate
            )
        },
    )

    if (showPlaylistBottomSheet) {
        val playlistUiState by playlistViewModel.uiState.collectAsStateWithLifecycle()
        PlaylistBottomSheet(
            playlistUiState = playlistUiState,
            songs = listOf(song),
            onDismiss = { showPlaylistBottomSheet = false },
            bottomBarHeight = bottomBarHeightDp,
            playerViewModel = playerViewModel
        )
    }
}
