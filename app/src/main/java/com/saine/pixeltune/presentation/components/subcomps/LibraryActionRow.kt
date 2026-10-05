package com.saine.pixeltune.presentation.components.subcomps

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Dataset
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.saine.pixeltune.R
import com.saine.pixeltune.data.model.MusicFolder
import com.saine.pixeltune.presentation.viewmodel.PlaylistSourceFilter
import com.saine.pixeltune.ui.theme.GoogleSansRounded
import java.io.File

val defaultShape = RoundedCornerShape(26.dp) // Fallback shape
val genHeight = 42.dp

/**
 * FIX(playlists-filter-flicker): which flavor of the library action row's
 * shared online/offline/all filter affordance is active — the storage filter
 * (Songs / Albums / Artists / Liked) or the playlist-source filter
 * (Playlists). Both flavors render into the SAME single button slot, so
 * switching between their tabs never removes + re-adds the button (the old
 * two-button exit/enter overlap looked like the filter being duplicated).
 */
private enum class FilterButtonFlavor {
    STORAGE,
    PLAYLIST_SOURCE
}

@Composable
fun LibraryActionRow(
    onMainActionClick: () -> Unit,
    iconRotation: Float,
    onSortClick: () -> Unit,
    onLocateClick: () -> Unit = {},
    showSortButton: Boolean,
    showLocateButton: Boolean = false,
    isPlaylistTab: Boolean,
    onImportM3uClick: () -> Unit = {},
    isFoldersTab: Boolean,
    modifier: Modifier = Modifier,
    // Breadcrumb parameters
    currentFolder: MusicFolder?,
    folderRootPath: String,
    folderRootLabel: String,
    onFolderClick: (String) -> Unit,
    onNavigateBack: () -> Unit,
    isShuffleEnabled: Boolean = false,
    // Storage Filter
    showStorageFilterButton: Boolean = false,
    currentStorageFilter: com.saine.pixeltune.data.model.StorageFilter = com.saine.pixeltune.data.model.StorageFilter.ALL,
    onStorageFilterClick: () -> Unit = {},
    // IMPROVE(cloud-playlist-source-filter): the Playlists tab's Local / Cloud
    // source filter — rendered exactly like the storage filter button of the
    // Songs / Albums / Artists / Liked tabs, placed beside the Sort button.
    showPlaylistSourceFilterButton: Boolean = false,
    currentPlaylistSourceFilter: PlaylistSourceFilter = PlaylistSourceFilter.ALL,
    onPlaylistSourceFilterClick: () -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp),
        horizontalArrangement = Arrangement.Absolute.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        AnimatedContent(
            targetState = isFoldersTab,
            label = "ActionRowContent",
            transitionSpec = {
                if (targetState) { // Transition to Folders (Breadcrumbs)
                    slideInVertically { height -> height } + fadeIn() togetherWith
                            slideOutVertically { height -> -height } + fadeOut()
                } else { // Transition to other tabs (Buttons)
                    slideInVertically { height -> -height } + fadeIn() togetherWith
                            slideOutVertically { height -> height } + fadeOut()
                }
            },
            modifier = Modifier.weight(1f)
        ) { isFolders ->
            if (isFolders) {
                Breadcrumbs(
                    currentFolder = currentFolder,
                    rootPath = folderRootPath,
                    rootLabel = folderRootLabel,
                    onFolderClick = onFolderClick,
                    onNavigateBack = onNavigateBack
                )
            } else {
                val newButtonEndCorner by animateDpAsState(
                    targetValue = if (isPlaylistTab) 8.dp else 26.dp,
                    label = "NewButtonEndCorner"
                )
                val importButtonStartCorner by animateDpAsState(
                    targetValue = if (isPlaylistTab) 6.dp else 26.dp,
                    label = "ImportButtonStartCorner"
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Determine button colors based on shuffle state (not for playlist tab)
                    val buttonContainerColor = if (!isPlaylistTab && isShuffleEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.tertiaryContainer
                    }
                    val buttonContentColor = if (!isPlaylistTab && isShuffleEnabled) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    }
                    
                    FilledTonalButton(
                        onClick = onMainActionClick,
                        shape = RoundedCornerShape(
                            topStart = 26.dp, bottomStart = 26.dp,
                            topEnd =  newButtonEndCorner, bottomEnd = newButtonEndCorner
                        ),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = buttonContainerColor,
                            contentColor = buttonContentColor
                        ),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp, pressedElevation = 6.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        modifier = Modifier.height(genHeight)
                    ) {
                        val icon = if (isPlaylistTab) Icons.AutoMirrored.Rounded.PlaylistAdd else Icons.Rounded.Shuffle
                        val text = if (isPlaylistTab) "New" else if (isShuffleEnabled) "Shuffle On" else "Shuffle"
                        val contentDesc = if (isPlaylistTab) "Create New Playlist" else "Shuffle Play"

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = contentDesc,
                                modifier = Modifier.size(20.dp).rotate(iconRotation)
                            )
                            Text(
                                modifier = Modifier.animateContentSize(),
                                text = text,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = isPlaylistTab,
                        enter = fadeIn() + expandHorizontally(
                            expandFrom = Alignment.Start,
                            clip = false, // <— evita el “corte” durante la expansión
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessLow
                            )
                        ),
                        exit = fadeOut() + shrinkHorizontally(
                            shrinkTowards = Alignment.Start,
                            clip = false, // <— evita el “corte” durante la expansión
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMedium
                            )
                        )
                    ) {
                        Row(modifier = Modifier.height(genHeight), verticalAlignment = Alignment.CenterVertically) {
                            Spacer(modifier = Modifier.width(8.dp))

                            FilledTonalButton(
                                onClick = onImportM3uClick,
                                shape = RoundedCornerShape(
                                    topStart = importButtonStartCorner,
                                    bottomStart = importButtonStartCorner,
                                    topEnd = 26.dp,
                                    bottomEnd = 26.dp
                                ),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                ),
                                elevation = ButtonDefaults.buttonElevation(
                                    defaultElevation = 4.dp,
                                    pressedElevation = 6.dp
                                ),
                                contentPadding = PaddingValues(
                                    horizontal = 14.dp,
                                    vertical = 10.dp
                                ),
                                modifier = Modifier.height(genHeight)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.rounded_upload_file_24),
                                        contentDescription = "Import M3U",
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = "Import",
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }


        Spacer(modifier = Modifier.width(8.dp))

        if (showSortButton) {
            val outerCorner = 26.dp

            // IMPROVE(cloud-playlist-source-filter): either filter button
            // (storage or playlist source) occupies the same middle slot, so
            // they share the corner / gap logic. Only one is ever visible at
            // a time (they belong to different tabs).
            val showSourceFilterButton = showStorageFilterButton || showPlaylistSourceFilterButton

            // Logic for Sort Button (Rightmost)
            val sortStartCorner by animateDpAsState(
                targetValue = if (showLocateButton || showSourceFilterButton) 8.dp else outerCorner,
                label = "SortStartCorner"
            )

            // Logic for Filter Button (Middle or Left if Locate hidden)
            // Filter is visible if showStorageFilterButton is true
            val filterEndCorner = 8.dp // Connected to Sort
            val filterStartCorner by animateDpAsState(
                targetValue = if (showLocateButton) 8.dp else outerCorner,
                label = "FilterStartCorner"
            )
            
            // Logic for Locate Button (Leftmost)
            val locateEndCorner = 8.dp // Connected to Filer or Sort

            // Gaps
            // If Filter is shown, gap is between Filter and Sort? OR if we use connected buttons, gap is 4dp between groups or 0dp between connected?
            // Existing code used 4dp gap and 8dp corner. 
            // "SortButtonsGap" was 4dp if showLocateButton else 0dp.
            // If we want "connected" look (segmented), gap should be small (1dp or 2dp) or 0.
            // But existing code uses `4.dp`.
            
            val gapBetweenLocateAndNext by animateDpAsState(
                targetValue = if (showLocateButton) 4.dp else 0.dp,
                label = "GapLocate"
            )
            val gapBetweenFilterAndSort by animateDpAsState(
                targetValue = if (showSourceFilterButton) 4.dp else 0.dp,
                label = "GapFilter"
            )


            Row(verticalAlignment = Alignment.CenterVertically) {
                // Locate Button
                AnimatedVisibility(
                    visible = showLocateButton,
                    enter = slideInHorizontally(initialOffsetX = { it / 2 }) + fadeIn(),
                    exit = slideOutHorizontally(targetOffsetX = { it / 2 }) + fadeOut()
                ) {
                    FilledTonalIconButton(
                        onClick = onLocateClick,
                        shape = RoundedCornerShape(
                            topStart = outerCorner,
                            bottomStart = outerCorner,
                            topEnd = locateEndCorner,
                            bottomEnd = locateEndCorner
                        ),
                        modifier = Modifier.size(genHeight)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MyLocation,
                            contentDescription = "Locate Current Song"
                        )
                    }
                }
                
                Spacer(modifier = Modifier.width(gapBetweenLocateAndNext))

                // FIX(playlists-filter-flicker): the online/offline/all filter
                // affordance used to be TWO sibling AnimatedVisibility blocks
                // (a storage filter button for Songs/Albums/Artists/Liked and a
                // playlist-source filter button for Playlists). Switching
                // between Playlists and any storage-filter tab flipped one
                // block's exit (slide + fade) and the other's enter (slide +
                // fade) at the SAME time: for the whole transition BOTH nearly
                // identical filter buttons were composed and laid out side by
                // side, visually duplicating the filter button and shoving
                // the Sort button sideways before snapping back — even though
                // the button "already exists" on both tabs. The two flavors
                // are now ONE persistent slot: a single AnimatedVisibility
                // (still slide + fade, but only for tabs that genuinely lack
                // the affordance — Folders / Downloads) wrapping a single
                // button whose icon (short crossfade), tooltip and click
                // handler follow the active flavor. Switching Playlists <->
                // Songs/Albums/Artists/Liked therefore keeps the button
                // steadily in place; at most the icon crossfades, which is
                // invisible when both filters sit on the same value (e.g.
                // Dataset for ALL).
                val activeFilterFlavor = when {
                    showPlaylistSourceFilterButton -> FilterButtonFlavor.PLAYLIST_SOURCE
                    showStorageFilterButton -> FilterButtonFlavor.STORAGE
                    else -> null
                }
                // The flavor rendered while the button is on screen. Seeded
                // with the flavor active at first composition (no stale first
                // frame) and updated from a LaunchedEffect (post-composition),
                // so during an EXIT animation the outgoing flavor — its icon
                // AND its click handler — is kept exactly like the old
                // per-button blocks did, instead of morphing toward the
                // hidden state.
                var shownFilterFlavor by remember {
                    mutableStateOf(activeFilterFlavor ?: FilterButtonFlavor.STORAGE)
                }
                LaunchedEffect(activeFilterFlavor) {
                    if (activeFilterFlavor != null) {
                        shownFilterFlavor = activeFilterFlavor
                    }
                }
                val isPlaylistSourceFilter = shownFilterFlavor == FilterButtonFlavor.PLAYLIST_SOURCE
                val filterIcon = if (isPlaylistSourceFilter) {
                    when (currentPlaylistSourceFilter) {
                        PlaylistSourceFilter.ALL -> Icons.Rounded.Dataset
                        PlaylistSourceFilter.LOCAL -> Icons.Rounded.PhoneAndroid
                        PlaylistSourceFilter.CLOUD -> Icons.Rounded.Cloud
                    }
                } else {
                    when (currentStorageFilter) {
                        com.saine.pixeltune.data.model.StorageFilter.ALL -> Icons.Rounded.Dataset
                        com.saine.pixeltune.data.model.StorageFilter.ONLINE -> Icons.Rounded.Cloud
                        com.saine.pixeltune.data.model.StorageFilter.OFFLINE -> Icons.Rounded.PhoneAndroid
                    }
                }
                val filterTooltip = if (isPlaylistSourceFilter) {
                    when (currentPlaylistSourceFilter) {
                        PlaylistSourceFilter.ALL -> "All Playlists"
                        PlaylistSourceFilter.LOCAL -> "Local Playlists"
                        PlaylistSourceFilter.CLOUD -> "Cloud Playlists"
                    }
                } else {
                    when (currentStorageFilter) {
                        com.saine.pixeltune.data.model.StorageFilter.ALL -> "All Songs"
                        com.saine.pixeltune.data.model.StorageFilter.ONLINE -> "Online"
                        com.saine.pixeltune.data.model.StorageFilter.OFFLINE -> "Offline"
                    }
                }
                val filterTooltipState = rememberTooltipState()

                AnimatedVisibility(
                    visible = activeFilterFlavor != null,
                    enter = slideInHorizontally(initialOffsetX = { it / 2 }) + fadeIn(),
                    exit = slideOutHorizontally(targetOffsetX = { it / 2 }) + fadeOut()
                ) {
                    @OptIn(ExperimentalMaterial3Api::class)
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                        tooltip = {
                            PlainTooltip {
                                Text(filterTooltip)
                            }
                        },
                        state = filterTooltipState
                    ) {
                        FilledTonalIconButton(
                            onClick = if (isPlaylistSourceFilter) {
                                onPlaylistSourceFilterClick
                            } else {
                                onStorageFilterClick
                            },
                            shape = RoundedCornerShape(
                                topStart = filterStartCorner,
                                bottomStart = filterStartCorner,
                                topEnd = filterEndCorner,
                                bottomEnd = filterEndCorner
                            ),
                            modifier = Modifier.size(genHeight)
                        ) {
                            // Crossfade keyed on the icon vector itself: when
                            // both tabs show the same icon (the common
                            // Dataset/ALL case) nothing animates at all; when
                            // the active filter states differ, the icon melts
                            // into the new one with no layout shift.
                            Crossfade(
                                targetState = filterIcon,
                                animationSpec = tween(durationMillis = 150),
                                label = "FilterButtonIcon"
                            ) { icon ->
                                Icon(
                                    imageVector = icon,
                                    contentDescription = filterTooltip
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.width(gapBetweenFilterAndSort))

                // Sort Button
                FilledTonalIconButton(
                    onClick = onSortClick,
                    shape = RoundedCornerShape(
                        topStart = sortStartCorner,
                        bottomStart = sortStartCorner,
                        topEnd = outerCorner,
                        bottomEnd = outerCorner
                    ),
                    modifier = Modifier.size(genHeight)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Sort,
                        contentDescription = "Sort Options",
                    )
                }
            }
        }
    }
}

