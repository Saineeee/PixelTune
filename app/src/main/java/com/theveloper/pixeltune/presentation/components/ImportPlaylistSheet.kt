package com.theveloper.pixeltune.presentation.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.theveloper.pixeltune.data.playlist.PlaylistImportManager
import com.theveloper.pixeltune.ui.theme.GoogleSansRounded
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

/**
 * IMPROVE 2 — Material 3 import-preview flow for URL playlist imports.
 *
 * Replaces the previous fire-and-forget [ImportPlaylistSheet] (which scraped
 * a Spotify / Apple Music / YouTube URL and immediately committed the first
 * YouTube search hit for each track — see FIX 3 in the worklog).
 *
 * New two-phase flow:
 *  Phase 1 (preview): user pastes URL → [ImportPlaylistViewModel.preview]
 *     scrapes the source and matches each track to its best YouTube result
 *     (with the new Jaccard-similarity matcher), WITHOUT persisting anything.
 *  Phase 2 (commit): user reviews the matches in a Material 3 sheet, deselects
 *     mis-matches, optionally edits the playlist name, and taps Import →
 *     [ImportPlaylistViewModel.commit] persists the selected subset only.
 *
 * UI design choices (matching the app's heavy Material 3 expressive design):
 *  - Full-height ModalBottomSheet with status-bar insets + skipPartiallyExpanded
 *    so the preview feels like a full screen, not a transient sheet.
 *  - Each row is a Card with rounded-corner AbsoluteSmoothCornerShape — same
 *    shape used by StreamingProviderSheet so the cards feel native.
 *  - Selection state is a checkable FilledTonalIconButton (M3 expressive
 *    spec) so the tap target is large and the visual is a clear checkmark
 *    fill when selected, an empty outline when deselected.
 *  - A 4dp LinearProgressIndicator is shown during both phases.
 *  - Error rows (no YouTube match) are visually distinct: muted container,
 *    MusicOff leading icon, and "No match" subtitle — clearly informing the
 *    user that this row will be skipped at commit time.
 *  - Bottom action row: "Cancel" (text button) + "Import N of M" (filled
 *    button, M3 default radius). The Import button's label updates live as
 *    the user toggles rows, and is disabled when nothing is selected.
 */
