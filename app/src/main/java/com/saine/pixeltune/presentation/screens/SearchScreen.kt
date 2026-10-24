package com.saine.pixeltune.presentation.screens

import com.saine.pixeltune.presentation.navigation.navigateSafely
import com.saine.pixeltune.presentation.components.ToggleSegmentButton

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.saine.pixeltune.data.model.Album
import com.saine.pixeltune.data.model.Artist
import com.saine.pixeltune.data.model.CloudArtist
import com.saine.pixeltune.data.model.CloudPlaylist
import com.saine.pixeltune.data.model.Playlist
import com.saine.pixeltune.data.model.SearchFilterType
import com.saine.pixeltune.data.model.SearchHistoryItem
import com.saine.pixeltune.data.model.SearchResultItem
import com.saine.pixeltune.data.model.Song
import com.saine.pixeltune.presentation.components.SmartImage
import com.saine.pixeltune.presentation.components.ShimmerBox
import com.saine.pixeltune.presentation.components.SongInfoBottomSheet
import com.saine.pixeltune.presentation.viewmodel.PlayerViewModel
import android.util.Log
import com.saine.pixeltune.ui.theme.LocalPixelTuneDarkTheme
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import com.saine.pixeltune.R
import com.saine.pixeltune.data.repository.MusicRepository
import com.saine.pixeltune.presentation.components.MiniPlayerHeight
import com.saine.pixeltune.presentation.components.MiniPlayerBottomSpacer
import com.saine.pixeltune.presentation.components.NavBarContentHeight
import com.saine.pixeltune.presentation.components.PlaylistBottomSheet
import com.saine.pixeltune.presentation.components.PlaylistCover
import com.saine.pixeltune.presentation.navigation.Screen
import com.saine.pixeltune.presentation.screens.search.components.GenreCategoriesGrid
import com.saine.pixeltune.presentation.viewmodel.PlaylistViewModel
import com.saine.pixeltune.utils.SearchResultKeys
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape
import timber.log.Timber
import com.saine.pixeltune.presentation.components.subcomps.EnhancedSongListItem


/**
 * IMPROVE(search-history): the three landing modes of the Search screen body.
 *
 *  - ONLINE_HISTORY — blank query while in Online mode: shows the recent
 *    online search history (per-entry remove, autocomplete arrow, clear all).
 *  - GENRE_BROWSE   — blank query while in Local mode: the genre grid.
 *  - SUGGESTIONS     — IMPROVE(online-search-autocomplete): a query is being
 *    TYPED in Online mode and has not been committed yet: live autocomplete
 *    suggestions from the active provider.
 *  - RESULTS        — a query is committed (submitted / suggestion tapped /
 *    history entry) or Local mode is active: filter chips + results.
 */
private enum class SearchBodyMode { GENRE_BROWSE, ONLINE_HISTORY, SUGGESTIONS, RESULTS }

/** IMPROVE(search-loading): what the results area shows. */
private enum class SearchResultsViewState { LOADING, EMPTY, RESULTS }

/**
 * FIX(online-search-chip-crash): a search result paired with its globally-
 * unique LazyColumn key (see [SearchResultKeys.uniqueKeysFor]).
 */
private data class KeyedSearchResult(
    val item: SearchResultItem,
    val key: String
)

/**
 * The results-list section a search result belongs to — cloud catalog
 * entries join the matching section (albums from the YT Music albums index
 * group under Albums, playlists under Playlists, artists under Artists).
 */
private fun sectionForResultItem(item: SearchResultItem): SearchFilterType = when (item) {
    is SearchResultItem.SongItem -> SearchFilterType.SONGS
    is SearchResultItem.AlbumItem -> SearchFilterType.ALBUMS
    is SearchResultItem.ArtistItem -> SearchFilterType.ARTISTS
    is SearchResultItem.PlaylistItem -> SearchFilterType.PLAYLISTS
    is SearchResultItem.CloudPlaylistItem ->
        if (item.playlist.isAlbum) SearchFilterType.ALBUMS else SearchFilterType.PLAYLISTS
    is SearchResultItem.CloudArtistItem -> SearchFilterType.ARTISTS
}