@Composable
fun Breadcrumbs(
    currentFolder: MusicFolder?,
    rootPath: String,
    rootLabel: String,
    onFolderClick: (String) -> Unit,
    onNavigateBack: () -> Unit
) {
    val rowState = rememberLazyListState()
    val pathSegments = remember(currentFolder?.path, rootPath, rootLabel) {
        val path = currentFolder?.path ?: rootPath
        val normalizedRoot = rootPath.removeSuffix("/")
        val normalizedPath = path.removeSuffix("/")
        val relativePath = normalizedPath
            .removePrefix(normalizedRoot)
            .removePrefix("/")

        if (!normalizedPath.startsWith(normalizedRoot) || relativePath.isEmpty() || normalizedPath == normalizedRoot) {
            listOf(rootLabel to rootPath)
        } else {
            listOf(rootLabel to rootPath) + relativePath.split("/").scan("") { acc, segment ->
                "$acc/$segment"
            }.drop(1).map {
                val file = File(rootPath, it)
                file.name to file.path
            }
        }
    }

    val showStartFade by remember { derivedStateOf { rowState.canScrollBackward } }
    val showEndFade by remember { derivedStateOf { rowState.canScrollForward } }

    LaunchedEffect(pathSegments.size) {
        if (pathSegments.isNotEmpty()) {
            rowState.animateScrollToItem(pathSegments.lastIndex + 1)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilledTonalIconButton(
            onClick = onNavigateBack,
            modifier = Modifier.size(36.dp),
            enabled = currentFolder != null
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
        }
        Spacer(Modifier.width(8.dp))

        LazyRow(
            state = rowState,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                // 1. Forzamos que el contenido se dibuje en una capa separada.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    // 2. Dibujamos el contenido original (el LazyRow).
                    drawContent()

                    // 3. Dibujamos los gradientes que actúan como "máscaras de borrado".
                    val gradientWidth = 24.dp.toPx()

                    // Máscara para el borde IZQUIERDO
                    if (showStartFade) {
                        drawRect(
                            brush = Brush.horizontalGradient(
                                // Gradiente de transparente a opaco (negro)
                                colors = listOf(Color.Transparent, Color.Black),
                                endX = gradientWidth
                            ),
                            // DstIn mantiene el contenido del LazyRow solo donde esta capa es opaca.
                            blendMode = BlendMode.DstIn
                        )
                    }

                    // Máscara para el borde DERECHO
                    if (showEndFade) {
                        drawRect(
                            brush = Brush.horizontalGradient(
                                // Gradiente de opaco (negro) a transparente
                                colors = listOf(Color.Black, Color.Transparent),
                                startX = this.size.width - gradientWidth
                            ),
                            blendMode = BlendMode.DstIn
                        )
                    }
                }
        ) {
            item { Spacer(modifier = Modifier.width(12.dp)) }

            items(pathSegments.size, key = { pathSegments[it].second }) { index ->
                val (name, path) = pathSegments[index]
                val isLast = index == pathSegments.lastIndex
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                    fontFamily = GoogleSansRounded,
                    color = if (isLast) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(enabled = !isLast) { onFolderClick(path) }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
                if (!isLast) {
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            item { Spacer(modifier = Modifier.width(12.dp)) }
        }
    }
}
