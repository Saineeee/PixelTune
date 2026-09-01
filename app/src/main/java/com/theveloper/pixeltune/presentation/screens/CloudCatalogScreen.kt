@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.theveloper.pixeltune.presentation.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import coil.size.Size
import com.theveloper.pixeltune.data.playlist.PlaylistImportManager
import com.theveloper.pixeltune.presentation.components.ExpressiveScrollBar
import com.theveloper.pixeltune.presentation.components.MiniPlayerHeight
import com.theveloper.pixeltune.presentation.components.NavBarContentHeight
import com.theveloper.pixeltune.presentation.components.PlaylistBottomSheet
import com.theveloper.pixeltune.presentation.components.SmartImage
import com.theveloper.pixeltune.presentation.components.SongInfoBottomSheet
import com.theveloper.pixeltune.presentation.components.subcomps.EnhancedSongListItem
import com.theveloper.pixeltune.presentation.viewmodel.CloudCatalogViewModel
import com.theveloper.pixeltune.presentation.viewmodel.PlayerViewModel
import com.theveloper.pixeltune.presentation.viewmodel.PlaylistViewModel
import com.theveloper.pixeltune.ui.theme.LocalPixelTuneDarkTheme
import com.theveloper.pixeltune.utils.shapes.RoundedStarShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val HeaderVisualOverscan = 1.03f
private val HeaderGradientLift = 10.dp