@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SearchScreen(
    paddingValues: PaddingValues,
    playerViewModel: PlayerViewModel = hiltViewModel(),
    playlistViewModel: PlaylistViewModel = hiltViewModel(),
    navController: NavHostController,
    onSearchBarActiveChange: (Boolean) -> Unit = {}
) {
    var searchQuery by rememberSaveable { mutableStateOf(playerViewModel.searchQuery) }
    // IMPROVE(online-search-autocomplete): the query the CURRENT results
    // belong to. In Online mode typing no longer fires a full provider search
    // on every keystroke — while typing, the body shows autocomplete
    // suggestions, and the full search runs only when a query is COMMITTED
    // (keyboard Search action, suggestion tap, or history entry — all of
    // which set `submittedQuery == searchQuery`). `null` means the user is
    // still typing (or cleared the field). Local mode keeps its as-you-type
    // search regardless of this value.
    var submittedQuery by rememberSaveable { mutableStateOf<String?>(null) }
    val systemNavBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomBarHeightDp = NavBarContentHeight + systemNavBarInset
    var showPlaylistBottomSheet by remember { mutableStateOf(false) }
    val uiState by playerViewModel.playerUiState.collectAsStateWithLifecycle()
    val currentFilter by remember { derivedStateOf { uiState.selectedSearchFilter } }
    val isOnlineSearch by remember { derivedStateOf { uiState.isOnlineSearch } }
    val genres by playerViewModel.genres.collectAsStateWithLifecycle()
    val stablePlayerState by playerViewModel.stablePlayerState.collectAsStateWithLifecycle()
    val favoriteSongIds by playerViewModel.favoriteSongIds.collectAsStateWithLifecycle()
    var showSongInfoBottomSheet by remember { mutableStateOf(false) }
    var selectedSongForInfo by remember { mutableStateOf<Song?>(null) }
    // IMPROVE(clear-search-history-confirm): tapping "Clear All" on the
    // recent-searches landing used to wipe the whole online search history
    // instantly — one accidental tap and every recent query was gone. The
    // button now opens a confirmation dialog first (same Material 3
    // AlertDialog pattern the Listening History "Clear" action already
    // uses) and only wipes the history when the user confirms.
    var showClearSearchHistoryDialog by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val searchInputFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        onSearchBarActiveChange(false)
    }

    LaunchedEffect(playerViewModel, keyboardController) {
        playerViewModel.searchNavDoubleTapEvents.collect {
            delay(40L)
            searchInputFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    // IMPROVE(cloud-playlist-import): surface the outcome of adding an online
    // playlist to the library (success / already-added / failure) through the
    // app's global toast channel.
    LaunchedEffect(playlistViewModel) {
        playlistViewModel.cloudPlaylistImportEvents.collect { message ->
            playerViewModel.sendToast(message)
        }
    }

    // Search debouncing is centralized in SearchStateHolder.
    //
    // IMPROVE(online-search-autocomplete): in ONLINE mode typing no longer
    // fires the full (debounced) provider search on every keystroke — it only
    // fetches autocomplete suggestions, so a full music search and an
    // autocomplete request never race for every keystroke. The full search
    // pipeline runs when the query is COMMITTED: keyboard Search action,
    // suggestion tap, or history entry (all of which set
    // `submittedQuery == searchQuery`), and when the filter chip changes on
    // committed results. LOCAL mode keeps the existing as-you-type MediaStore
    // search. Mode switches re-run this effect so the appropriate pipeline
    // (local search / online suggestions / online re-search) always owns the
    // body, and stale suggestions from an earlier state never survive a
    // mode or provider change (SearchStateHolder invalidates those itself).
    LaunchedEffect(searchQuery, currentFilter, isOnlineSearch, submittedQuery) {
        when {
            !isOnlineSearch -> playerViewModel.performSearch(searchQuery)
            searchQuery.isBlank() -> {
                // Back on the recent-searches landing: no results, no
                // suggestions, nothing in flight.
                playerViewModel.performSearch(searchQuery)
                playerViewModel.clearSearchSuggestions()
            }
            submittedQuery == searchQuery -> playerViewModel.performSearch(searchQuery)
            else -> playerViewModel.requestSearchSuggestions(searchQuery)
        }
    }
    val searchResults = uiState.searchResults

    // IMPROVE(search-loading): true only while an ONLINE search request is in
    // flight — drives the expressive Material 3 loading indicator below.
    val isSearchingOnline by remember { derivedStateOf { uiState.isSearching && isOnlineSearch } }

    // IMPROVE(search-history): recent-search state + actions for the Online
    // landing. Refreshed whenever the online landing becomes visible so the
    // list always reflects the latest submits / deletions.
    val searchHistory = uiState.searchHistory
    // IMPROVE(online-search-autocomplete): live suggestions + loading state
    // for the Online typing panel.
    val searchSuggestions = uiState.searchSuggestions
    val isLoadingSearchSuggestions = uiState.isLoadingSearchSuggestions

    LaunchedEffect(isOnlineSearch, searchQuery.isNotBlank()) {
        if (isOnlineSearch && searchQuery.isBlank()) {
            playerViewModel.loadSearchHistory()
        }
    }
    val onSuggestionSelected: (String) -> Unit = { suggestion ->
        // IMPROVE(online-search-autocomplete): tapping a suggestion COMMITS
        // it — the field text, the search-query state, the history entry and
        // the FULL online search are driven by the single ViewModel call,
        // then the keyboard and the suggestion panel hide and the existing
        // results interface (filters + pagination) takes over.
        searchQuery = suggestion
        playerViewModel.selectSearchSuggestion(suggestion)
        submittedQuery = suggestion
        keyboardController?.hide()
    }
    val onHistoryQuerySelected: (String) -> Unit = { query ->
        searchQuery = query
        playerViewModel.updateSearchQuery(query)
        // Re-record so the tapped entry bubbles back to the top of recents.
        playerViewModel.onSearchQuerySubmitted(query)
        // IMPROVE(online-search-autocomplete): selecting a history entry is a
        // COMMITTED search — show the results, never the suggestion panel.
        submittedQuery = query
        playerViewModel.clearSearchSuggestions()
        keyboardController?.hide()
    }
    val onHistoryQueryFill: (String) -> Unit = { query ->
        searchQuery = query
        playerViewModel.updateSearchQuery(query)
        // IMPROVE(online-search-autocomplete): filling an entry is EDITING,
        // not submitting — the field gets focus, the keyboard opens, and (in
        // Online mode) fresh autocomplete suggestions arrive for the query.
        submittedQuery = null
        searchInputFocusRequester.requestFocus()
        keyboardController?.show()
    }
    val onDeleteHistoryEntry: (String) -> Unit = { query ->
        playerViewModel.deleteSearchHistoryItem(query)
    }
    val onClearHistory: () -> Unit = {
        showClearSearchHistoryDialog = true
    }

    val handleSongMoreOptionsClick: (Song) -> Unit = { song ->
        selectedSongForInfo = song
        playerViewModel.selectSongForInfo(song)
        showSongInfoBottomSheet = true
    }

    val searchbarCornerRadius = 28.dp

    val dm = LocalPixelTuneDarkTheme.current

    val gradientColorsDark = listOf(
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
        Color.Transparent
    ).toImmutableList()

    val gradientColorsLight = listOf(
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f),
        Color.Transparent
    ).toImmutableList()

    val gradientColors = if (dm) gradientColorsDark else gradientColorsLight

    val gradientBrush = remember(gradientColors) {
        Brush.verticalGradient(colors = gradientColors)
    }

    val colorScheme = MaterialTheme.colorScheme

    DisposableEffect(Unit) {
        onDispose {
            onSearchBarActiveChange(false)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .background(
                    gradientBrush
                )
        )

        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            ) {
                val searchBarInputFieldColors = SearchBarDefaults.inputFieldColors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary
                )

                SearchBar(
                    inputField = {
                        SearchBarDefaults.InputField(
                            modifier = Modifier.focusRequester(searchInputFocusRequester),
                            query = searchQuery,
                            onQueryChange = {
                                searchQuery = it
                                playerViewModel.updateSearchQuery(it)
                                // IMPROVE(online-search-autocomplete): every
                                // edit returns the field to the TYPING state
                                // — suggestions (not results) own the body
                                // until the query is committed again.
                                submittedQuery = null
                            },
                            onSearch = { query ->
                                if (query.isNotBlank()) {
                                    playerViewModel.onSearchQuerySubmitted(query)
                                    // IMPROVE(online-search-autocomplete): the
                                    // keyboard's Search action COMMITS the
                                    // typed query — even when suggestions never
                                    // loaded or failed — and the normal full
                                    // online search runs for it via the
                                    // query-commit LaunchedEffect above.
                                    submittedQuery = query
                                }
                                keyboardController?.hide()
                            },
                            expanded = false,
                            onExpandedChange = {},
                            placeholder = {
                                Text(
                                    "Search...",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Search,
                                    contentDescription = "Buscar",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            },
                            trailingIcon = {
                                if (searchQuery.isNotBlank()) {
                                    IconButton(
                                        onClick = {
                                            searchQuery = ""
                                            playerViewModel.updateSearchQuery("")
                                            // IMPROVE(online-search-autocomplete):
                                            // clearing the field un-commits any
                                            // previous search — the
                                            // recent-searches landing takes over.
                                            submittedQuery = null
                                        },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .padding(end = 10.dp)
                                            .clip(CircleShape)
                                            .background(
                                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                                            )
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = "Limpiar",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            },
                            colors = searchBarInputFieldColors
                        )
                    },
                    expanded = false,
                    onExpandedChange = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(searchbarCornerRadius)),
                    colors = SearchBarDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                        dividerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                        inputFieldColors = searchBarInputFieldColors
                    ),
                    content = {}
                )
            }

            // IMPROVE(search-history): the Local/Online toggle now lives ABOVE
            // the animated body so the user can pick a mode BEFORE typing —
            // Online + blank query lands on the recent-searches history,
            // Local + blank query lands on the genre browse grid.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp)
                    .height(48.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                ) {
                    ToggleSegmentButton(
                        modifier = Modifier.fillMaxSize(),
                        active = !isOnlineSearch,
                        activeColor = MaterialTheme.colorScheme.primary,
                        inactiveColor = MaterialTheme.colorScheme.surfaceVariant,
                        activeContentColor = MaterialTheme.colorScheme.onPrimary,
                        inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        activeCornerRadius = 24.dp,
                        onClick = { playerViewModel.toggleSearchMode(false) },
                        text = "Local"
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                ) {
                    ToggleSegmentButton(
                        modifier = Modifier.fillMaxSize(),
                        active = isOnlineSearch,
                        activeColor = MaterialTheme.colorScheme.primary,
                        inactiveColor = MaterialTheme.colorScheme.surfaceVariant,
                        activeContentColor = MaterialTheme.colorScheme.onPrimary,
                        inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        activeCornerRadius = 24.dp,
                        onClick = { playerViewModel.toggleSearchMode(true) },
                        text = "Online"
                    )
                }
            }

            // IMPROVE(search-history) + IMPROVE(online-search-autocomplete):
            // four-way landing mode — autocomplete suggestions while a query
            // is being TYPED in Online mode, history when the query is blank
            // and Online is selected, genre grid when blank and Local is
            // selected, and the search results UI for committed online
            // queries and for Local searches.
            val searchBodyMode by remember(searchQuery, isOnlineSearch, submittedQuery) {
                derivedStateOf {
                    when {
                        searchQuery.isNotBlank() && submittedQuery == searchQuery ->
                            SearchBodyMode.RESULTS
                        searchQuery.isNotBlank() && isOnlineSearch ->
                            SearchBodyMode.SUGGESTIONS
                        searchQuery.isNotBlank() -> SearchBodyMode.RESULTS
                        isOnlineSearch -> SearchBodyMode.ONLINE_HISTORY
                        else -> SearchBodyMode.GENRE_BROWSE
                    }
                }
            }
            AnimatedContent(
                targetState = searchBodyMode,
                transitionSpec = {
                    val switchingToGenre = targetState == SearchBodyMode.GENRE_BROWSE
                    val enter = fadeIn(animationSpec = tween(durationMillis = 320, delayMillis = 70)) +
                        slideInVertically(animationSpec = tween(durationMillis = 320)) { fullHeight ->
                            if (switchingToGenre) -fullHeight / 10 else fullHeight / 10
                        }
                    val exit = fadeOut(animationSpec = tween(durationMillis = 220)) +
                        slideOutVertically(animationSpec = tween(durationMillis = 220)) { fullHeight ->
                            if (switchingToGenre) fullHeight / 12 else -fullHeight / 12
                        }
                    (enter togetherWith exit).using(SizeTransform(clip = false))
                },
                label = "search_mode_transition"
            ) { bodyMode ->
                when (bodyMode) {
                    SearchBodyMode.GENRE_BROWSE -> {
                        Box(modifier = Modifier.fillMaxSize()) {
                            GenreCategoriesGrid(
                                genres = genres,
                                onGenreClick = { genre ->
                                    Timber.tag("SearchScreen")
                                        .d("Genre clicked: ${genre.name} (ID: ${genre.id})")
                                    val encodedGenreId = java.net.URLEncoder.encode(genre.id, "UTF-8")
                                    navController.navigateSafely(Screen.GenreDetail.createRoute(encodedGenreId))
                                },
                                playerViewModel = playerViewModel,
                                modifier = Modifier.padding(top = 12.dp)
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.BottomCenter)
                                    .height(80.dp)
                                    .background(
                                        brush = Brush.verticalGradient(
                                            colors = listOf(
                                                Color.Transparent,
                                                MaterialTheme.colorScheme.surfaceContainerLowest.copy(
                                                    0.5f
                                                ),
                                                MaterialTheme.colorScheme.surfaceContainerLowest
                                            )
                                        )
                                    )
                            )
                        }
                    }

                    SearchBodyMode.ONLINE_HISTORY -> {
                        OnlineSearchHistorySection(
                            historyItems = searchHistory,
                            onQuerySelected = onHistoryQuerySelected,
                            onQueryFill = onHistoryQueryFill,
                            onDeleteEntry = onDeleteHistoryEntry,
                            onClearAll = onClearHistory,
                            currentPlayingSongId = stablePlayerState.currentSong?.id
                        )
                    }

                    // IMPROVE(online-search-autocomplete): the Online TYPING
                    // body — live autocomplete suggestions from the active
                    // provider, directly below the search field + mode toggle.
                    SearchBodyMode.SUGGESTIONS -> {
                        OnlineSearchSuggestionsSection(
                            suggestions = searchSuggestions,
                            isLoading = isLoadingSearchSuggestions,
                            onSuggestionSelected = onSuggestionSelected,
                            currentPlayingSongId = stablePlayerState.currentSong?.id
                        )
                    }

                    SearchBodyMode.RESULTS -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp)
                        ) {
                            FlowRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                SearchFilterChip(SearchFilterType.ALL, currentFilter, playerViewModel)
                                SearchFilterChip(SearchFilterType.SONGS, currentFilter, playerViewModel)
                                SearchFilterChip(SearchFilterType.ALBUMS, currentFilter, playerViewModel)
                                SearchFilterChip(SearchFilterType.ARTISTS, currentFilter, playerViewModel)
                                SearchFilterChip(SearchFilterType.PLAYLISTS, currentFilter, playerViewModel)
                            }

                            // IMPROVE(search-loading): the results area now has
                            // three states — an expressive Material 3 loading
                            // indicator while the online search is in flight,
                            // the empty state, and the results list.
                            val resultsViewState = when {
                                isSearchingOnline -> SearchResultsViewState.LOADING
                                searchResults.isEmpty() -> SearchResultsViewState.EMPTY
                                else -> SearchResultsViewState.RESULTS
                            }
                            Crossfade(
                                targetState = resultsViewState,
                                animationSpec = tween(durationMillis = 190),
                                label = "search_results_fade"
                            ) { state ->
                                when (state) {
                                    SearchResultsViewState.LOADING -> OnlineSearchLoadingIndicator(
                                        searchQuery = searchQuery
                                    )

                                    SearchResultsViewState.EMPTY -> EmptySearchResults(
                                        searchQuery = searchQuery,
                                        colorScheme = colorScheme
                                    )

                                    SearchResultsViewState.RESULTS -> SearchResultsList(
                                        results = searchResults,
                                        playerViewModel = playerViewModel,
                                        onItemSelected = {
                                            if (searchQuery.isNotBlank()) {
                                                playerViewModel.onSearchQuerySubmitted(searchQuery)
                                            }
                                        },
                                        currentPlayingSongId = stablePlayerState.currentSong?.id,
                                        isPlaying = stablePlayerState.isPlaying,
                                        onSongMoreOptionsClick = handleSongMoreOptionsClick,
                                        navController = navController,
                                        // IMPROVE(search-load-more): pagination of
                                        // the current ONLINE search — the "Load
                                        // more" row shows for every filter chip.
                                        hasMoreResults = isOnlineSearch && uiState.hasMoreSearchResults,
                                        isLoadingMoreResults = isOnlineSearch && uiState.isLoadingMoreSearchResults,
                                        onLoadMoreResults = { playerViewModel.loadMoreSearchResults() },
                                        // IMPROVE(cloud-playlist-import): the
                                        // "Add to your playlist" action on
                                        // online playlist rows.
                                        playlistViewModel = playlistViewModel
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // IMPROVE(clear-search-history-confirm): Material 3 confirmation dialog
    // guarding the destructive "Clear All" action on the recent-searches
    // landing. Mirrors the Listening History clear dialog (title + body +
    // Cancel/Confirm TextButtons) so both destructive history actions feel
    // identical across the app.
    if (showClearSearchHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearSearchHistoryDialog = false },
            title = { Text(text = "Clear search history?") },
            text = {
                Text(
                    text = "This removes all ${searchHistory.size} recent searches. " +
                        "This action can't be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearSearchHistoryDialog = false
                        playerViewModel.clearSearchHistory()
                    }
                ) {
                    Text(text = "Clear")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearSearchHistoryDialog = false }
                ) {
                    Text(text = "Cancel")
                }
            }
        )
    }

    if (showSongInfoBottomSheet && selectedSongForInfo != null) {
        val currentSong = selectedSongForInfo
        val isFavorite = remember(currentSong?.id, favoriteSongIds) {
            derivedStateOf {
                currentSong?.let { favoriteSongIds.contains(it.id) }
            }
        }.value ?: false
        val removeFromListTrigger = remember(currentSong) {
            {
                // IMPROVE(online-search-autocomplete): the space-append still
                // forces a query CHANGE (re-running the search without the
                // song that was just deleted from the device) — but the
                // refreshed query is also COMMITTED so the results stay on
                // screen instead of dropping back to the suggestion panel
                // mid-flow.
                val refreshedQuery = "$searchQuery "
                searchQuery = refreshedQuery
                submittedQuery = refreshedQuery
            }
        }

        if (currentSong != null) {
            // IMPROVE(offline-downloads): live download state for cloud search
            // results opened through this sheet.
            val downloadedSongs by playerViewModel.downloadedSongs.collectAsStateWithLifecycle()
            val downloadStates by playerViewModel.downloadStates.collectAsStateWithLifecycle()
            val downloadStatus = com.saine.pixeltune.data.downloads.songDownloadStatus(
                song = currentSong,
                isCloud = playerViewModel.isSongCloudStreamed(currentSong),
                downloaded = downloadedSongs,
                states = downloadStates
            )
            SongInfoBottomSheet(
                song = currentSong,
                isFavorite = isFavorite,
                removeFromListTrigger = removeFromListTrigger,
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
                    showPlaylistBottomSheet = true;
                },
                onDeleteFromDevice = playerViewModel::deleteFromDevice,
                onNavigateToAlbum = {
                    navController.navigateSafely(Screen.AlbumDetail.createRoute(currentSong.albumId))
                    showSongInfoBottomSheet = false
                },
                onNavigateToArtist = {
                    navController.navigateSafely(Screen.ArtistDetail.createRoute(currentSong.artistId))
                    showSongInfoBottomSheet = false
                },
                onEditSong = { newTitle, newArtist, newAlbum, newGenre, newLyrics, newTrackNumber, coverArtUpdate ->
                    playerViewModel.editSongMetadata(
                        currentSong,
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
                    songs = listOf(currentSong),
                    onDismiss = { showPlaylistBottomSheet = false },
                    bottomBarHeight = bottomBarHeightDp,
                    playerViewModel = playerViewModel,
                )
            }
        }
    }
}

