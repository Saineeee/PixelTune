package com.theveloper.pixeltune.presentation.navigation

import androidx.compose.runtime.Immutable
import com.theveloper.pixeltune.data.model.CloudArtist
import com.theveloper.pixeltune.data.model.CloudPlaylist
import kotlinx.serialization.json.Json
import java.net.URLEncoder


@Immutable
sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Search : Screen("search")
    object Library : Screen("library")
    object Settings : Screen("settings")
    object Accounts : Screen("settings_accounts")
    object SettingsCategory : Screen("settings_category/{categoryId}") {
        fun createRoute(categoryId: String) = "settings_category/$categoryId"
    }
    object PaletteStyle : Screen("palette_style_settings")
    object Experimental : Screen("experimental_settings")
    object NavBarCrRad : Screen("nav_bar_corner_radius")
    object PlaylistDetail : Screen("playlist_detail/{playlistId}") {
        fun createRoute(playlistId: String) = "playlist_detail/$playlistId"
    }

    /**
     * FIX(online-filter-chips): the ONLINE-search catalog detail screen —
     * cloud playlists / albums / artists (YouTube Music, SoundCloud).
     *
     * The entry travels as URL-encoded JSON in the `entry` query argument;
     * `type` ("playlist" / "artist") selects the concrete class to decode
     * (both classes' required fields overlap, so the type must be explicit);
     * `autoplay` is set when the row's PLAY button (not the row body) opened
     * the screen, so playback starts as soon as the real tracks are loaded.
     */
    object CloudCatalog : Screen("cloud_catalog/{type}?entry={entry}&autoplay={autoplay}") {
        fun createRoute(playlist: CloudPlaylist, autoPlay: Boolean = false): String {
            val encoded = encodeEntry(Json.encodeToString(playlist))
            return "cloud_catalog/playlist?entry=$encoded&autoplay=${if (autoPlay) "true" else "false"}"
        }

        fun createRoute(artist: CloudArtist, autoPlay: Boolean = false): String {
            val encoded = encodeEntry(Json.encodeToString(artist))
            return "cloud_catalog/artist?entry=$encoded&autoplay=${if (autoPlay) "true" else "false"}"
        }

        /**
         * Percent-encodes the entry JSON for safe travel through a nav route
         * argument. `+` is normalized to `%20` because URLEncoder emits it for
         * spaces (form encoding) — keeping it would make the value ambiguous
         * against query-string decoding; `%20` decodes correctly under every
         * decoding behavior.
         */
        private fun encodeEntry(json: String): String =
            URLEncoder.encode(json, "UTF-8").replace("+", "%20")
    }

    object  DailyMixScreen : Screen("daily_mix")
    object RecentlyPlayed : Screen("recently_played")
    object ListeningHistory : Screen("listening_history")
    object Stats : Screen("stats")
    object GenreDetail : Screen("genre_detail/{genreId}") { // New screen
        fun createRoute(genreId: String) = "genre_detail/$genreId"
    }
    object DJSpace : Screen("dj_space")
    // La ruta base es "album_detail". La ruta completa con el argumento se define en AppNavigation.
    object AlbumDetail : Screen("album_detail/{albumId}") {
        // Función de ayuda para construir la ruta de navegación con el ID del álbum.
        fun createRoute(albumId: Long) = "album_detail/$albumId"
    }

    object ArtistDetail : Screen("artist_detail/{artistId}") {
        fun createRoute(artistId: Long) = "artist_detail/$artistId"
    }

    object EditTransition : Screen("edit_transition?playlistId={playlistId}") {
        fun createRoute(playlistId: String?) =
            if (playlistId != null) "edit_transition?playlistId=$playlistId" else "edit_transition"
    }

    object About : Screen("about")

    object ArtistSettings : Screen("artist_settings")
    object DelimiterConfig : Screen("delimiter_config")
    object Equalizer : Screen("equalizer")
    object DeviceCapabilities : Screen("device_capabilities")
    object NeteaseDashboard : Screen("netease_dashboard")

}