/**
 * FIX(online-filter-chips): the ONLINE-search catalog detail screen.
 *
 * Tapping a cloud playlist / album / artist result row (YouTube Music or
 * SoundCloud online search) previously navigated to the LOCAL
 * AlbumDetail / ArtistDetail / PlaylistDetail screens, which look the entry
 * up in Room / MediaStore by a hashcode id that never exists there — the
 * "data doesn't show up correctly after clicking and opening" report. This
 * screen instead re-extracts the entry's REAL tracks from the provider
 * through the same NewPipe repositories + stream proxies the search uses,
 * so every row is immediately playable, likeable and downloadable.
 *
 * UI follows the app's heavily-used Material 3 expressive detail-screen
 * language — the exact collapsing header (animated height + gradient +
 * BiasAlignment title + LargeExtendedFloatingActionButton with the star
 * shape), ContainedLoadingIndicator first-load state, EnhancedSongListItem
 * rows and ExpressiveScrollBar of AlbumDetailScreen / ArtistDetailScreen.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CloudCatalogScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    viewModel: CloudCatalogViewModel = hiltViewModel(),
    playlistViewModel: PlaylistViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val stablePlayerState by playerViewModel.stablePlayerState.collectAsStateWithLifecycle()
    val favoriteIds by playerViewModel.favoriteSongIds.collectAsStateWithLifecycle()

    // IMPROVE(cloud-playlist-import): "Add to your playlist" state for the
    // header button of an opened ONLINE PLAYLIST — imports the whole playlist
    // into the Library's Playlists tab, where it plays like any local
    // playlist. Result messages surface as Material 3 snackbars through the
    // app's shared snackbar host.
    val importingCloudPlaylistKeys by playlistViewModel.importingCloudPlaylistKeys.collectAsStateWithLifecycle()
    val importedCloudPlaylistKeys by playlistViewModel.importedCloudPlaylistKeys.collectAsStateWithLifecycle()
    LaunchedEffect(playlistViewModel, playerViewModel) {
        playlistViewModel.cloudImportEvents.collect { message ->
            playerViewModel.sendToast(message)
        }
    }
    val openedCloudPlaylist = uiState.playlist
    val showAddToLibraryButton = openedCloudPlaylist != null && !openedCloudPlaylist.isAlbum
    val cloudImportKey = remember(openedCloudPlaylist) {
        openedCloudPlaylist?.let { PlaylistImportManager.cloudImportKey(it) }
    }

    val isDarkTheme = LocalPixelTuneDarkTheme.current
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    var showSongInfoBottomSheet by remember { mutableStateOf(false) }
    val selectedSongForInfo by playerViewModel.selectedSongForInfo.collectAsStateWithLifecycle()
    var showPlaylistBottomSheet by remember { mutableStateOf(false) }
    val systemNavBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomBarHeightDp = NavBarContentHeight + systemNavBarInset

    // OPTIMIZATION (copied from AlbumDetailScreen): defer long list work
    // until the navigation transition settles.
    var isTransitionFinished by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(600)
        isTransitionFinished = true
    }

    // FIX(online-filter-chips): the PLAY button on a search result row opens
    // this screen with autoplay=true — start the queue as soon as the first
    // page of real tracks is available. Exactly once.
    var autoPlayConsumed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(viewModel.autoPlay, uiState.isLoading, uiState.songs) {
        if (viewModel.autoPlay && !autoPlayConsumed && !uiState.isLoading && uiState.songs.isNotEmpty()) {
            autoPlayConsumed = true
            playerViewModel.showAndPlaySong(
                uiState.songs.first(),
                uiState.songs,
                uiState.headerTitle.ifBlank { "Online" }
            )
        }
    }

    when {
        // First load — the app's expressive full-screen loading state.
        uiState.isLoading -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ContainedLoadingIndicator()
                    SpacerHeight(20.dp)
                    Text(
                        text = "Loading tracks…",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    SpacerHeight(4.dp)
                    Text(
                        text = "Fetching \"${uiState.headerTitle}\" from the streaming service",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // The entry could not be decoded / first extraction failed.
        uiState.error != null && uiState.songs.isEmpty() -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding()
                    .padding(16.dp)
            ) {
                FilledIconButton(
                    onClick = { navController.popBackStack() },
                    modifier = Modifier.align(Alignment.TopStart),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Rounded.LibraryMusic,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    )
                    SpacerHeight(16.dp)
                    Text(
                        text = uiState.error ?: "Something went wrong",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    SpacerHeight(8.dp)
                    FilledTonalButton(onClick = { viewModel.retry() }) {
                        Text("Retry")
                    }
                }
            }
        }

        else -> {
            val songs = uiState.songs
            val lazyListState = rememberLazyListState()

            val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val minTopBarHeight = 64.dp + statusBarHeight
            val maxTopBarHeight = 300.dp

            val minTopBarHeightPx = with(density) { minTopBarHeight.toPx() }
            val maxTopBarHeightPx = with(density) { maxTopBarHeight.toPx() }

            val topBarHeight = remember { Animatable(maxTopBarHeightPx) }
            val collapseFraction by remember(minTopBarHeightPx, maxTopBarHeightPx) {
                derivedStateOf {
                    1f - ((topBarHeight.value - minTopBarHeightPx) /
                        (maxTopBarHeightPx - minTopBarHeightPx)).coerceIn(0f, 1f)
                }
            }

            val nestedScrollConnection = remember {
                object : NestedScrollConnection {
                    override fun onPreScroll(
                        available: Offset,
                        source: NestedScrollSource
                    ): Offset {
                        val delta = available.y
                        val isScrollingDown = delta < 0

                        if (!isScrollingDown && (lazyListState.firstVisibleItemIndex > 0 ||
                                lazyListState.firstVisibleItemScrollOffset > 0)
                        ) {
                            return Offset.Zero
                        }

                        val previousHeight = topBarHeight.value
                        val newHeight =
                            (previousHeight + delta).coerceIn(minTopBarHeightPx, maxTopBarHeightPx)
                        val consumed = newHeight - previousHeight

                        if (consumed.roundToInt() != 0) {
                            coroutineScope.launch {
                                topBarHeight.snapTo(newHeight)
                            }
                        }

                        val canConsumeScroll = !(isScrollingDown && newHeight == minTopBarHeightPx)
                        return if (canConsumeScroll) Offset(0f, consumed) else Offset.Zero
                    }

                    override suspend fun onPostFling(
                        consumed: Velocity,
                        available: Velocity
                    ): Velocity {
                        return super.onPostFling(consumed, available)
                    }
                }
            }

            LaunchedEffect(lazyListState.isScrollInProgress) {
                if (!lazyListState.isScrollInProgress) {
                    val shouldExpand =
                        topBarHeight.value > (minTopBarHeightPx + maxTopBarHeightPx) / 2
                    val canExpand =
                        lazyListState.firstVisibleItemIndex == 0 &&
                            lazyListState.firstVisibleItemScrollOffset == 0

                    val targetValue = if (shouldExpand && canExpand) {
                        maxTopBarHeightPx
                    } else {
                        minTopBarHeightPx
                    }

                    if (topBarHeight.value != targetValue) {
                        coroutineScope.launch {
                            topBarHeight.animateTo(
                                targetValue,
                                spring(stiffness = Spring.StiffnessMedium)
                            )
                        }
                    }
                }
            }

            val isMiniPlayerVisible = stablePlayerState.currentSong != null
            val fabBottomPadding by animateDpAsState(
                targetValue = if (isMiniPlayerVisible) MiniPlayerHeight + 16.dp else 16.dp,
                label = "fabPadding"
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color = MaterialTheme.colorScheme.surface)
                    .nestedScroll(nestedScrollConnection)
            ) {
                val currentTopBarHeightDp = with(density) { topBarHeight.value.toDp() }
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .offset { IntOffset(0, topBarHeight.value.toInt()) },
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = if ((lazyListState.canScrollForward || lazyListState.canScrollBackward) &&
                            collapseFraction > 0.95f
                        ) 24.dp else 16.dp,
                        bottom = fabBottomPadding + 80.dp // Account for the FAB
                    )
                ) {
                    if (songs.isEmpty()) {
                        item(key = "cloud_empty") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.LibraryMusic,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                )
                                SpacerHeight(12.dp)
                                Text(
                                    text = "No tracks available for this item",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                SpacerHeight(4.dp)
                                Text(
                                    text = "The provider may have restricted or removed it.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    } else {
                        val displayedSongs = if (isTransitionFinished) songs else songs.take(20)
                        itemsIndexed(
                            displayedSongs,
                            key = { index, song -> "cloud_song_${song.id}_$index" }
                        ) { _, song ->
                            EnhancedSongListItem(
                                song = song,
                                isPlaying = stablePlayerState.isPlaying,
                                isCurrentSong = stablePlayerState.currentSong?.id == song.id,
                                showAlbumArt = true,
                                onMoreOptionsClick = {
                                    playerViewModel.selectSongForInfo(song)
                                    showSongInfoBottomSheet = true
                                },
                                onClick = {
                                    playerViewModel.showAndPlaySong(
                                        song,
                                        songs,
                                        uiState.headerTitle.ifBlank { "Online" }
                                    )
                                }
                            )
                        }

                        // FIX(online-filter-chips): provider pagination — the
                        // initial page only carries part of large playlists /
                        // channel uploads.
                        if (uiState.hasMore || uiState.isLoadingMore ||
                            (uiState.error != null && songs.isNotEmpty())
                        ) {
                            item(key = "cloud_load_more") {
                                CloudLoadMoreRow(
                                    isLoadingMore = uiState.isLoadingMore,
                                    error = uiState.error?.takeIf { songs.isNotEmpty() },
                                    onLoadMore = { viewModel.loadMore() }
                                )
                            }
                        }
                    }
                }

                if (collapseFraction > 0.95f &&
                    (lazyListState.canScrollForward || lazyListState.canScrollBackward)
                ) {
                    ExpressiveScrollBar(
                        listState = lazyListState,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(
                                top = currentTopBarHeightDp + 12.dp,
                                bottom = fabBottomPadding + 80.dp
                            )
                    )
                }

                CollapsingCloudCatalogTopBar(
                    title = uiState.headerTitle,
                    subtitle = uiState.headerSubtitle,
                    trackCountLabel = uiState.trackCountLabel,
                    artworkUrl = uiState.headerArtworkUrl
                        ?: songs.firstOrNull()?.albumArtUriString,
                    isArtist = uiState.artist != null,
                    collapseFraction = collapseFraction,
                    headerHeight = currentTopBarHeightDp,
                    onBackPressed = { navController.popBackStack() },
                    onShuffleClick = {
                        if (songs.isNotEmpty()) {
                            val randomSong = songs.random()
                            playerViewModel.showAndPlaySong(
                                randomSong,
                                songs,
                                uiState.headerTitle.ifBlank { "Online" }
                            )
                        }
                    },
                    // IMPROVE(cloud-playlist-import): import button on the
                    // opened playlist's header (above the shuffle FAB).
                    showAddToLibrary = showAddToLibraryButton,
                    isImporting = cloudImportKey != null &&
                        importingCloudPlaylistKeys.contains(cloudImportKey),
                    isImported = cloudImportKey != null &&
                        importedCloudPlaylistKeys.contains(cloudImportKey),
                    onAddToLibraryClick = {
                        openedCloudPlaylist?.let { playlistViewModel.importCloudPlaylistToLibrary(it) }
                    },
                    isDarkTheme = isDarkTheme
                )
            }
        }
    }

    // The app's standard song bottom sheet (same wiring the online search
    // results use — favorite, queue, add-to-playlist, offline download).
    if (showSongInfoBottomSheet && selectedSongForInfo != null) {
        val currentSong = selectedSongForInfo
        val isFavorite = remember(currentSong?.id, favoriteIds) {
            derivedStateOf { currentSong?.let { favoriteIds.contains(it.id) } }
        }.value ?: false

        if (currentSong != null) {
            // IMPROVE(offline-downloads): live download state for cloud
            // catalog tracks opened through this sheet.
            val downloadedSongs by playerViewModel.downloadedSongs.collectAsStateWithLifecycle()
            val downloadStates by playerViewModel.downloadStates.collectAsStateWithLifecycle()
            val downloadStatus = com.theveloper.pixeltune.data.downloads.songDownloadStatus(
                song = currentSong,
                isCloud = playerViewModel.isSongCloudStreamed(currentSong),
                downloaded = downloadedSongs,
                states = downloadStates
            )
            SongInfoBottomSheet(
                song = currentSong,
                isFavorite = isFavorite,
                removeFromListTrigger = {
                    viewModel.removeSong(currentSong.id)
                },
                downloadStatus = downloadStatus,
                onDownloadToggle = { playerViewModel.toggleDownloadForSong(currentSong) },
                onToggleFavorite = {
                    playerViewModel.toggleFavoriteSpecificSong(currentSong)
                },
                onDismiss = { showSongInfoBottomSheet = false },
                onPlaySong = {
                    playerViewModel.showAndPlaySong(currentSong)
                    showSongInfoBottomSheet = false
                },
                onAddToQueue = {
                    playerViewModel.addSongToQueue(currentSong)
                    showSongInfoBottomSheet = false
                },
                onAddNextToQueue = {
                    playerViewModel.addSongNextToQueue(currentSong)
                    showSongInfoBottomSheet = false
                },
                onAddToPlayList = {
                    showPlaylistBottomSheet = true
                },
                onDeleteFromDevice = playerViewModel::deleteFromDevice,
                onNavigateToAlbum = {
                    showSongInfoBottomSheet = false
                },
                onNavigateToArtist = {
                    showSongInfoBottomSheet = false
                },
                onEditSong = { _, _, _, _, _, _, _ -> },
                generateAiMetadata = { fields ->
                    playerViewModel.generateAiMetadata(currentSong, fields)
                }
            )
            if (showPlaylistBottomSheet) {
                val playlistUiState by playlistViewModel.uiState.collectAsStateWithLifecycle()

                PlaylistBottomSheet(
                    playlistUiState = playlistUiState,
                    songs = listOf(currentSong),
                    onDismiss = { showPlaylistBottomSheet = false },
                    bottomBarHeight = bottomBarHeightDp,
                    playerViewModel = playerViewModel
                )
            }
        }
    }
}

/** Small local spacer helper — keeps the loading/empty blocks readable. */
@Composable
private fun SpacerHeight(height: androidx.compose.ui.unit.Dp) {
    Box(modifier = Modifier.height(height))
}

