package com.theveloper.pixeltune.data.model

import androidx.compose.runtime.Immutable

@Immutable
enum class LibraryTabId(
    val storageKey: String,
    val title: String,
    val defaultSort: SortOption
) {
    SONGS("SONGS", "SONGS", SortOption.SongTitleAZ),
    ALBUMS("ALBUMS", "ALBUMS", SortOption.AlbumTitleAZ),
    ARTISTS("ARTIST", "ARTIST", SortOption.ArtistNameAZ),
    PLAYLISTS("PLAYLISTS", "PLAYLISTS", SortOption.PlaylistNameAZ),
    FOLDERS("FOLDERS", "FOLDERS", SortOption.FolderNameAZ),
    LIKED("LIKED", "LIKED", SortOption.LikedSongDateLiked),
    // IMPROVE(downloads-chip): dedicated Library chip for offline downloads
    // (Netflix-style, app-private copies of cloud-streamed songs). Default
    // ordering is by download date (newest first); the sort button now offers
    // the same affordance the other library tabs have.
    DOWNLOADS("DOWNLOADS", "DOWNLOADS", SortOption.DownloadDateNewest);

    companion object {
        fun fromStorageKey(key: String): LibraryTabId =
            entries.firstOrNull { it.storageKey == key } ?: SONGS
    }
}

fun String.toLibraryTabIdOrNull(): LibraryTabId? =
    LibraryTabId.entries.firstOrNull { it.storageKey == this }