@Composable
fun SearchResultSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp, horizontal = 4.dp)
    )
}

/**
 * IMPROVE(search-history): the Online search landing — recent searches with a
 * per-entry remove action, an autocomplete arrow that fills the entry into
 * the search bar, and a master clear-all button. Styled after the app's
 * existing M3 expressive cards (AbsoluteSmoothCornerShape / surfaceContainerLow)
 * so it matches the search results list it sits above.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineSearchHistorySection(
    historyItems: List<SearchHistoryItem>,
    onQuerySelected: (String) -> Unit,
    onQueryFill: (String) -> Unit,
    onDeleteEntry: (String) -> Unit,
    onClearAll: () -> Unit,
    // FIX(search-history-bottom-padding): whether the mini player overlay is
    // visible — the list must reserve room for it so the bottom-most (oldest)
    // search entry is not hidden behind it.
    currentPlayingSongId: String? = null,
    modifier: Modifier = Modifier
) {
    val systemBarPaddingBottom =
        WindowInsets.systemBars.asPaddingValues().calculateBottomPadding() + 94.dp

    // FIX(search-history-bottom-padding): the history list used to reserve
    // only `systemBars.bottom + 94.dp`, which is roughly one history row
    // SHORT of the real bottom overlay (app navigation bar + mini player +
    // its spacer). After a significant number of searches, the oldest entry
    // at the bottom sat underneath the mini player — impossible to see or
    // tap. Reserve the REAL overlay exactly like the sibling search
    // RESULTS list (and the library tabs): nav bar content + system inset,
    // plus the mini player and its spacer when a song is loaded, with a bit
    // of breathing room so the last row is comfortably tappable. The legacy
    // reservation is kept as a floor for tall-overlay devices.
    val miniPlayerVisible = currentPlayingSongId != null
    val bottomOverlay = if (miniPlayerVisible) {
        bottomBarHeightDpForResults() + MiniPlayerHeight + MiniPlayerBottomSpacer + 24.dp
    } else {
        bottomBarHeightDpForResults() + 24.dp
    }
    val legacyBottomPadding = MiniPlayerHeight + systemBarPaddingBottom
    val historyBottomPadding = if (bottomOverlay > legacyBottomPadding) bottomOverlay else legacyBottomPadding

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Recent Searches",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            if (historyItems.isNotEmpty()) {
                TextButton(
                    onClick = onClearAll,
                    contentPadding = PaddingValues(horizontal = 10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteSweep,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Clear All")
                }
            }
        }

        if (historyItems.isEmpty()) {
            // FIX(search-history-empty-center): the "No recent searches"
            // placeholder used to sit directly under the "Recent Searches"
            // header (top-heavy — it read as clipped/awkwardly high on the
            // screen). It now centers vertically in the space between the
            // header and the bottom overlay (app navigation bar + mini player
            // pill when a song is loaded), i.e. the middle of the VISIBLE
            // body area — matching how the sibling empty states (search
            // results, listening history) are centered.
            val emptyStateBottomInset = if (miniPlayerVisible) {
                bottomBarHeightDpForResults() + MiniPlayerHeight + MiniPlayerBottomSpacer
            } else {
                bottomBarHeightDpForResults()
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(bottom = emptyStateBottomInset),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Rounded.History,
                        contentDescription = "No recent searches",
                        modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No recent searches",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Your online searches will show up here",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(
                    top = 4.dp,
                    bottom = historyBottomPadding
                )
            ) {
                items(
                    historyItems,
                    key = { "history_${it.id ?: it.query}" }
                ) { item ->
                    OnlineSearchHistoryRow(
                        item = item,
                        onClick = { onQuerySelected(item.query) },
                        onFillClick = { onQueryFill(item.query) },
                        onDeleteClick = { onDeleteEntry(item.query) }
                    )
                }
            }
        }
    }
}

/**
 * A single recent-search row: tapping it searches the query again, the
 * arrow button fills it into the search bar for editing, and the close
 * button removes that single entry from the history.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnlineSearchHistoryRow(
    item: SearchHistoryItem,
    onClick: () -> Unit,
    onFillClick: () -> Unit,
    onDeleteClick: () -> Unit
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
        onClick = onClick,
        shape = rowShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.History,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = item.query,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            // Autocomplete arrow — fills this entry into the search bar.
            IconButton(onClick = onFillClick, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.CallMade,
                    contentDescription = "Fill search bar",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            // Remove this single entry from the search history.
            IconButton(onClick = onDeleteClick, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Remove from search history",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

/**
 * IMPROVE(online-search-autocomplete): the Online TYPING body — live
 * autocomplete suggestions from the active provider, shown while a query is
 * being typed and has not been committed yet. Mirrors the recent-searches
 * section's M3 expressive styling (smooth-corner cards on
 * surfaceContainerLow) so the typing panel reads as the same surface the
 * history and results lists use.
 *
 * Three visual states, all restrained by design:
 *  - suggestions present -> the list (rows animate subtly as the provider
 *    list changes under the user's typing),
 *  - none yet + loading   -> a single small progress indicator that never
 *    blocks text input,
 *  - none + not loading   -> nothing (the query stays intact and the
 *    keyboard's Search action still runs the full search).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineSearchSuggestionsSection(
    suggestions: List<String>,
    isLoading: Boolean,
    onSuggestionSelected: (String) -> Unit,
    // Reserve room for the bottom overlay (app navigation bar + mini player)
    // exactly like the history / results lists do, so no row hides behind it
    // when the keyboard is closed.
    currentPlayingSongId: String? = null,
    modifier: Modifier = Modifier
) {
    val localDensity = LocalDensity.current

    val miniPlayerVisible = currentPlayingSongId != null
    val bottomOverlay = if (miniPlayerVisible) {
        bottomBarHeightDpForResults() + MiniPlayerHeight + MiniPlayerBottomSpacer + 24.dp
    } else {
        bottomBarHeightDpForResults() + 24.dp
    }
    val systemBarPaddingBottom =
        WindowInsets.systemBars.asPaddingValues().calculateBottomPadding() + 94.dp
    val legacyBottomPadding = MiniPlayerHeight + systemBarPaddingBottom
    val suggestionsBottomPadding =
        if (bottomOverlay > legacyBottomPadding) bottomOverlay else legacyBottomPadding

    // The list must stay usable with the software keyboard open: while the
    // IME is visible its inset replaces the bottom-overlay padding (that
    // overlay sits BEHIND the keyboard), keeping every row scrollable to
    // above the keyboard — the same policy the search results list applies.
    val imePadding = WindowInsets.ime.getBottom(localDensity).dp

    when {
        suggestions.isEmpty() && isLoading -> {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .padding(top = 32.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 2.5.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        suggestions.isNotEmpty() -> {
            LazyColumn(
                modifier = modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(
                    top = 4.dp,
                    bottom = if (imePadding <= 8.dp) suggestionsBottomPadding else imePadding
                )
            ) {
                items(
                    suggestions,
                    key = { "suggestion_$it" }
                ) { suggestion ->
                    SearchSuggestionRow(
                        suggestion = suggestion,
                        onClick = { onSuggestionSelected(suggestion) },
                        // Subtle placement animation when the provider's list
                        // changes under the user's typing.
                        modifier = Modifier.animateItem()
                    )
                }
            }
        }

        else -> {
            // No suggestions (query below the provider's radar, or the fetch
            // failed): render nothing — the typed query stays intact and the
            // keyboard's Search action still runs the full search.
        }
    }
}

/**
 * IMPROVE(online-search-autocomplete): a single autocomplete row — leading
 * search icon + the provider's suggested query. Tapping it commits the
 * suggestion (fills the field, records history and runs the full online
 * search). Same smooth-corner card styling the recent-searches rows use so
 * the two lists feel like one surface.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchSuggestionRow(
    suggestion: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
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
        onClick = onClick,
        shape = rowShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = suggestion,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * IMPROVE(search-loading): expressive Material 3 loading state shown while an
 * online search is in flight. Uses the same M3 expressive [LoadingIndicator]
 * the full player uses, plus shimmering skeleton rows that mirror the shape
 * of the incoming result rows — matching the app's existing animation
 * language instead of a plain spinner.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OnlineSearchLoadingIndicator(
    searchQuery: String,
    modifier: Modifier = Modifier
) {
    val skeletonShape = remember {
        RoundedCornerShape(20.dp)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(40.dp))
        LoadingIndicator(
            modifier = Modifier.size(36.dp),
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = if (searchQuery.isNotBlank()) {
                "Searching for \"$searchQuery\""
            } else {
                "Searching"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Fetching the best matches for you",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(28.dp))
        repeat(5) {
            ShimmerBox(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(76.dp)
                    .clip(skeletonShape)
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}


@Composable
fun EmptySearchResults(searchQuery: String, colorScheme: ColorScheme) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = "No results",
            modifier = Modifier
                .size(80.dp)
                .padding(bottom = 16.dp),
            tint = colorScheme.primary.copy(alpha = 0.6f)
        )

        Text(
            text = if (searchQuery.isNotBlank()) "No results for \"$searchQuery\"" else "Nothing found",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Try a different search term or check your filters.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
    }
}


@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun SearchResultsList(
    results: List<SearchResultItem>,
    playerViewModel: PlayerViewModel,
    onItemSelected: () -> Unit,
    currentPlayingSongId: String?,
    isPlaying: Boolean,
    onSongMoreOptionsClick: (Song) -> Unit,
    navController: NavHostController,
    // IMPROVE(search-load-more): pagination state of the current ONLINE
    // search — a "Load more" row renders at the bottom of the results when
    // the provider has another page (available on EVERY filter chip).
    hasMoreResults: Boolean = false,
    isLoadingMoreResults: Boolean = false,
    onLoadMoreResults: () -> Unit = {},
    // IMPROVE(cloud-playlist-import): the "Add to your playlist" action for
    // online playlist rows.
    playlistViewModel: PlaylistViewModel? = null
) {
    val localDensity = LocalDensity.current
    val playerStableState by playerViewModel.stablePlayerState.collectAsStateWithLifecycle()
    val allSongs by playerViewModel.allSongsFlow.collectAsStateWithLifecycle()

    // IMPROVE(cloud-playlist-import): imported-cloud-playlist state, collected
    // as Compose state so the "Added" checkmark appears the MOMENT an import
    // completes (a plain function read of the ViewModel's StateFlow value
    // would not recompose these rows).
    val importedCloudPlaylistIds by playlistViewModel?.importedCloudPlaylistIds
        ?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(emptySet<String>()) }
    val importingCloudPlaylistId by playlistViewModel?.importingCloudPlaylistId
        ?.collectAsStateWithLifecycle() ?: remember { mutableStateOf<String?>(null) }

    if (results.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("No results found.", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    // PERF(scroll): groupBy + section order were rebuilt on every
    // recomposition of the results list (play/pause flips, library emissions).
    // Remembered on the results list so they only recompute when results change.
    //
    // FIX(online-search-chip-crash): every result is paired ONCE with its
    // GLOBALLY-unique LazyColumn key ([SearchResultKeys.uniqueKeysFor]). The
    // old raw keys ("cloud_playlist_<id>" / "cloud_artist_<id>" …) crashed
    // the app with "Key was used multiple times" whenever the provider search
    // page repeated an entry — which YouTube Music / SoundCloud routinely do
    // (the same playlist in the top-result shelf AND the list; the same channel
    // in several musicShelfRenderers) — because the ids are url hashcodes.
    // Keys for unique ids stay byte-identical to the historical format.
    val keyedResults = remember(results) {
        val keys = SearchResultKeys.uniqueKeysFor(results)
        results.mapIndexed { index, item -> KeyedSearchResult(item, keys[index]) }
    }
    val groupedResults = remember(keyedResults) {
        keyedResults.groupBy { keyed -> sectionForResultItem(keyed.item) }
    }

    val sectionOrder = listOf(
        SearchFilterType.SONGS,
        SearchFilterType.ALBUMS,
        SearchFilterType.ARTISTS,
        SearchFilterType.PLAYLISTS
    )

    val imePadding = WindowInsets.ime.getBottom(localDensity).dp
    val systemBarPaddingBottom = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding() + 94.dp

    // FIX(cloud-load-more-visibility): the bottom of the results list sits
    // under BOTH the app navigation bar and the miniplayer. The old constant
    // left the last rows flush against the miniplayer, which made a "Load
    // more" row at the bottom impossible to tap. Reserve the REAL overlay
    // (nav bar content + system inset + miniplayer + its spacer) plus
    // breathing room so every bottom row — the Load more button included —
    // is fully visible and comfortably tappable.
    val miniPlayerVisible = currentPlayingSongId != null
    val bottomOverlay = if (miniPlayerVisible) {
        bottomBarHeightDpForResults() + MiniPlayerHeight + MiniPlayerBottomSpacer + 24.dp
    } else {
        bottomBarHeightDpForResults() + 24.dp
    }
    // Keep at least the legacy reservation (miniplayer + system bars + 94dp)
    // so nothing ever scrolls UNDER the miniplayer on tall-overlay devices.
    val legacyBottomPadding = MiniPlayerHeight + systemBarPaddingBottom
    val resultsBottomPadding = if (bottomOverlay > legacyBottomPadding) bottomOverlay else legacyBottomPadding

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = 8.dp,
            bottom = if (imePadding <= 8.dp) resultsBottomPadding else imePadding
        )
    ) {
        sectionOrder.forEach { filterType ->
            val itemsForSection = groupedResults[filterType] ?: emptyList()

            if (itemsForSection.isNotEmpty()) {
                item(
                    key = "header_${filterType.name}",
                    contentType = "section_header"
                ) {
                    SearchResultSectionHeader(
                        title = when (filterType) {
                            SearchFilterType.SONGS -> "Songs"
                            SearchFilterType.ALBUMS -> "Albums"
                            SearchFilterType.ARTISTS -> "Artists"
                            SearchFilterType.PLAYLISTS -> "Playlists"
                            else -> "Results"
                        }
                    )
                }

                items(
                    count = itemsForSection.size,
                    // FIX(online-search-chip-crashes): globally-unique, index-free
                    // keys from [SearchResultKeys.uniqueKeysFor] — see
                    // keyedResults above. Two simultaneously-composed rows can
                    // never share a key, no matter what the provider returns
                    // (repeated entries or id hash collisions).
                    key = { index ->
                        itemsForSection[index].key
                    },
                    // PERF: each SearchResultItem variant renders a completely
                    // different composable subtree. Without contentType the lazy
                    // layout recycles an album row's composition for a song row
                    // (and vice versa) while scrolling between sections, tearing
                    // down and rebuilding the whole subtree every time the type
                    // changes. Keying reuse on the runtime class keeps slot
                    // recycling type-stable.
                    contentType = { index -> itemsForSection[index].item::class }
                ) { index ->
                    val item = itemsForSection[index].item
                    Box(modifier = Modifier.padding(bottom = 12.dp)) {
                        when (item) {
                            is SearchResultItem.SongItem -> {
                                val rememberedOnClick = remember(item.song, playerViewModel, onItemSelected) {
                                    {
                                        playerViewModel.showAndPlaySong(item.song)
                                        onItemSelected()
                                    }
                                }
                                EnhancedSongListItem(
                                    song = item.song,
                                    isPlaying = isPlaying,
                                    isCurrentSong = currentPlayingSongId == item.song.id,
                                    onMoreOptionsClick = onSongMoreOptionsClick,
                                    onClick = rememberedOnClick
                                )
                            }

                            is SearchResultItem.AlbumItem -> {
                                val onPlayClick = remember(item.album, playerViewModel, onItemSelected) {
                                    {
                                        Timber.tag("SearchScreen")
                                            .d("Album clicked: ${item.album.title}")
                                        playerViewModel.playAlbum(item.album)
                                        onItemSelected()
                                    }
                                }
                                val onOpenClick = remember(
                                    item.album,
                                    playerViewModel, onItemSelected
                                ) {
                                    {
                                        navController.navigateSafely(Screen.AlbumDetail.createRoute(item.album.id))
                                        onItemSelected()
                                    }
                                }
                                SearchResultAlbumItem(
                                    album = item.album,
                                    onPlayClick = onPlayClick,
                                    onOpenClick = onOpenClick
                                )
                            }

                            is SearchResultItem.ArtistItem -> {
                                val onPlayClick = remember(item.artist, playerViewModel, onItemSelected) {
                                    {
                                        Timber.tag("SearchScreen")
                                            .d("Artist clicked: ${item.artist.name}")
                                        playerViewModel.playArtist(item.artist)
                                        onItemSelected()
                                    }
                                }
                                val onOpenClick = remember(
                                    item.artist,
                                    playerViewModel, onItemSelected
                                ) {
                                    {
                                        navController.navigateSafely(Screen.ArtistDetail.createRoute(item.artist.id))
                                        onItemSelected()
                                    }
                                }
                                SearchResultArtistItem(
                                    artist = item.artist,
                                    onPlayClick = onPlayClick,
                                    onOpenClick = onOpenClick
                                )
                            }

                            is SearchResultItem.PlaylistItem -> {
                                // PERF: songIds is a List<String>, so `it.id in songIds`
                                // was a linear scan per library song (O(songs x playlist)
                                // per playlist row). Build the HashSet once per
                                // (playlist, library) pair instead.
                                val playlistSongs = remember(item.playlist.songIds, allSongs) {
                                    val songIdSet = item.playlist.songIds.toHashSet()
                                    allSongs.filter { it.id in songIdSet }
                                }
                                val coroutineScope = rememberCoroutineScope()
                                // PERF(scroll): remembered like its sibling row
                                // lambdas (onOpenClick below) so the row can skip
                                // recomposition when the parent re-executes.
                                val onPlayClick: () -> Unit = remember(
                                    item.playlist,
                                    playerViewModel,
                                    playerStableState.isShuffleEnabled,
                                    onItemSelected
                                ) {
                                    {
                                        coroutineScope.launch {
                                            val songs = playerViewModel.getSongs(item.playlist.songIds)
                                            if (songs.isNotEmpty()) {
                                                playerViewModel.playSongs(
                                                    songs,
                                                    songs.first(),
                                                    item.playlist.name
                                                )
                                                if (playerStableState.isShuffleEnabled) playerViewModel.toggleShuffle()
                                            } else {
                                                playerViewModel.sendToast("Empty playlist")
                                            }
                                            onItemSelected()
                                        }
                                    }
                                }
                                val onOpenClick = remember(
                                    item.playlist,
                                    playerViewModel, onItemSelected
                                ) {
                                    {
                                        navController.navigateSafely(Screen.PlaylistDetail.createRoute(item.playlist.id))
                                        onItemSelected()
                                    }
                                }
                                SearchResultPlaylistItem(
                                    playlist = item.playlist,
                                    playlistSongs = playlistSongs,
                                    onPlayClick = onPlayClick,
                                    onOpenClick = onOpenClick
                                )
                            }

                            // FIX(online-filter-chips): ONLINE search results —
                            // tapping the row opens the CloudCatalog detail
                            // screen (which extracts the playlist's REAL
                            // tracks), and the play button opens it with
                            // autoplay so playback starts as soon as the
                            // tracks are loaded. Never routed to the LOCAL
                            // detail screens, which cannot resolve cloud ids.
                            is SearchResultItem.CloudPlaylistItem -> {
                                val onOpenClick = remember(item.playlist, onItemSelected) {
                                    {
                                        navController.navigateSafely(
                                            Screen.CloudCatalog.createRoute(item.playlist)
                                        )
                                        onItemSelected()
                                    }
                                }
                                val onPlayClick = remember(item.playlist, onItemSelected) {
                                    {
                                        navController.navigateSafely(
                                            Screen.CloudCatalog.createRoute(
                                                item.playlist,
                                                autoPlay = true
                                            )
                                        )
                                        onItemSelected()
                                    }
                                }
                                SearchResultCloudPlaylistItem(
                                    playlist = item.playlist,
                                    onOpenClick = onOpenClick,
                                    onPlayClick = onPlayClick,
                                    // IMPROVE(cloud-playlist-import): add this
                                    // online playlist straight to the library's
                                    // Playlists tab.
                                    onAddToLibraryClick = playlistViewModel?.let { vm ->
                                        { vm.importCloudPlaylistToLibrary(item.playlist) }
                                    },
                                    isImported = importedCloudPlaylistIds.contains(
                                        com.saine.pixeltune.data.playlist.CloudPlaylistImportManager
                                            .playlistIdFor(item.playlist)
                                    ),
                                    isImporting = importingCloudPlaylistId == item.playlist.id
                                )
                            }

                            is SearchResultItem.CloudArtistItem -> {
                                val onOpenClick = remember(item.artist, onItemSelected) {
                                    {
                                        navController.navigateSafely(
                                            Screen.CloudCatalog.createRoute(item.artist)
                                        )
                                        onItemSelected()
                                    }
                                }
                                val onPlayClick = remember(item.artist, onItemSelected) {
                                    {
                                        navController.navigateSafely(
                                            Screen.CloudCatalog.createRoute(
                                                item.artist,
                                                autoPlay = true
                                            )
                                        )
                                        onItemSelected()
                                    }
                                }
                                SearchResultCloudArtistItem(
                                    artist = item.artist,
                                    onOpenClick = onOpenClick,
                                    onPlayClick = onPlayClick
                                )
                            }
                        }
                    }
                }
            }
        }

        // IMPROVE(search-load-more): the "Load more" row of the ONLINE results.
        //
        // ⚠️ CRITICAL — this item MUST be registered exactly ONCE, at the very
        // bottom of the LazyColumn and OUTSIDE the sectionOrder.forEach loop
        // above. It previously sat INSIDE the loop, which registered the SAME
        // key ("search_load_more") once per section (up to 4×). LazyLayout
        // wraps every composed item in SaveableStateProvider(key), and
        // androidx.compose.runtime.saveable requires that no two
        // simultaneously-composed items share a key:
        //   IllegalArgumentException: Key search_load_more was used multiple times
        // — a hard crash the moment two of the duplicate rows composed together
        // (short result lists such as the Playlists chip crashed immediately;
        // long lists such as Songs / Albums crashed when scrolled to the
        // bottom where the rows sat). Registering once after ALL sections keeps
        // a single row at the true bottom of the list, always visible over the
        // reserved content padding (nav bar + miniplayer overlay).
        if (hasMoreResults || isLoadingMoreResults) {
            item(key = "search_load_more") {
                SearchLoadMoreRow(
                    isLoadingMore = isLoadingMoreResults,
                    onLoadMore = onLoadMoreResults
                )
            }
        }
    }
}

/**
 * FIX(cloud-load-more-visibility): the app navigation bar height that
 * overlays the bottom of the search results (the Search tab keeps the
 * bottom bar visible).
 */
