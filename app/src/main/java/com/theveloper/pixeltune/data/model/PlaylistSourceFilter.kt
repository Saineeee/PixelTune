package com.theveloper.pixeltune.data.model

import androidx.compose.runtime.Immutable

/**
 * IMPROVE(playlist-source-filter): source filter for the Library's Playlists
 * tab — lets the user see only LOCAL playlists, only CLOUD-imported ones
 * (YouTube / SoundCloud / Netease / Telegram), or everything.
 *
 * Persisted through [com.theveloper.pixeltune.data.preferences.UserPreferencesRepository]
 * next to the playlists sort option, and surfaced in the same Material 3 sort
 * bottom sheet (segmented "All / Local / Cloud" row, mirroring the Folders
 * tab's Internal / SD segments).
 */
@Immutable
enum class PlaylistSourceFilter(val storageKey: String, val displayName: String) {
    ALL("all", "All"),
    LOCAL("local", "Local"),
    CLOUD("cloud", "Cloud");

    companion object {
        fun fromStorageKey(raw: String?): PlaylistSourceFilter =
            entries.firstOrNull { it.storageKey == raw } ?: ALL
    }
}

/**
 * A playlist is "cloud-sourced" when its tracks come from an online streaming
 * / cloud-drive provider instead of the device's local library. AI-generated
 * playlists stay local — they select from the local library.
 */
fun Playlist.isCloudSourced(): Boolean = when (source.trim().uppercase()) {
    "YOUTUBE", "SOUNDCLOUD", "NETEASE", "TELEGRAM" -> true
    else -> false
}