/**
 * FIX(online-filter-chips): the "Load more" row of the cloud catalog list —
 * M3 expressive button while idle, progress while fetching, inline retry
 * when a page failed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CloudLoadMoreRow(
    isLoadingMore: Boolean,
    error: String?,
    onLoadMore: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            isLoadingMore -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            error != null -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                    TextButton(onClick = onLoadMore) {
                        Text("Retry")
                    }
                }
            }
            else -> {
                FilledTonalButton(
                    onClick = onLoadMore,
                    shape = CircleShape
                ) {
                    Text("Load more tracks")
                }
            }
        }
    }
}

/**
 * FIX(online-filter-chips): the collapsing header of the cloud catalog
 * screen — a 1:1 adaptation of the app's signature AlbumDetailScreen header
 * (animated height, artwork + gradient, BiasAlignment title collapse,
 * star-shaped shuffle FAB) so online playlists/artists feel exactly like
 * the local detail pages.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollapsingCloudCatalogTopBar(
    title: String,
    subtitle: String,
    trackCountLabel: String?,
    artworkUrl: String?,
    isArtist: Boolean,
    collapseFraction: Float,
    headerHeight: Dp,
    onBackPressed: () -> Unit,
    onShuffleClick: () -> Unit,
    showAddToLibrary: Boolean = false,
    isImporting: Boolean = false,
    isImported: Boolean = false,
    onAddToLibraryClick: () -> Unit = {},
    isDarkTheme: Boolean
) {
    val surfaceColor = MaterialTheme.colorScheme.surface
    val statusBarColor = if (isDarkTheme) {
        Color.Black.copy(alpha = 0.6f)
    } else {
        Color.White.copy(alpha = 0.4f)
    }

    // Animation values (same recipe as AlbumDetailScreen).
    val fabScale = 1f - collapseFraction
    val backgroundAlpha = collapseFraction
    val headerContentAlpha = 1f - (collapseFraction * 2).coerceAtMost(1f)

    val titleScale = lerp(1f, 0.75f, collapseFraction)
    val titlePaddingStart = lerp(24.dp, 58.dp, collapseFraction)
    val titleMaxLines = if (collapseFraction < 0.5f) 2 else 1
    val titleVerticalBias = lerp(1f, -1f, collapseFraction)
    val animatedTitleAlignment =
        BiasAlignment(horizontalBias = -1f, verticalBias = titleVerticalBias)
    val titleContainerHeight = lerp(88.dp, 56.dp, collapseFraction)
    val yOffsetCorrection = lerp((titleContainerHeight / 2) - 64.dp, 0.dp, collapseFraction)

    val subtitleLine = if (trackCountLabel != null) {
        "$subtitle • $trackCountLabel"
    } else {
        subtitle
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(headerHeight)
            .clipToBounds()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(headerHeight)
                .background(surfaceColor.copy(alpha = backgroundAlpha))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = HeaderVisualOverscan
                        scaleY = HeaderVisualOverscan
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
            ) {
                // Header content (visible when expanded)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = headerContentAlpha }
                ) {
                    if (artworkUrl != null) {
                        SmartImage(
                            model = artworkUrl,
                            contentDescription = "Cover of $title",
                            contentScale = ContentScale.Crop,
                            targetSize = Size(1600, 1600),
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        // No provider artwork — a theme-colored header block
                        // (same treatment as local items without art).
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.primaryContainer,
                                            MaterialTheme.colorScheme.surfaceContainerHigh
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.LibraryMusic,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(96.dp)
                                    .clip(CircleShape),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(
                                    alpha = 0.75f
                                )
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .drawWithCache {
                                val liftPx = HeaderGradientLift.toPx()
                                val brush = Brush.verticalGradient(
                                    colorStops = arrayOf(
                                        0.30f to Color.Transparent,
                                        0.60f to surfaceColor.copy(alpha = 0.30f),
                                        0.83f to surfaceColor.copy(alpha = 0.90f),
                                        0.92f to surfaceColor,
                                        1f to surfaceColor
                                    ),
                                    startY = -liftPx,
                                    endY = size.height - liftPx
                                )
                                onDrawBehind { drawRect(brush = brush) }
                            }
                    )
                }

                // Status bar gradient
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(statusBarColor, Color.Transparent)
                            )
                        )
                        .align(Alignment.TopCenter)
                )
            }

            // Top bar content (back button, title, shuffle FAB)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                FilledIconButton(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 12.dp, top = 4.dp),
                    onClick = onBackPressed,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                }

                Box(
                    modifier = Modifier
                        .align(animatedTitleAlignment)
                        .height(titleContainerHeight)
                        .fillMaxWidth()
                        .offset(y = yOffsetCorrection)
                ) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = titlePaddingStart, end = 120.dp)
                            .graphicsLayer {
                                scaleX = titleScale
                                scaleY = titleScale
                            },
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontSize = 26.sp,
                                textGeometricTransform = TextGeometricTransform(scaleX = 1.2f),
                            ),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = titleMaxLines,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = subtitleLine,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // IMPROVE(cloud-playlist-import): "Add to your playlist"
                // button stacked above the shuffle FAB — same star shape,
                // tonal M3 container and collapse-driven scale/alpha so the
                // header's action pair reads as one expressive group. Morphs
                // add → progress → added while the import runs.
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                        .graphicsLayer {
                            scaleX = fabScale
                            scaleY = fabScale
                            alpha = fabScale
                        },
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    if (showAddToLibrary) {
                        FilledTonalIconButton(
                            onClick = onAddToLibraryClick,
                            enabled = !isImporting,
                            modifier = Modifier.size(56.dp),
                            shape = RoundedStarShape(sides = 8, curve = 0.05, rotation = 0f),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) {
                            Crossfade(
                                targetState = when {
                                    isImporting -> "importing"
                                    isImported -> "imported"
                                    else -> "idle"
                                },
                                animationSpec = tween(durationMillis = 190),
                                label = "cloudHeaderImportState"
                            ) { state ->
                                when (state) {
                                    "importing" -> CircularProgressIndicator(
                                        modifier = Modifier.size(22.dp),
                                        strokeWidth = 2.5.dp,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    "imported" -> Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = "Added to Library",
                                        modifier = Modifier.size(26.dp)
                                    )
                                    else -> Icon(
                                        imageVector = Icons.Rounded.PlaylistAdd,
                                        contentDescription = "Add to your Library",
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                            }
                        }
                    }

                    LargeExtendedFloatingActionButton(
                        onClick = onShuffleClick,
                        shape = RoundedStarShape(sides = 8, curve = 0.05, rotation = 0f)
                    ) {
                        Icon(Icons.Rounded.Shuffle, contentDescription = "Shuffle play")
                    }
                }
            }
        }
    }
}