@Composable
private fun bottomBarHeightDpForResults(): Dp {
    val systemNavBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return NavBarContentHeight + systemNavBarInset
}

/**
 * IMPROVE(search-load-more): the "Load more" row of the online search
 * results — an expressive Material 3 button while idle, a contained progress
 * indicator while the next page is in flight. Placed as the LAST item of the
 * results list; the list's bottom contentPadding keeps it clear of the
 * navigation bar and the miniplayer so it is always comfortably tappable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchLoadMoreRow(
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
                Text("Load more")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchResultAlbumItem(
    album: Album,
    onOpenClick: () -> Unit,
    onPlayClick: () -> Unit
) {
    val itemShape = remember {
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
        onClick = onOpenClick,
        shape = itemShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SmartImage(
                model = album.albumArtUriString,
                contentDescription = "Album Art: ${album.title}",
                modifier = Modifier
                    .size(56.dp)
                    .clip(itemShape)
            )
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = album.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = album.artist,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FilledIconButton(
                onClick = onPlayClick,
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f),
                    contentColor = MaterialTheme.colorScheme.onSecondary
                )
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = "Play Album", modifier = Modifier.size(24.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchResultArtistItem(
    artist: Artist,
    onOpenClick: () -> Unit,
    onPlayClick: () -> Unit
) {
    val itemShape = remember {
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
        onClick = onOpenClick,
        shape = itemShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!artist.effectiveImageUrl.isNullOrBlank()) {
                SmartImage(
                    model = artist.effectiveImageUrl,
                    contentDescription = "Artist: ${artist.name}",
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                )
            } else {
                Icon(
                    painter = painterResource(id = R.drawable.rounded_artist_24),
                    contentDescription = "Artist",
                    modifier = Modifier
                        .size(56.dp)
                        .background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape)
                        .padding(12.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = artist.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${artist.songCount} Songs",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FilledIconButton(
                onClick = onPlayClick,
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.8f),
                    contentColor = MaterialTheme.colorScheme.onTertiary
                )
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = "Play Artist", modifier = Modifier.size(24.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchResultPlaylistItem(
    playlist: Playlist,
    playlistSongs: List<Song>,
    onOpenClick: () -> Unit,
    onPlayClick: () -> Unit
) {
    val itemShape = remember {
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
        onClick = onOpenClick,
        shape = itemShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlaylistCover(
                playlist = playlist,
                playlistSongs = playlistSongs,
                size = 56.dp
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${playlist.songIds.size} songs",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FilledIconButton(
                onClick = onPlayClick,
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = "Play Playlist", modifier = Modifier.size(24.dp))
            }
        }
    }
}

/**
 * FIX(online-filter-chips): an ONLINE-search playlist / album row (YouTube
 * Music / SoundCloud).
 *
 * Styled 1:1 after the app's existing M3 expressive result cards
 * (AbsoluteSmoothCornerShape, surfaceContainerLow, 56dp artwork, circular
 * filled play button) — but now renders the provider's REAL metadata:
 * high-res artwork, uploader name and true track count (the old mapping
 * showed a placeholder cover with "0 songs").
 *
 * IMPROVE(cloud-playlist-import): a compact "Add to your playlist" action
 * sits next to the play button — tapping it adds the playlist to the
 * library's Playlists tab (provider-badged); once added the button turns
 * into a checkmark. The button is a real, fully tappable 40dp target placed
 * ABOVE the list's bottom padding, never overlapped by other UI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchResultCloudPlaylistItem(
    playlist: CloudPlaylist,
    onOpenClick: () -> Unit,
    onPlayClick: () -> Unit,
    onAddToLibraryClick: (() -> Unit)? = null,
    isImported: Boolean = false,
    isImporting: Boolean = false
) {
    val itemShape = remember {
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

    val coverShape = remember {
        AbsoluteSmoothCornerShape(
            cornerRadiusTL = 14.dp,
            smoothnessAsPercentTR = 60,
            cornerRadiusTR = 14.dp,
            smoothnessAsPercentBR = 60,
            cornerRadiusBR = 14.dp,
            smoothnessAsPercentBL = 60,
            cornerRadiusBL = 14.dp,
            smoothnessAsPercentTL = 60
        )
    }

    Card(
        onClick = onOpenClick,
        shape = itemShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (playlist.artworkUrl != null) {
                SmartImage(
                    model = playlist.artworkUrl,
                    contentDescription = "Cover of ${playlist.name}",
                    modifier = Modifier
                        .size(56.dp)
                        .clip(coverShape)
                )
            } else {
                Icon(
                    painter = painterResource(id = R.drawable.rounded_library_music_24),
                    contentDescription = "Playlist",
                    modifier = Modifier
                        .size(56.dp)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            coverShape
                        )
                        .padding(12.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = cloudPlaylistSubtitle(playlist),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // IMPROVE(cloud-playlist-import): "Add to your playlist" — a
            // real 40dp tappable target; checkmark once it's in the library.
            if (onAddToLibraryClick != null) {
                FilledTonalIconButton(
                    onClick = onAddToLibraryClick,
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                    enabled = !isImporting && !isImported,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = if (isImported) {
                            MaterialTheme.colorScheme.tertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                        contentColor = if (isImported) {
                            MaterialTheme.colorScheme.onTertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        }
                    )
                ) {
                    if (isImporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.5.dp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    } else if (isImported) {
                        Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = "Added to your playlists",
                            modifier = Modifier.size(22.dp)
                        )
                    } else {
                        Icon(
                            Icons.Rounded.PlaylistAdd,
                            contentDescription = "Add to your playlists",
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
            }
            FilledIconButton(
                onClick = onPlayClick,
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = (if (playlist.isAlbum) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.primary
                    }).copy(alpha = 0.8f),
                    contentColor = if (playlist.isAlbum) {
                        MaterialTheme.colorScheme.onSecondary
                    } else {
                        MaterialTheme.colorScheme.onPrimary
                    }
                )
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = "Play", modifier = Modifier.size(24.dp))
            }
        }
    }
}

/**
 * FIX(online-filter-chips): an ONLINE-search artist row (YouTube Music /
 * SoundCloud) — renders the channel avatar + real follower count the old
 * mapping threw away, and opens the cloud artist's own track listing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchResultCloudArtistItem(
    artist: CloudArtist,
    onOpenClick: () -> Unit,
    onPlayClick: () -> Unit
) {
    val itemShape = remember {
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
        onClick = onOpenClick,
        shape = itemShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (artist.artworkUrl != null) {
                SmartImage(
                    model = artist.artworkUrl,
                    contentDescription = "Artist: ${artist.name}",
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                )
            } else {
                Icon(
                    painter = painterResource(id = R.drawable.rounded_artist_24),
                    contentDescription = "Artist",
                    modifier = Modifier
                        .size(56.dp)
                        .background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape)
                        .padding(12.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = artist.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = cloudArtistSubtitle(artist),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            FilledIconButton(
                onClick = onPlayClick,
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.8f),
                    contentColor = MaterialTheme.colorScheme.onTertiary
                )
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = "Play", modifier = Modifier.size(24.dp))
            }
        }
    }
}

/** "Uploader • 12 tracks" subtitle for a cloud playlist row. */
private fun cloudPlaylistSubtitle(playlist: CloudPlaylist): String {
    val uploader = playlist.uploaderName?.takeIf { it.isNotBlank() }
        ?: (if (playlist.isAlbum) "Album" else "Playlist")
    val countLabel = when (playlist.trackCount) {
        -1L -> null
        1L -> "1 track"
        else -> "${playlist.trackCount} tracks"
    }
    return listOfNotNull(uploader, countLabel).joinToString(" • ")
}

