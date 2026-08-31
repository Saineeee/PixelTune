package com.theveloper.pixeltune.data.model

import androidx.compose.runtime.Immutable

@Immutable
sealed interface SearchResultItem {
    data class SongItem(val song: Song) : SearchResultItem

    /** LOCAL library search result — routes to the local AlbumDetail screen. */
    data class AlbumItem(val album: Album) : SearchResultItem

    /** LOCAL library search result — routes to the local ArtistDetail screen. */
    data class ArtistItem(val artist: Artist) : SearchResultItem

    /** LOCAL library search result — routes to the local PlaylistDetail screen. */
    data class PlaylistItem(val playlist: Playlist) : SearchResultItem

    /**
     * FIX(online-filter-chips): ONLINE search result — a YouTube Music /
     * SoundCloud playlist or album. Keeps the provider metadata NewPipe
     * extracted (artwork, uploader, track count) and the canonical URL the
     * [com.theveloper.pixeltune.presentation.screens.CloudCatalogScreen]
     * re-extracts playable tracks from.
     */
    data class CloudPlaylistItem(val playlist: CloudPlaylist) : SearchResultItem

    /**
     * FIX(online-filter-chips): ONLINE search result — a YouTube Music /
     * SoundCloud artist. Keeps the provider metadata NewPipe extracted
     * (avatar, subscriber count) and the canonical channel URL the
     * [com.theveloper.pixeltune.presentation.screens.CloudCatalogScreen]
     * re-extracts playable tracks from.
     */
    data class CloudArtistItem(val artist: CloudArtist) : SearchResultItem
}
