package com.theveloper.pixeltune.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.theveloper.pixeltune.R
import com.theveloper.pixeltune.data.model.Song
import com.theveloper.pixeltune.presentation.components.MiniPlayerHeight
import com.theveloper.pixeltune.presentation.components.subcomps.EnhancedSongListItem
import com.theveloper.pixeltune.presentation.components.subcomps.SineWaveLine
import com.theveloper.pixeltune.presentation.model.RecentlyPlayedSongUiModel
import com.theveloper.pixeltune.presentation.model.mapRecentlyPlayedSongs
import com.theveloper.pixeltune.presentation.viewmodel.PlayerViewModel
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
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ListeningHistoryScreen(
    playerViewModel: PlayerViewModel,
    navController: NavController
) {
    val playbackHistory by playerViewModel.playbackHistory.collectAsStateWithLifecycle()
    val allSongs by playerViewModel.allSongsFlow.collectAsStateWithLifecycle()
    val cloudSongsById by playerViewModel.cloudSongsRegistry.collectAsStateWithLifecycle()
    val stablePlayerState by playerViewModel.stablePlayerState.collectAsStateWithLifecycle()

    val lazyListState = rememberLazyListState()
    var showClearConfirmDialog by rememberSaveable { mutableStateOf(false) }

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
            // ---- Header (back button + centered title + sine divider) ----
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
                        bottom = MiniPlayerHeight +
                            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                            140.dp
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
                                    if (historyQueue.isNotEmpty()) {
                                        playerViewModel.playSongs(
                                            songsToPlay = historyQueue,
                                            startSong = song,
                                            queueName = "Listening History"
                                        )
                                    }
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

        // ---- Clear history action, overlaid at the bottom center ----
        if (historySongs.isNotEmpty()) {
            LargeExtendedFloatingActionButton(
                onClick = { showClearConfirmDialog = true },
                shape = AbsoluteSmoothCornerShape(
                    cornerRadiusBR = 24.dp,
                    smoothnessAsPercentBR = 60,
                    cornerRadiusBL = 24.dp,
                    smoothnessAsPercentBL = 60,
                    cornerRadiusTR = 24.dp,
                    smoothnessAsPercentTR = 60,
                    cornerRadiusTL = 24.dp,
                    smoothnessAsPercentTL = 60
                ),
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                icon = {
                    Icon(
                        painter = painterResource(id = R.drawable.rounded_delete_24),
                        contentDescription = "Clear History"
                    )
                },
                text = { Text(text = "Clear History") },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
            )
        }

        // Bottom fade so content scrolls out gracefully under the FAB.
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
}
