package com.theveloper.pixeltune.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.size.Size
import com.theveloper.pixeltune.R
import com.theveloper.pixeltune.data.model.Song
import com.theveloper.pixeltune.presentation.components.MiniPlayerHeight
import com.theveloper.pixeltune.presentation.components.PlaylistBottomSheet
import com.theveloper.pixeltune.presentation.components.SmartImage
import com.theveloper.pixeltune.presentation.components.subcomps.EnhancedSongListItem
import com.theveloper.pixeltune.presentation.components.subcomps.SineWaveLine
import com.theveloper.pixeltune.presentation.model.RecentlyPlayedSongUiModel
import com.theveloper.pixeltune.presentation.model.mapRecentlyPlayedSongs
import com.theveloper.pixeltune.presentation.viewmodel.PlayerViewModel
import com.theveloper.pixeltune.presentation.viewmodel.PlaylistViewModel
import com.theveloper.pixeltune.ui.theme.ExpTitleTypography
import com.theveloper.pixeltune.ui.theme.GoogleSansRounded
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Full-screen Listening History page.
 *
 * Replaces the old partial-height ModalBottomSheet that opened from the Home
 * top-bar history button (IMPROVE: "make the listening history page a
 * stretchable full screen page"). The history now fills the whole screen and
 * shows up to 30 songs — the most recently listened ones, grouped by day.
 *
 * Cloud-streamed songs (YouTube / SoundCloud) are fully supported: history
 * entries carry a metadata snapshot (title / artist / artwork / normalized
 * playback URI), so entries render and play correctly even across app
 * restarts, when the in-memory cloud songs registry is empty.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ListeningHistoryScreen(
    playerViewModel: PlayerViewModel,
    playlistViewModel: PlaylistViewModel = hiltViewModel(),
    navController: NavController
) {
    val playbackHistory by playerViewModel.playbackHistory.collectAsStateWithLifecycle()
    val allSongs by playerViewModel.allSongsFlow.collectAsStateWithLifecycle()
    val cloudSongsById by playerViewModel.cloudSongsRegistry.collectAsStateWithLifecycle()
    val stablePlayerState by playerViewModel.stablePlayerState.collectAsStateWithLifecycle()
    val favoriteSongIds by playerViewModel.favoriteSongIds.collectAsStateWithLifecycle()
    val playlistUiState by playlistViewModel.uiState.collectAsStateWithLifecycle()

    val lazyListState = rememberLazyListState()
    var showClearConfirmDialog by rememberSaveable { mutableStateOf(false) }

    // IMPROVE(history-three-dot): the three-dot button on each row now opens
    // a Material 3 bottom sheet with per-song actions (Add to Liked / Remove
    // from history / Add to playlist) instead of silently replaying the queue.
    var optionsSong by remember { mutableStateOf<Song?>(null) }
    var showPlaylistBottomSheet by remember { mutableStateOf(false) }

    val bottomBarHeightDp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // Merge local library + session cloud registry. Entries that still can't be
    // resolved through this merge (e.g. cloud songs after an app restart) fall
    // back to their persisted metadata snapshot inside mapRecentlyPlayedSongs.
    val historySongs = remember(playbackHistory, allSongs, cloudSongsById) {
        val mergedSongs = if (cloudSongsById.isEmpty()) {
            allSongs
        } else {
            val seen = HashSet<String>(allSongs.size + cloudSongsById.size)
            val combined = ArrayList<Song>(allSongs.size + cloudSongsById.size)
            for (song in allSongs) {
                if (seen.add(song.id)) combined += song
            }
            for (song in cloudSongsById.values) {
                if (seen.add(song.id)) combined += song
            }
            combined
        }
        mapRecentlyPlayedSongs(
            playbackHistory = playbackHistory,
            songs = mergedSongs,
            maxItems = 30
        )
    }
    val historyQueue = remember(historySongs) {
        historySongs.map { it.song }
    }

    // Group entries by day: Today / Yesterday / formatted date.
    val groupedSongs = remember(historySongs) {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

        // LinkedHashMap preserves the (most-recent-first) order of the entries.
        val groups = LinkedHashMap<String, MutableList<RecentlyPlayedSongUiModel>>()
        historySongs.forEach { item ->
            val date = Instant.ofEpochMilli(item.lastPlayedTimestamp)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
            val header = when (date) {
                today -> "Today"
                yesterday -> "Yesterday"
                else -> date.format(formatter)
            }
            groups.getOrPut(header) { mutableListOf() } += item
        }
        groups
    }

    val surface = MaterialTheme.colorScheme.surface
    val secondary = MaterialTheme.colorScheme.secondary
    val primary = MaterialTheme.colorScheme.primary
    val backgroundBrush = remember(surface, secondary, primary) {
        Brush.verticalGradient(
            colors = listOf(
                secondary.copy(alpha = 0.24f),
                primary.copy(alpha = 0.10f),
                surface.copy(alpha = 0.95f),
                surface
            ),
            endY = 1200f
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBrush)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // ---- Header (back button + centered title + sine divider + clear action) ----
            Box(modifier = Modifier.fillMaxWidth()) {
                FilledIconButton(
                    onClick = { navController.popBackStack() },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier
                        .padding(start = 10.dp, top = 8.dp)
                        .clip(CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back"
                    )
                }

                // IMPROVE(clear-history-placement): "Clear History" used to be a
                // large FAB overlaid at the bottom of the screen, where it sat
                // underneath the Home/Search/Library bottom navigation bar and was
                // impossible to tap. It now lives in the header as an icon button
                // (error-container styling marks it as destructive, per Material 3),
                // always reachable while the list scrolls under it. The destructive
                // action itself is still guarded by the confirmation dialog below.
                if (historySongs.isNotEmpty()) {
                    FilledIconButton(
                        onClick = { showClearConfirmDialog = true },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        ),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(end = 10.dp, top = 8.dp)
                            .clip(CircleShape)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.rounded_delete_24),
                            contentDescription = "Clear History"
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Listening History",
                        fontFamily = GoogleSansRounded,
                        style = ExpTitleTypography.displaySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    SineWaveLine(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(32.dp)
                            .padding(horizontal = 8.dp),
                        animate = true,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                        alpha = 0.95f,
                        strokeWidth = 4.dp,
                        amplitude = 4.dp,
                        waves = 7.6f,
                        phase = 0f
                    )
                }
            }

            // ---- Song list (stretchable full-screen content) ----
            if (historySongs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "No history yet",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Songs you play will show up here",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        // IMPROVE(clear-history-placement): the bottom padding no
                        // longer reserves room for the removed Clear-History FAB;
                        // just clear the mini player + gesture navigation bar.
                        bottom = MiniPlayerHeight +
                            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                            32.dp
                    )
                ) {
                    groupedSongs.forEach { (header, groupItems) ->
                        item(key = "header_$header") {
                            Text(
                                text = header,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                            )
                        }

                        items(
                            groupItems,
                            key = { item -> item.song.id + "_" + item.lastPlayedTimestamp }
                        ) { item ->
                            val isCurrentSong =
                                item.song.id == stablePlayerState.currentSong?.id
                            EnhancedSongListItem(
                                song = item.song,
                                isPlaying = stablePlayerState.isPlaying && isCurrentSong,
                                isCurrentSong = isCurrentSong,
                                onMoreOptionsClick = { song ->
                                    // IMPROVE(history-three-dot): open the per-song
                                    // options sheet (Add to Liked / Remove from
                                    // history / Add to playlist).
                                    optionsSong = song
                                },
                                onClick = {
                                    if (historyQueue.isNotEmpty()) {
                                        playerViewModel.playSongs(
                                            songsToPlay = historyQueue,
                                            startSong = item.song,
                                            queueName = "Listening History"
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        // Bottom fade so content scrolls out gracefully at the screen edge.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(20.dp)
                .background(
                    brush = Brush.verticalGradient(
                        listOf(
                            Color.Transparent,
                            MaterialTheme.colorScheme.surfaceContainerLow
                        )
                    )
                )
        )
    }

    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = { Text(text = "Clear listening history?") },
            text = {
                Text(
                    text = "This removes all ${historySongs.size} songs from your listening " +
                        "history. Your listening stats are not affected."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirmDialog = false
                        playerViewModel.clearPlaybackHistory()
                    }
                ) {
                    Text(text = "Clear")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearConfirmDialog = false }
                ) {
                    Text(text = "Cancel")
                }
            }
        )
    }

    // IMPROVE(history-three-dot): per-song options sheet.
    val songForOptions = optionsSong
    if (songForOptions != null && !showPlaylistBottomSheet) {
        // IMPROVE(offline-downloads): live download state for cloud songs in
        // the listening history.
        val downloadedSongs by playerViewModel.downloadedSongs.collectAsStateWithLifecycle()
        val downloadStates by playerViewModel.downloadStates.collectAsStateWithLifecycle()
        val historyDownloadStatus = com.theveloper.pixeltune.data.downloads.songDownloadStatus(
            song = songForOptions,
            isCloud = playerViewModel.isSongCloudStreamed(songForOptions),
            downloaded = downloadedSongs,
            states = downloadStates
        )
        HistorySongOptionsSheet(
            song = songForOptions,
            isLiked = favoriteSongIds.contains(songForOptions.id),
            downloadStatus = historyDownloadStatus,
            onDownloadToggle = { playerViewModel.toggleDownloadForSong(songForOptions) },
            onDismiss = { optionsSong = null },
            onToggleLike = {
                val wasLiked = favoriteSongIds.contains(songForOptions.id)
                playerViewModel.toggleFavoriteSpecificSong(songForOptions)
                playerViewModel.sendToast(
                    if (wasLiked) "Removed from Liked" else "Added to Liked"
                )
                optionsSong = null
            },
            onAddToPlaylist = {
                showPlaylistBottomSheet = true
            },
            onRemoveFromHistory = {
                playerViewModel.removeFromPlaybackHistory(songForOptions.id)
                playerViewModel.sendToast("Removed from listening history")
                optionsSong = null
            }
        )
    }

    if (songForOptions != null && showPlaylistBottomSheet) {
        PlaylistBottomSheet(
            playlistUiState = playlistUiState,
            songs = listOf(songForOptions),
            onDismiss = {
                showPlaylistBottomSheet = false
                optionsSong = null
            },
            bottomBarHeight = bottomBarHeightDp,
            playerViewModel = playerViewModel
        )
    }
}

/**
 * IMPROVE(history-three-dot): Material 3 bottom sheet with the per-song
 * actions for a Listening History row — "Add to Liked", "Add to playlist"
 * and "Remove" (delete this entry from the listening history).
 *
 * Follows the app's expressive M3 styling: rounded option rows with tonal
 * leading icon containers, a song header with artwork, and the standard
 * ModalBottomSheet enter/exit motion.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HistorySongOptionsSheet(
    song: Song,
    isLiked: Boolean,
    downloadStatus: com.theveloper.pixeltune.data.downloads.SongDownloadStatus =
        com.theveloper.pixeltune.data.downloads.SongDownloadStatus.Hidden,
    onDownloadToggle: () -> Unit = {},
    onDismiss: () -> Unit,
    onToggleLike: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRemoveFromHistory: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { true }
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            // ---- Song header (artwork + title + artist) ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(14.dp)
                        )
                ) {
                    SmartImage(
                        model = song.albumArtUriString,
                        contentDescription = song.title,
                        shape = RoundedCornerShape(14.dp),
                        targetSize = Size(168, 168),
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = GoogleSansRounded,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = song.displayArtist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            HistoryOptionRow(
                icon = painterResource(
                    if (isLiked) R.drawable.rounded_favorite_24
                    else R.drawable.round_favorite_border_24
                ),
                label = if (isLiked) "Remove from Liked" else "Add to Liked",
                supportingText = if (isLiked) {
                    "Take this song out of your Liked tab"
                } else {
                    "Show this song in your Library's Liked tab"
                },
                iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
                iconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                onClick = onToggleLike
            )

            Spacer(modifier = Modifier.height(10.dp))

            HistoryOptionRow(
                icon = painterResource(id = R.drawable.rounded_playlist_add_24),
                label = "Add to playlist",
                supportingText = "Add this song to one of your playlists",
                iconContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                iconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                onClick = onAddToPlaylist
            )

            // IMPROVE(offline-downloads): download toggle for cloud songs.
            if (downloadStatus != com.theveloper.pixeltune.data.downloads.SongDownloadStatus.Hidden) {
                Spacer(modifier = Modifier.height(10.dp))
                when (downloadStatus) {
                    is com.theveloper.pixeltune.data.downloads.SongDownloadStatus.Downloading -> {
                        HistoryOptionRow(
                            imageVector = Icons.Rounded.Download,
                            label = if (downloadStatus.indeterminate) {
                                "Downloading…"
                            } else {
                                "Downloading… ${downloadStatus.progressPercent}%"
                            },
                            supportingText = "Tap to cancel the offline download",
                            iconContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            iconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            onClick = onDownloadToggle
                        )
                    }
                    com.theveloper.pixeltune.data.downloads.SongDownloadStatus.Downloaded -> {
                        HistoryOptionRow(
                            imageVector = Icons.Rounded.DownloadDone,
                            label = "Remove Download",
                            supportingText = "Delete the offline copy of this song",
                            iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            iconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            onClick = onDownloadToggle
                        )
                    }
                    else -> {
                        HistoryOptionRow(
                            imageVector = Icons.Rounded.Download,
                            label = "Download",
                            supportingText = "Save this song for offline playback",
                            iconContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            iconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            onClick = onDownloadToggle
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            HistoryOptionRow(
                icon = painterResource(id = R.drawable.rounded_delete_24),
                label = "Remove",
                supportingText = "Delete this entry from your listening history",
                iconContainerColor = MaterialTheme.colorScheme.errorContainer,
                iconContentColor = MaterialTheme.colorScheme.onErrorContainer,
                onClick = onRemoveFromHistory
            )
        }
    }
}

/**
 * A single rounded option row inside [HistorySongOptionsSheet]: tonal leading
 * icon container + label / supporting text, with ripple feedback.
 */
@Composable
private fun HistoryOptionRow(
    icon: Painter? = null,
    imageVector: ImageVector? = null,
    label: String,
    supportingText: String,
    iconContainerColor: Color,
    iconContentColor: Color,
    onClick: () -> Unit
) {
    Surface(
        shape = AbsoluteSmoothCornerShape(
            cornerRadiusBR = 22.dp,
            smoothnessAsPercentBR = 60,
            cornerRadiusBL = 22.dp,
            smoothnessAsPercentBL = 60,
            cornerRadiusTR = 22.dp,
            smoothnessAsPercentTR = 60,
            cornerRadiusTL = 22.dp,
            smoothnessAsPercentTL = 60
        ),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .clip(
                AbsoluteSmoothCornerShape(
                    cornerRadiusBR = 22.dp,
                    smoothnessAsPercentBR = 60,
                    cornerRadiusBL = 22.dp,
                    smoothnessAsPercentBL = 60,
                    cornerRadiusTR = 22.dp,
                    smoothnessAsPercentTR = 60,
                    cornerRadiusTL = 22.dp,
                    smoothnessAsPercentTL = 60
                )
            )
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(iconContainerColor),
                contentAlignment = Alignment.Center
            ) {
                when {
                    imageVector != null -> {
                        Icon(
                            imageVector = imageVector,
                            contentDescription = label,
                            tint = iconContentColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    icon != null -> {
                        Icon(
                            painter = icon,
                            contentDescription = label,
                            tint = iconContentColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = GoogleSansRounded,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
