package com.theveloper.pixeltune.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixeltune.R
import com.theveloper.pixeltune.data.model.Song
import com.theveloper.pixeltune.presentation.components.subcomps.EnhancedSongListItem
import com.theveloper.pixeltune.presentation.components.subcomps.SineWaveLine
import com.theveloper.pixeltune.presentation.model.RecentlyPlayedSongUiModel
import com.theveloper.pixeltune.presentation.viewmodel.PlayerViewModel
import com.theveloper.pixeltune.ui.theme.ExpTitleTypography
import com.theveloper.pixeltune.ui.theme.GoogleSansRounded
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlaybackHistoryBottomSheet(
    modifier: Modifier = Modifier,
    recentlyPlayedSongs: List<RecentlyPlayedSongUiModel>,
    playerViewModel: PlayerViewModel,
    onSongClick: (Song) -> Unit,
    onClearHistory: () -> Unit
) {
    val fabCornerRadius = 24.dp
    val stablePlayerState by playerViewModel.stablePlayerState.collectAsStateWithLifecycle()

    val groupedSongs = remember(recentlyPlayedSongs) {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

        recentlyPlayedSongs.groupBy {
            val date = Instant.ofEpochMilli(it.lastPlayedTimestamp)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
            when (date) {
                today -> "Today"
                yesterday -> "Yesterday"
                else -> date.format(formatter)
            }
        }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 0.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Listening History",
                fontFamily = GoogleSansRounded,
                style = ExpTitleTypography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(16.dp))

            SineWaveLine(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.CenterHorizontally)
                    .height(32.dp)
                    .padding(horizontal = 8.dp)
                    .padding(bottom = 4.dp),
                animate = true,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                alpha = 0.95f,
                strokeWidth = 4.dp,
                amplitude = 4.dp,
                waves = 7.6f,
                phase = 0f
            )

            if (recentlyPlayedSongs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No history yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 140.dp)
                ) {
                    groupedSongs.forEach { (header, items) ->
                        item(key = "header_$header") {
                            Text(
                                text = header,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                            )
                        }
                        
                        items(items, key = { it.song.id + "_" + it.lastPlayedTimestamp }) { item ->
                            val isCurrentSong = item.song.id == stablePlayerState.currentSong?.id
                            EnhancedSongListItem(
                                song = item.song,
                                isPlaying = stablePlayerState.isPlaying && isCurrentSong,
                                isCurrentSong = isCurrentSong,
                                onMoreOptionsClick = { }, // Hide or handle if needed
                                onClick = { onSongClick(item.song) }
                            )
                        }
                    }
                }
            }
        }

        if (recentlyPlayedSongs.isNotEmpty()) {
            LargeExtendedFloatingActionButton(
                onClick = onClearHistory,
                shape = AbsoluteSmoothCornerShape(
                    cornerRadiusBR = fabCornerRadius,
                    smoothnessAsPercentBR = 60,
                    cornerRadiusBL = fabCornerRadius,
                    smoothnessAsPercentBL = 60,
                    cornerRadiusTR = fabCornerRadius,
                    smoothnessAsPercentTR = 60,
                    cornerRadiusTL = fabCornerRadius,
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
                    .padding(horizontal = 24.dp, vertical = 24.dp)
            )
        }

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
}