@HiltViewModel
class ImportPlaylistViewModel @Inject constructor(
    private val importManager: PlaylistImportManager
) : ViewModel() {

    /** Phase 1: are we currently scraping the source URL? */
    private val _isPreviewLoading = MutableStateFlow(false)
    val isPreviewLoading: StateFlow<Boolean> = _isPreviewLoading.asStateFlow()

    /** Phase 2: are we currently persisting the selected matches? */
    private val _isCommitting = MutableStateFlow(false)
    val isCommitting: StateFlow<Boolean> = _isCommitting.asStateFlow()

    /** Surfaced to the UI for toast/snackbar messaging. */
    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    /** Tracks whether the import has fully finished (caller dismisses the sheet). */
    private val _importComplete = MutableStateFlow(false)
    val importComplete: StateFlow<Boolean> = _importComplete.asStateFlow()

    /** The playlist name scraped from the source URL (editable by the user). */
    private val _playlistName = MutableStateFlow("")
    val playlistName: StateFlow<String> = _playlistName.asStateFlow()

    /** The list of preview matches, mutable so the UI can deselect rows. */
    private val _matches = MutableStateFlow<List<PlaylistImportManager.TrackMatch>>(emptyList())
    val matches: StateFlow<List<PlaylistImportManager.TrackMatch>> = _matches.asStateFlow()

    fun previewPlaylist(url: String) {
        if (url.isBlank()) {
            _statusMessage.value = "Please enter a valid URL."
            return
        }
        viewModelScope.launch {
            _isPreviewLoading.value = true
            _statusMessage.value = "Resolving tracks from source..."
            _matches.value = emptyList()
            _playlistName.value = ""
            _importComplete.value = false
            try {
                val result = importManager.previewPlaylist(url)
                if (result.isFailure) {
                    _statusMessage.value = "Error: ${result.exceptionOrNull()?.message}"
                } else {
                    val (name, matches) = result.getOrNull()!!
                    _playlistName.value = name
                    _matches.value = matches
                    _statusMessage.value = null
                }
            } catch (e: Exception) {
                _statusMessage.value = "Error: ${e.message}"
            } finally {
                _isPreviewLoading.value = false
            }
        }
    }

    fun toggleMatch(sourceIndex: Int) {
        val current = _matches.value.toMutableList()
        val idx = current.indexOfFirst { it.sourceIndex == sourceIndex }
        if (idx < 0) return
        val existing = current[idx]
        current[idx] = existing.copy(isSelected = !existing.isSelected)
        _matches.value = current
    }

    fun updatePlaylistName(name: String) {
        _playlistName.value = name
    }

    fun commitImport() {
        viewModelScope.launch {
            _isCommitting.value = true
            _statusMessage.value = "Importing ${_matches.value.count { it.isSelected }} tracks..."
            try {
                val result = importManager.commitPreview(
                    playlistName = _playlistName.value,
                    matches = _matches.value
                )
                if (result.isSuccess) {
                    _statusMessage.value = result.getOrNull()
                    _importComplete.value = true
                } else {
                    _statusMessage.value = "Error: ${result.exceptionOrNull()?.message}"
                }
            } catch (e: Exception) {
                _statusMessage.value = "Error: ${e.message}"
            } finally {
                _isCommitting.value = false
            }
        }
    }

    /** Reset state when the sheet is dismissed. */
    fun reset() {
        _matches.value = emptyList()
        _playlistName.value = ""
        _statusMessage.value = null
        _importComplete.value = false
        _isPreviewLoading.value = false
        _isCommitting.value = false
    }

    // --- Legacy single-shot entry point (kept for any old callers) ---
    private val _legacyIsLoading = MutableStateFlow(false)
    val legacyIsLoading: StateFlow<Boolean> = _legacyIsLoading.asStateFlow()
    fun importPlaylist(url: String) {
        if (url.isBlank()) {
            _statusMessage.value = "Please enter a valid URL."
            return
        }
        viewModelScope.launch {
            _legacyIsLoading.value = true
            _statusMessage.value = "Starting import..."
            try {
                val result = importManager.importPlaylist(url)
                if (result.isSuccess) {
                    _statusMessage.value = result.getOrNull()
                    _importComplete.value = true
                } else {
                    _statusMessage.value = "Error: ${result.exceptionOrNull()?.message}"
                }
            } catch (e: Exception) {
                _statusMessage.value = "Error: ${e.message}"
            } finally {
                _legacyIsLoading.value = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ImportPlaylistSheet(
    onDismiss: () -> Unit,
    viewModel: ImportPlaylistViewModel = hiltViewModel()
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var url by remember { mutableStateOf("") }

    val isPreviewLoading by viewModel.isPreviewLoading.collectAsStateWithLifecycle()
    val isCommitting by viewModel.isCommitting.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    val playlistName by viewModel.playlistName.collectAsStateWithLifecycle()
    val matches by viewModel.matches.collectAsStateWithLifecycle()
    val importComplete by viewModel.importComplete.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Auto-dismiss when import completed (after a brief delay so the success
    // message is visible to the user before the sheet slides away).
    LaunchedEffect(importComplete) {
        if (importComplete) {
            kotlinx.coroutines.delay(1200)
            viewModel.reset()
            onDismiss()
        }
    }

    val isBusy = isPreviewLoading || isCommitting
    val selectedCount = matches.count { it.isSelected }

    ModalBottomSheet(
        onDismissRequest = {
            if (!isBusy) {
                viewModel.reset()
                onDismiss()
            }
        },
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        contentWindowInsets = { BottomSheetDefaults.windowInsets }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (matches.isEmpty()) "Import Playlist" else "Preview",
                        style = MaterialTheme.typography.headlineSmall,
                        fontFamily = GoogleSansRounded,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (matches.isEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Paste a public URL from YouTube, Spotify, or Apple Music.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "$selectedCount of ${matches.size} tracks selected",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (matches.isNotEmpty()) {
                    FilledTonalIconButton(
                        onClick = {
                            viewModel.reset()
                        },
                        enabled = !isBusy,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = "Cancel preview")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // URL input row — only shown in the "enter URL" phase.
            if (matches.isEmpty()) {
                UrlInputRow(
                    url = url,
                    onUrlChange = { url = it },
                    onPreview = { viewModel.previewPlaylist(url.trim()) },
                    isBusy = isBusy
                )

                if (isPreviewLoading) {
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Resolving tracks via YouTube search — this can take a few seconds for large playlists.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                statusMessage?.let { message ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (message.contains("Error", true))
                            MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                }
            } else {
                // Preview phase.
                if (isCommitting) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                }

                PlaylistNameRow(
                    name = playlistName,
                    onNameChange = { viewModel.updatePlaylistName(it) },
                    enabled = !isCommitting
                )

                Spacer(Modifier.height(12.dp))

                PreviewMatchesList(
                    matches = matches,
                    onToggle = { viewModel.toggleMatch(it) },
                    enabled = !isCommitting
                )

                Spacer(Modifier.height(8.dp))

                statusMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (message.contains("Error", true) || message.contains("Import", true))
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                Spacer(Modifier.height(12.dp))

                PreviewActionsRow(
                    selectedCount = selectedCount,
                    total = matches.size,
                    isCommitting = isCommitting,
                    onImport = { viewModel.commitImport() },
                    onCancel = {
                        viewModel.reset()
                    }
                )
            }
        }
    }
}

@Composable
private fun UrlInputRow(
    url: String,
    onUrlChange: (String) -> Unit,
    onPreview: () -> Unit,
    isBusy: Boolean
) {
    Column {
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            label = { Text("Playlist URL") },
            placeholder = { Text("https://...") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent
            ),
            trailingIcon = {
                if (url.isNotEmpty()) {
                    IconButton(onClick = { onUrlChange("") }, enabled = !isBusy) {
                        Icon(Icons.Rounded.Close, contentDescription = "Clear")
                    }
                }
            },
            enabled = !isBusy
        )

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = onPreview,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = CircleShape,
            enabled = !isBusy && url.isNotBlank()
        ) {
            Icon(Icons.Rounded.Download, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Preview",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = GoogleSansRounded,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun PlaylistNameRow(
    name: String,
    onNameChange: (String) -> Unit,
    enabled: Boolean
) {
    OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        label = { Text("Playlist name") },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        singleLine = true,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent
        ),
        enabled = enabled
    )
}

@Composable
private fun PreviewMatchesList(
    matches: List<PlaylistImportManager.TrackMatch>,
    onToggle: (Int) -> Unit,
    enabled: Boolean
) {
    val listState = rememberLazyListState()
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(items = matches, key = { it.sourceIndex }) { match ->
            TrackMatchRow(
                match = match,
                onToggle = { onToggle(match.sourceIndex) },
                enabled = enabled
            )
        }
    }
}

@Composable
private fun TrackMatchRow(
    match: PlaylistImportManager.TrackMatch,
    onToggle: () -> Unit,
    enabled: Boolean
) {
    val isSelected = match.isSelected && match.matchedSong != null
    val hasMatch = match.matchedSong != null
    val cardShape = AbsoluteSmoothCornerShape(
        cornerRadiusTR = 18.dp, cornerRadiusTL = 18.dp,
        cornerRadiusBR = 18.dp, cornerRadiusBL = 18.dp,
        smoothnessAsPercentTR = 60, smoothnessAsPercentTL = 60,
        smoothnessAsPercentBR = 60, smoothnessAsPercentBL = 60
    )
    val containerColor = when {
        !hasMatch -> MaterialTheme.colorScheme.surfaceContainerLowest
        isSelected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = when {
        !hasMatch -> MaterialTheme.colorScheme.onSurfaceVariant
        isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val targetCorner by animateDpAsState(
        targetValue = if (isSelected) 16.dp else 18.dp,
        animationSpec = tween(durationMillis = 220),
        label = "trackRowCorner"
    )
    val animatedShape = AbsoluteSmoothCornerShape(
        cornerRadiusTR = targetCorner, cornerRadiusTL = targetCorner,
        cornerRadiusBR = targetCorner, cornerRadiusBL = targetCorner,
        smoothnessAsPercentTR = 60, smoothnessAsPercentTL = 60,
        smoothnessAsPercentBR = 60, smoothnessAsPercentBL = 60
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled && hasMatch, onClick = onToggle),
        shape = animatedShape,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Leading: album art (matched) or muted icon (no match)
            if (hasMatch && !match.matchedSong!!.albumArtUriString.isNullOrBlank()) {
                SmartImage(
                    model = match.matchedSong!!.albumArtUriString,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    shape = CircleShape,
                    modifier = Modifier.size(44.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (hasMatch) Icons.Rounded.Download else Icons.Rounded.MusicOff,
                        contentDescription = null,
                        tint = contentColor.copy(alpha = 0.6f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                val title = if (hasMatch) match.matchedSong!!.title else match.scrapedTitle
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = contentColor,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val subtitle = if (hasMatch) {
                    match.matchedSong!!.displayArtist
                } else {
                    match.scrapedArtist.ifBlank { "Unknown artist" }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (hasMatch) {
                    Text(
                        text = match.matchReason,
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.55f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else if (match.matchReason.isNotBlank()) {
                    Text(
                        text = "No YouTube match — will be skipped",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Trailing: checkable indicator (only when there's a match to toggle)
            if (hasMatch) {
                FilledTonalIconButton(
                    onClick = onToggle,
                    enabled = enabled,
                    modifier = Modifier.size(40.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = if (isSelected)
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = if (isSelected)
                            MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    if (isSelected) {
                        Icon(Icons.Rounded.Check, contentDescription = "Selected for import")
                    } else {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewActionsRow(
    selectedCount: Int,
    total: Int,
    isCommitting: Boolean,
    onImport: () -> Unit,
    onCancel: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = onCancel,
            enabled = !isCommitting,
            modifier = Modifier.weight(1f).height(52.dp),
            shape = CircleShape
        ) {
            Text(
                text = "Cancel",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = GoogleSansRounded,
                fontWeight = FontWeight.SemiBold
            )
        }
        Button(
            onClick = onImport,
            enabled = !isCommitting && selectedCount > 0,
            modifier = Modifier.weight(1.4f).height(52.dp),
            shape = CircleShape
        ) {
            Icon(Icons.Rounded.Download, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Import $selectedCount of $total",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = GoogleSansRounded,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
