@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.theveloper.pixeltune.presentation.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.material.icons.rounded.CheckCircle
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
import androidx.compose.runtime.snapshotFlow
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
import com.theveloper.pixeltune.presentation.components.ExpressiveScrollBar
import com.theveloper.pixeltune.presentation.components.rememberCanScrollMore
import com.theveloper.pixeltune.presentation.components.MiniPlayerHeight
import com.theveloper.pixeltune.presentation.components.MiniPlayerBottomSpacer
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
import kotlinx.coroutines.flow.distinctUntilChanged
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

    val isDarkTheme = LocalPixelTuneDarkTheme.current
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    var showSongInfoBottomSheet by remember { mutableStateOf(false) }
    val selectedSongForInfo by playerViewModel.selectedSongForInfo.collectAsStateWithLifecycle()
    var showPlaylistBottomSheet by remember { mutableStateOf(false) }
    val systemNavBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomBarHeightDp = NavBarContentHeight + systemNavBarInset

    // =================================================================
    // IMPROVE(cloud-playlist-import): "Add to your playlist" action for the
    // OPENED online playlist / album — adds it to the library's Playlists
    // tab (provider-badged) via the PlaylistViewModel + its import manager.
    // =================================================================
    // Collect as Compose state so the "Added" checkmark appears the MOMENT
    // the import completes (and the progress indicator while it runs).
    val importingCloudPlaylistId by playlistViewModel.importingCloudPlaylistId.collectAsStateWithLifecycle()
    val importedCloudPlaylistIds by playlistViewModel.importedCloudPlaylistIds.collectAsStateWithLifecycle()
    // Capture the delegated property into a local val so the null checks and
    // smart casts below compile (delegated properties cannot be smart-cast).
    val openedCloudPlaylist = uiState.playlist
    val isPlaylistImporting = openedCloudPlaylist != null &&
        importingCloudPlaylistId == openedCloudPlaylist.id
    val isPlaylistImported = openedCloudPlaylist != null &&
        importedCloudPlaylistIds.contains(
            com.theveloper.pixeltune.data.playlist.CloudPlaylistImportManager
                .playlistIdFor(openedCloudPlaylist)
        )

    // IMPROVE(cloud-playlist-import): surface the import outcome (success /
    // already-added / failure) through the app's global toast channel.
    LaunchedEffect(playlistViewModel) {
        playlistViewModel.cloudPlaylistImportEvents.collect { message ->
            playerViewModel.sendToast(message)
        }
    }

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

            // PERF(scroll): the effect key was `lazyListState.isScrollInProgress`,
            // evaluated in composition — the enclosing screen scope recomposed at
            // the start AND end of every scroll gesture. A snapshotFlow inside a
            // single Unit-keyed effect observes the same flips without any
            // composition-scope subscription.
            LaunchedEffect(Unit) {
                snapshotFlow { lazyListState.isScrollInProgress }
                    .distinctUntilChanged()
                    .collect { scrollInProgress ->
                        if (!scrollInProgress) {
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
            }

            val isMiniPlayerVisible = stablePlayerState.currentSong != null
            val fabBottomPadding by animateDpAsState(
                targetValue = if (isMiniPlayerVisible) MiniPlayerHeight + 16.dp else 16.dp,
                label = "fabPadding"
            )

            // FIX(cloud-detail-spacing): the track list's bottom reservation,
            // matching how the Search results list keeps its own "Load more"
            // row fully visible above the miniplayer. Two things eat into this
            // list's visible bottom edge:
            //   1. The miniplayer floats at the bottom of the screen above the
            //      system navigation-bar inset — its top edge sits
            //      (systemNavBarInset + MiniPlayerHeight + MiniPlayerBottomSpacer)
            //      above the screen bottom.
            //   2. This list is drawn OFFSET DOWN by the collapsing header (the
            //      .offset on the LazyColumn below). When the list is fully
            //      scrolled the header rests at minTopBarHeight, so the list's
            //      last visible row effectively ends minTopBarHeight BELOW the
            //      screen bottom — that offset has to be reserved too, or the
            //      bottom rows (the "Load more tracks" button included) slide
            //      behind the miniplayer.
            // The previous flat reservation (fabBottomPadding + spacer + 96.dp)
            // ignored the header offset and the real miniplayer overlay, so on
            // typical devices the "Load more tracks" button ended up partially
            // hidden behind the miniplayer bar.
            val listBottomPadding by animateDpAsState(
                targetValue = minTopBarHeight + systemNavBarInset + if (isMiniPlayerVisible) {
                    MiniPlayerHeight + MiniPlayerBottomSpacer + 24.dp
                } else {
                    24.dp
                },
                label = "cloudListBottomPadding"
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
                        end = if (rememberCanScrollMore(lazyListState) &&
                            collapseFraction > 0.95f
                        ) 24.dp else 16.dp,
                        // FIX(cloud-detail-spacing): reserve the full bottom
                        // overlay — collapsing-header offset + system nav inset +
                        // miniplayer (+ spacer) + breathing room — so the
                        // "Load more tracks" row is always fully visible and
                        // comfortably tappable above the miniplayer, exactly
                        // like the Search results list does. The app navigation
                        // bar no longer overlays this screen (its route joined
                        // the hidden-nav-bar set in MainActivity), so the
                        // miniplayer is the only floating bar to clear.
                        bottom = listBottomPadding
                    ),
                    // FIX(cloud-detail-spacing): lay every track out as its own
                    // clearly spaced card — the same 12.dp gap the Search
                    // results list puts between its result cards — instead of
                    // the song cards stacking flush against (visually
                    // overlapping) each other.
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (songs.isEmpty()) {
                        item(key = "cloud_empty", contentType = "empty") {
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
                            // PERF(scroll): keys must not bake in the index —
                            // "cloud_song_<id>_<index>" shifted every subsequent key
                            // when a song was removed, recomposing the whole visible
                            // list and losing item state. Song ids are already unique.
                            key = { _, song -> "cloud_song_${song.id}" },
                            contentType = { _, _ -> "song" }
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
                            item(key = "cloud_load_more", contentType = "load_more") {
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
                    rememberCanScrollMore(lazyListState)
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
                    // IMPROVE(cloud-playlist-import): "Add to your playlist"
                    // for the opened online playlist / album.
                    onAddToLibraryClick = openedCloudPlaylist?.let { playlist ->
                        {
                            playlistViewModel.importCloudPlaylistToLibrary(playlist)
                        }
                    },
                    isAddToLibraryImported = isPlaylistImported,
                    isAddToLibraryImporting = isPlaylistImporting,
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
    // IMPROVE(cloud-playlist-import): "Add to your playlist" action — null
    // for artist pages (only playlists / albums can be imported).
    onAddToLibraryClick: (() -> Unit)? = null,
    isAddToLibraryImported: Boolean = false,
    isAddToLibraryImporting: Boolean = false,
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

            // Top bar content (back button, add-to-library, title, shuffle FAB)
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

                // IMPROVE(cloud-playlist-import): "Add to your playlist" — a
                // real, fully tappable 48dp Material 3 tonal button pinned to
                // the top end of the header. It stays put through the collapse
                // animation, sits clear of the title block (the title column
                // reserves end padding), and flips to a checkmark once the
                // playlist is in the library.
                if (onAddToLibraryClick != null) {
                    FilledTonalIconButton(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(end = 12.dp, top = 4.dp)
                            .size(48.dp),
                        onClick = onAddToLibraryClick,
                        enabled = !isAddToLibraryImporting && !isAddToLibraryImported,
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = if (isAddToLibraryImported) {
                                MaterialTheme.colorScheme.tertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            },
                            contentColor = if (isAddToLibraryImported) {
                                MaterialTheme.colorScheme.onTertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    ) {
                        if (isAddToLibraryImporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.5.dp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else if (isAddToLibraryImported) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = "Added to your playlists"
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.PlaylistAdd,
                                contentDescription = "Add to your playlists"
                            )
                        }
                    }
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
                            .padding(start = titlePaddingStart, end = 96.dp)
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

                LargeExtendedFloatingActionButton(
                    onClick = onShuffleClick,
                    shape = RoundedStarShape(sides = 8, curve = 0.05, rotation = 0f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                        .graphicsLayer {
                            scaleX = fabScale
                            scaleY = fabScale
                            alpha = fabScale
                        }
                ) {
                    Icon(Icons.Rounded.Shuffle, contentDescription = "Shuffle play")
                }
            }
        }
    }
}