/**
 * Subtitle for a cloud artist row.
 *
 * FIX(youtube-subscriber-count): YouTube Music artist search results report a
 * "monthly audience" metric (monthly listeners) rather than subscriber counts,
 * and NewPipe misparses it INTO `subscriberCount` — the app then showed absurd
 * values like "313M subscribers" for artists whose channels have ~28.6M. The
 * repository layer now keeps the two metrics apart
 * ([CloudArtist.subscriberCount] vs [CloudArtist.monthlyAudienceCount]), and
 * this subtitle labels each one honestly:
 *   - "28.6M subscribers"  — only when the provider said "subscribers";
 *   - "313M monthly listeners" — the monthly-audience metric, exactly what
 *     YouTube Music itself displays on artist search results;
 *   - "Artist" — neither metric known.
 */
private fun cloudArtistSubtitle(artist: CloudArtist): String {
    val unit = if (artist.provider == com.saine.pixeltune.data.model.CloudStreamProvider.YOUTUBE) {
        "subscribers"
    } else {
        "followers"
    }
    // Only a count the provider explicitly labeled "subscribers"/"followers"
    // may be rendered with the subscriber label.
    if (artist.subscriberCount >= 0) {
        return formatCloudAudienceCount(artist.subscriberCount, unit)
    }
    if (artist.monthlyAudienceCount >= 0) {
        return formatCloudAudienceCount(artist.monthlyAudienceCount, "monthly listeners")
    }
    return "Artist"
}

