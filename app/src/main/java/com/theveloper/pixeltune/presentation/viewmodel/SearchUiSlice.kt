package com.theveloper.pixeltune.presentation.viewmodel

import androidx.compose.runtime.Immutable
import com.theveloper.pixeltune.data.model.SearchFilterType
import com.theveloper.pixeltune.data.model.SearchHistoryItem
import com.theveloper.pixeltune.data.model.SearchResultItem
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * PERF(search): narrow UI-state slice consumed by the Search screen.
 *
 * [PlayerUiState] is a ~40-field aggregate that re-emits whenever ANY
 * playback, library, folder or undo-bar field changes. The Search screen
 * only renders five of those fields, so collecting the aggregate forced the
 * whole screen to recompose for emissions that cannot affect it (library
 * sync flags flipping, queue edits, dismiss-undo state, restore snapshots,
 * position writes on pause/seek, ...).
 *
 * This slice carries exactly the fields the Search screen reads, sourced
 * from the same [SearchStateHolder] flows that are mirrored into
 * [PlayerUiState]. Because the slice is an immutable data class, downstream
 * `distinctUntilChanged` semantics mean the screen only recomposes when one
 * of its own inputs actually changes.
 */
@Immutable
data class SearchUiSlice(
    /** Live search results (online provider or local library filter). */
    val searchResults: ImmutableList<SearchResultItem> = persistentListOf(),
    /** Recent search history for the online landing view. */
    val searchHistory: ImmutableList<SearchHistoryItem> = persistentListOf(),
    /** Currently selected result filter chip (ALL / SONGS / VIDEOS / ...). */
    val selectedSearchFilter: SearchFilterType = SearchFilterType.ALL,
    /** True while an ONLINE search request is in flight. */
    val isSearching: Boolean = false,
    /** True when the search bar operates in online (vs local library) mode. */
    val isOnlineSearch: Boolean = true
)
