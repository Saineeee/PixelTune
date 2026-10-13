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
import androidx.compose.material3.TopAppBarDefaults.topAppBarColors
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import com.saine.pixeltune.R
import com.saine.pixeltune.data.model.Genre
import com.saine.pixeltune.data.model.Song
import com.saine.pixeltune.data.surf.SurfJumpBackInEntry
import com.saine.pixeltune.data.surf.SurfSectionsBuilder
import com.saine.pixeltune.data.surf.SurfTopArtist
import com.saine.pixeltune.data.surf.YoutubeTrendingFeedParser
import com.saine.pixeltune.presentation.components.ExpressiveScrollBar
import com.saine.pixeltune.presentation.components.MiniPlayerHeight
import com.saine.pixeltune.presentation.components.NavBarContentHeight
import com.saine.pixeltune.presentation.components.PlaylistBottomSheet
import com.saine.pixeltune.presentation.components.SmartImage
import com.saine.pixeltune.presentation.components.SongInfoBottomSheet
import com.saine.pixeltune.presentation.components.StreamingProviderSheet
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
 * Library tabs' single onRefresh applied to the feed). The sections
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
    // be called from the non-composable click lambdas below.
    val trendingQueueName = stringResource(R.string.surf_trending_title)
    val relatedQueueName = stringResource(
        R.string.surf_because_you_played_title,
        surfUiState.relatedSeed?.title.orEmpty()
    )

    // On-device personalisation, derived exactly like HomeScreen derives its
    // sections: remember over the live flows + the pure SurfSectionsBuilder.
    val jumpBackIn = remember(playbackHistory, allSongs) {
        SurfSectionsBuilder.buildJumpBackIn(
            playbackHistory = playbackHistory,
            allSongs = allSongs
        )
    }
    val topArtists = remember(engagementStats, allSongs) {
        SurfSectionsBuilder.buildTopArtists(
            engagementStats = engagementStats,
            allSongs = allSongs
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

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .background(brush = gradientBrush),
            topBar = {
                SurfTopBar(
                    onCloudStreamClick = { showStreamingProviderSheet = true },
                    onSettingsClick = {
                        navController.navigateSafely(Screen.Settings.route)
                    }
                )
            }
        ) { innerPadding ->
            // The Library's content sheet — the feed sits on the surface
            // colour inside 34dp smooth top corners, over the tinted zone.
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = innerPadding.calculateTopPadding()),
                shape = AbsoluteSmoothCornerShape(
                    cornerRadiusTL = 34.dp,
                    smoothnessAsPercentTL = 60,
                    cornerRadiusTR = 34.dp,
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
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                top = 10.dp,
                                bottom = bottomBarHeight + miniPlayerGap + ListExtraBottomGap
                            ),
                            verticalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            if (jumpBackIn.isNotEmpty()) {
                                item(key = "surf_jump_back_in", contentType = "surf_section_jump_back_in") {
                                    SurfSectionHeader(title = stringResource(R.string.surf_jump_back_in_title))
                                }
                                item(key = "surf_jump_back_in_row", contentType = "surf_section_jump_back_in_row") {
                                    SurfJumpBackInRow(
                                        entries = jumpBackIn,
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
                                            navController.navigateSafely(
                                                Screen.ArtistDetail.createRoute(artist.artistId)
                                            )
                                        }
                                    )
                                }
                            }

                            item(key = "surf_trending", contentType = "surf_section_trending") {
                                SurfTrendingSection(
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

                            // "Fresh finds" — YouTube's trending playlist cards.
                            // SoundCloud exposes no second chart, so the section
                            // simply doesn't compose for it (loaded-and-empty,
                            // never an error row).
                            if (surfUiState.exploreIsLoading || surfUiState.exploreFailed ||
                                surfUiState.explorePlaylists.isNotEmpty()
                            ) {
                                item(key = "surf_explore", contentType = "surf_section_explore") {
                                    SurfExploreSection(
                                        playlists = surfUiState.explorePlaylists,
                                        isLoading = surfUiState.exploreIsLoading,
                                        failed = surfUiState.exploreFailed,
                                        isOnline = isOnline,
                                        onRetry = { surfViewModel.refreshExplore() },
                                        onPlaylistClick = { playlist ->
                                            navController.navigateSafely(
                                                Screen.SurfList.createRoute(
                                                    SurfListSource.playlist(
                                                        browseId = playlist.browseId,
                                                        title = playlist.title
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

    /** A trending playlist card's see-all page — "playlist:<browseId>|<title>". */
    fun playlist(browseId: String, title: String): String = "playlist:$browseId|$title"
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
 * The tab's top bar — the Library screen's own chrome: the big
 * GoogleSansRounded title in the theme's primary colour over the
 * primaryContainer-tinted bar, with the same FilledIconButton actions
 * (the streaming provider sheet and Settings).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SurfTopBar(
    onCloudStreamClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    TopAppBar(
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
 * "Jump back in": a horizontal row of square cover cards for the albums and
 * artists behind the listener's recent plays. Tapping re-opens the existing
 * AlbumDetail / ArtistDetail page — re-finding, not a new detail screen.
 */
@Composable
private fun SurfJumpBackInRow(
    entries: List<SurfJumpBackInEntry>,
    onEntryClick: (SurfJumpBackInEntry) -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 14.dp, end = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(
            items = entries,
            key = { entry ->
                when (entry) {
                    is SurfJumpBackInEntry.Album -> "jump_album_${entry.albumId}"
                    is SurfJumpBackInEntry.Artist -> "jump_artist_${entry.artistId}"
                }
            },
            contentType = { entry -> entry::class }
        ) { entry ->
            val subtitle = when (entry) {
                is SurfJumpBackInEntry.Album -> entry.artistName
                is SurfJumpBackInEntry.Artist ->
                    pluralStringResource(
                        R.plurals.surf_artist_song_count,
                        entry.songCount,
                        entry.songCount
                    )
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
                },
                onClick = { onEntryClick(entry) }
            )
        }
    }
}

/** "Top artists": circular avatars from the on-device engagement rollup. */
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
            key = { artist -> "top_artist_${artist.artistId}" },
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
 * "Trending now": the provider's own chart as a card carousel. The header's
 * see-all pill opens the full paged list; loading shows the app's expressive
 * LoadingIndicator; a failure with nothing stale to show degrades to the
 * compact retry row (offline copy when the connection is down).
 */
@Composable
private fun SurfTrendingSection(
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
            title = stringResource(R.string.surf_trending_title),
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
                        .height(190.dp),
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
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(start = 14.dp, end = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        items = uiState.trendingSongs,
                        key = { song -> "trending_${song.id}" },
                        contentType = { "trending_song" }
                    ) { song ->
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

/**
 * "Fresh finds": the YouTube Music trending playlist cards ("Today's fresh &
 * popular") as the feed's second online shelf — the cover-card carousel the
 * app's other shelves use, each card opening the playlist's own track page.
 * Only loading/failed/ready-with-content ever compose; a provider without a
 * second chart (SoundCloud) renders nothing here.
 */
@Composable
private fun SurfExploreSection(
    playlists: ImmutableList<YoutubeTrendingFeedParser.TrendingPlaylist>,
    isLoading: Boolean,
    failed: Boolean,
    isOnline: Boolean,
    onRetry: () -> Unit,
    onPlaylistClick: (YoutubeTrendingFeedParser.TrendingPlaylist) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SurfSectionHeader(title = stringResource(R.string.surf_explore_title))
        when {
            isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp),
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
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(start = 14.dp, end = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        items = playlists,
                        key = { playlist -> "explore_${playlist.browseId}" },
                        contentType = { "explore_playlist" }
                    ) { playlist ->
                        SurfMediaCard(
                            artworkUriString = playlist.artworkUrl,
                            title = playlist.title,
                            subtitle = playlist.artists,
                            contentDescription = stringResource(
                                R.string.surf_open_playlist, playlist.title
                            ),
                            onClick = { onPlaylistClick(playlist) }
                        )
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
 * A square cover card — the shared shape of the "Jump back in",
 * "Trending now" and "Fresh finds" carousels. Artwork corner and title
 * colour morph while the song plays, mirroring the pill/list-item morphs
 * across the app.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SurfMediaCard(
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
    Column(
        modifier = Modifier
            .width(150.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Box(
            modifier = Modifier
                .size(150.dp)
                .clip(AbsoluteSmoothCornerShape(cornerRadius = artworkCorner, smoothnessAsPercent = 60))
        ) {
            SmartImage(
                model = artworkUriString,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                shape = AbsoluteSmoothCornerShape(cornerRadius = artworkCorner, smoothnessAsPercent = 60)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = titleColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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