/** "1.2M subscribers" / "28.6M monthly listeners" count formatter. */
private fun formatCloudAudienceCount(count: Long, unit: String): String {
    return when {
        count >= 1_000_000L -> {
            val v = count / 1_000_000L
            val frac = (count % 1_000_000L) / 100_000L
            if (frac > 0) "$v.${frac}M $unit" else "$v M $unit"
        }
        count >= 1_000L -> "${count / 1_000L}K $unit"
        else -> "$count $unit"
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun SearchFilterChip(
    filterType: SearchFilterType,
    currentFilter: SearchFilterType,
    playerViewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val selected = filterType == currentFilter

    FilterChip(
        selected = selected,
        onClick = { playerViewModel.updateSearchFilter(filterType) },
        label = { Text(filterType.name.lowercase().replaceFirstChar { it.titlecase() }) },
        modifier = modifier,
        shape = CircleShape,
        border = BorderStroke(
            width = 0.dp,
            color = Color.Transparent
        ),
        colors = FilterChipDefaults.filterChipColors(
            containerColor =  MaterialTheme.colorScheme.secondaryContainer,
            labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
         leadingIcon = if (selected) {
             {
                 Icon(
                     painter = painterResource(R.drawable.rounded_check_circle_24),
                     contentDescription = "Selected",
                     tint = MaterialTheme.colorScheme.onPrimary,
                     modifier = Modifier.size(FilterChipDefaults.IconSize)
                 )
             }
         } else {
             null
         }
    )
}
