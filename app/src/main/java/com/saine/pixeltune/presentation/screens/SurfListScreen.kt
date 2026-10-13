@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.saine.pixeltune.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import com.saine.pixeltune.R
import com.saine.pixeltune.data.model.Song
import com.saine.pixeltune.presentation.components.MiniPlayerBottomSpacer
import com.saine.pixeltune.presentation.components.MiniPlayerHeight
import com.saine.pixeltune.presentation.components.NavBarContentHeight
import com.saine.pixeltune.presentation.components.PlaylistBottomSheet
import com.saine.pixeltune.presentation.components.SongInfoBottomSheet
import com.saine.pixeltune.presentation.components.subcomps.EnhancedSongListItem
import com.saine.pixeltune.presentation.navigation.Screen
import com.saine.pixeltune.presentation.navigation.navigateSafely
import com.saine.pixeltune.presentation.viewmodel.PlayerViewModel
import com.saine.pixeltune.presentation.viewmodel.PlaylistViewModel
import com.saine.pixeltune.presentation.viewmodel.SurfListViewModel
import com.saine.pixeltune.utils.SearchResultKeys
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * SURF: the "see all" list behind the discovery feed — the trending chart's
 * full page and the online genre / mood pages.
 *
 * The layout recombines the app's existing detail-screen parts: the loading
 * and error states are CloudCatalogScreen's (ContainedLoadingIndicator /
 * icon + retry), the gradient header and Play/Shuffle action row are
 * RecentlyPlayedScreen's, the rows are the standard EnhancedSongListItem,
 * and the pagination row is the search results' "Load more" button.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SurfListScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    viewModel: SurfListViewModel = hiltViewModel(),
    playlistViewModel: PlaylistViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val stablePlayerState by playerViewModel.stablePlayerState.collectAsStateWithLifecycle()
    val favoriteIds by playerViewModel.favoriteSongIds.collectAsStateWithLifecycle()

    val currentSongId by remember(playerViewModel.stablePlayerState) {
        playerViewModel.stablePlayerState.map { it.currentSong?.id }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = null)

    val lazyListState = rememberLazyListState()

    var showSongInfoBottomSheet by remember { mutableStateOf(false) }
    var showPlaylistBottomSheet by remember { mutableStateOf(false) }
    var selectedSongForInfo by remember { mutableStateOf<Song?>(null) }
    val selectedSong = selectedSongForInfo

    val systemNavBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val miniPlayerVisible = stablePlayerState.currentSong != null
    val listBottomPadding = systemNavBarInset +
            (if (miniPlayerVisible) MiniPlayerHeight + MiniPlayerBottomSpacer else 0.dp) + 24.dp

    val bgColors = listOf(
        MaterialTheme.colorScheme.secondary.copy(alpha = 0.24f),
        MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
        MaterialTheme.colorScheme.surface
    )
    val backgroundBrush = remember(bgColors) {
        Brush.verticalGradient(colors = bgColors, endY = 1200f)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBrush)
    ) {
        when {
            // First load — CloudCatalogScreen's full-screen expressive state.
            uiState.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        ContainedLoadingIndicator()
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = stringResource(R.string.surf_list_loading),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.surf_list_fetching, uiState.title),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // The feed could not be loaded at all — CloudCatalogScreen's error state.
            uiState.failed -> {
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.surf_back))
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
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.surf_list_error),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        FilledTonalButton(onClick = { viewModel.retry() }) {
                            Text(stringResource(R.string.surf_retry))
                        }
                    }
                }
            }

            else -> {
                val songs = uiState.songs
                // FIX(online-search-chip-crash)-style guard: keys stay unique
                // even if the provider page repeats an entry or hashes collide.
                val songKeys = remember(songs) {
                    SearchResultKeys.disambiguate(songs.map { "surf_song_${it.id}" })
                }

                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = listBottomPadding),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item(key = "surf_list_header", contentType = "header") {
                        SurfListHeader(
                            title = uiState.title,
                            subtitle = uiState.subtitle,
                            scrollState = lazyListState,
                            onBackClick = { navController.popBackStack() }
                        )
                    }

                    if (songs.isNotEmpty()) {
                        item(key = "surf_list_actions", contentType = "actions") {
                            SurfListActions(
                                onPlay = {
                                    val firstSong = songs.firstOrNull() ?: return@SurfListActions
                                    playerViewModel.playSongs(
                                        songsToPlay = songs,
                                        startSong = firstSong,
                                        queueName = uiState.title
                                    )
                                },
                                onShuffle = {
                                    playerViewModel.playSongsShuffled(
                                        songsToPlay = songs,
                                        queueName = uiState.title
                                    )
                                },
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }

                        itemsIndexed(
                            songs,
                            key = { index, _ -> songKeys[index] },
                            contentType = { _, _ -> "song" }
                        ) { _, song ->
                            EnhancedSongListItem(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                song = song,
                                isPlaying = stablePlayerState.isPlaying,
                                isCurrentSong = currentSongId == song.id,
                                onMoreOptionsClick = {
                                    selectedSongForInfo = song
                                    showSongInfoBottomSheet = true
                                },
                                onClick = {
                                    playerViewModel.playSongs(
                                        songsToPlay = songs,
                                        startSong = song,
                                        queueName = uiState.title
                                    )
                                }
                            )
                        }

                        if (uiState.hasMore || uiState.isLoadingMore) {
                            item(key = "surf_list_load_more", contentType = "load_more") {
                                SurfListLoadMoreRow(
                                    isLoadingMore = uiState.isLoadingMore,
                                    onLoadMore = { viewModel.loadMore() }
                                )
                            }
                        }
                    } else {
                        item(key = "surf_list_empty", contentType = "empty") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp, horizontal = 16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.LibraryMusic,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = stringResource(R.string.surf_list_empty_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.surf_list_empty_message),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // The app's standard song bottom sheet (same wiring the online search
    // results and CloudCatalog use — favorite, queue, add-to-playlist).
    if (showSongInfoBottomSheet && selectedSong != null) {
        val isFavorite = remember(selectedSong.id, favoriteIds) {
            favoriteIds.contains(selectedSong.id)
        }
        // IMPROVE(offline-downloads): live download state for the cloud songs
        // shown through this sheet — same wiring the search results use.
        val downloadedSongs by playerViewModel.downloadedSongs.collectAsStateWithLifecycle()
        val downloadStates by playerViewModel.downloadStates.collectAsStateWithLifecycle()
        val downloadStatus = com.saine.pixeltune.data.downloads.songDownloadStatus(
            song = selectedSong,
            isCloud = playerViewModel.isSongCloudStreamed(selectedSong),
            downloaded = downloadedSongs,
            states = downloadStates
        )
        SongInfoBottomSheet(
            song = selectedSong,
            isFavorite = isFavorite,
            removeFromListTrigger = { viewModel.removeSong(selectedSong.id) },
            downloadStatus = downloadStatus,
            onDownloadToggle = { playerViewModel.toggleDownloadForSong(selectedSong) },
            onToggleFavorite = {
                playerViewModel.toggleFavoriteSpecificSong(selectedSong)
            },
            onDismiss = {
                showSongInfoBottomSheet = false
                showPlaylistBottomSheet = false
                selectedSongForInfo = null
            },
            onPlaySong = {
                playerViewModel.showAndPlaySong(selectedSong)
                showSongInfoBottomSheet = false
            },
            onAddToQueue = {
                playerViewModel.addSongToQueue(selectedSong)
                showSongInfoBottomSheet = false
            },
            onAddNextToQueue = {
                playerViewModel.addSongNextToQueue(selectedSong)
                showSongInfoBottomSheet = false
            },
            onAddToPlayList = {
                showPlaylistBottomSheet = true
            },
            onDeleteFromDevice = playerViewModel::deleteFromDevice,
            onNavigateToAlbum = {
                navController.navigateSafely(Screen.AlbumDetail.createRoute(selectedSong.albumId))
                showSongInfoBottomSheet = false
            },
            onNavigateToArtist = {
                navController.navigateSafely(Screen.ArtistDetail.createRoute(selectedSong.artistId))
                showSongInfoBottomSheet = false
            },
            onEditSong = { newTitle, newArtist, newAlbum, newGenre, newLyrics, newTrackNumber, coverArtUpdate ->
                playerViewModel.editSongMetadata(
                    selectedSong,
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
                songs = listOf(selectedSong),
                onDismiss = { showPlaylistBottomSheet = false },
                bottomBarHeight = NavBarContentHeight + systemNavBarInset,
                playerViewModel = playerViewModel
            )
        }
    }
}

/**
 * The list's header — RecentlyPlayedScreen's ExpressiveRecentlyPlayedHeader
 * pattern: gradient banner, parallax + fade on scroll, variable-font title,
 * plus the back affordance the CloudCatalog detail screens carry.
 */
@Composable
private fun SurfListHeader(
    title: String,
    subtitle: String,
    scrollState: androidx.compose.foundation.lazy.LazyListState,
    onBackClick: () -> Unit
) {
    val parallaxOffset by remember {
        derivedStateOf {
            if (scrollState.firstVisibleItemIndex == 0) {
                scrollState.firstVisibleItemScrollOffset * 0.36f
            } else 0f
        }
    }
    val headerAlpha by remember {
        derivedStateOf {
            (1f - (scrollState.firstVisibleItemScrollOffset / 520f)).coerceIn(0f, 1f)
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp)
            .graphicsLayer {
                translationY = parallaxOffset
                alpha = headerAlpha
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.24f),
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                            MaterialTheme.colorScheme.surface
                        ),
                        endY = 780f
                    )
                )
        )
        FilledIconButton(
            onClick = onBackClick,
            modifier = Modifier
                .statusBarsPadding()
                .padding(start = 10.dp, top = 6.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.surf_back))
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = title,
                style = rememberSurfListTitleStyle(),
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalTextApi::class)
@Composable
private fun rememberSurfListTitleStyle(): androidx.compose.ui.text.TextStyle {
    return remember {
        androidx.compose.ui.text.TextStyle(
            fontFamily = FontFamily(
                Font(
                    resId = R.font.gflex_variable,
                    variationSettings = FontVariation.Settings(
                        FontVariation.weight(560),
                        FontVariation.width(122f),
                        FontVariation.grade(40),
                        FontVariation.Setting("ROND", 100f),
                        FontVariation.Setting("XTRA", 520f),
                        FontVariation.Setting("YOPQ", 90f),
                        FontVariation.Setting("YTLC", 505f)
                    )
                )
            ),
            fontWeight = FontWeight(560),
            fontSize = 34.sp,
            lineHeight = 38.sp,
            letterSpacing = (-0.4).sp
        )
    }
}

/** RecentlyPlayedScreen's RecentlyPlayedActions, verbatim shape. */
@Composable
private fun SurfListActions(
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onPlay,
            modifier = Modifier
                .weight(1f)
                .fillMaxSize(),
            shape = RoundedCornerShape(
                topStart = 52.dp,
                topEnd = 14.dp,
                bottomStart = 52.dp,
                bottomEnd = 14.dp
            )
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize)
            )
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.surf_list_play_all))
        }

        FilledTonalButton(
            onClick = onShuffle,
            modifier = Modifier
                .weight(1f)
                .fillMaxSize(),
            shape = RoundedCornerShape(
                topStart = 14.dp,
                topEnd = 52.dp,
                bottomStart = 14.dp,
                bottomEnd = 52.dp
            )
        ) {
            Icon(
                imageVector = Icons.Rounded.Shuffle,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize)
            )
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.surf_list_shuffle))
        }
    }
}

/** The search results' "Load more" row, verbatim. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SurfListLoadMoreRow(
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        if (isLoadingMore) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                strokeWidth = 3.dp,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            FilledTonalButton(
                onClick = onLoadMore,
                shape = CircleShape
            ) {
                Icon(
                    Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.surf_list_load_more))
            }
        }
    }
}
