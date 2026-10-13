package com.saine.pixeltune.data.surf

import androidx.compose.runtime.Immutable
import com.saine.pixeltune.data.model.Song
import com.saine.pixeltune.data.stats.PlaybackStatsRepository

/**
 * SURF: one card in the "Jump back in" row — a local album or artist the
 * listener recently played, ready to re-open through the existing
 * AlbumDetail / ArtistDetail screens.
 */
@Immutable
sealed interface SurfJumpBackInEntry {
    val title: String
    val artworkUriString: String?

    /** The album's id in Room ([com.saine.pixeltune.Screen.AlbumDetail] route). */
    @Immutable
    data class Album(
        val albumId: Long,
        override val title: String,
        val artistName: String,
        override val artworkUriString: String?
    ) : SurfJumpBackInEntry

    /** The artist's id in Room ([com.saine.pixeltune.Screen.ArtistDetail] route). */
    @Immutable
    data class Artist(
        val artistId: Long,
        override val title: String,
        val songCount: Int,
        override val artworkUriString: String?
    ) : SurfJumpBackInEntry
}

/**
 * SURF: one avatar in the "Top artists" row — the local artist with the
 * highest played duration+count rollup from the on-device engagement stats.
 */
@Immutable
data class SurfTopArtist(
    val artistId: Long,
    val name: String,
    val playCount: Int,
    val songCount: Int,
    val representativeArtUriString: String?
)

/**
 * SURF: the on-device personalisation behind the discovery feed's offline
 * sections. Pure functions over (library songs, playback history,
 * engagement stats) so the ranking is unit-testable — mirrors how
 * [com.saine.pixeltune.data.DailyMixManager] keeps its scoring pure of the
 * network layer. Personalisation NEVER leaves the device: these builders run
 * on local Room / MediaStore data only.
 */
object SurfSectionsBuilder {

    /**
     * Builds "Jump back in": the albums and artists behind the listener's
     * most recent plays, most-recent-first, alternating album/artist families
     * so the row shows breadth instead of a run of one artist's albums.
     *
     * Cloud plays (history entries whose songId is not in the local library)
     * are skipped — they have no local album/artist page to re-open.
     */
    fun buildJumpBackIn(
        playbackHistory: List<PlaybackStatsRepository.PlaybackHistoryEntry>,
        allSongs: List<Song>,
        maxEntries: Int = DEFAULT_JUMP_BACK_IN_ENTRIES
    ): List<SurfJumpBackInEntry> {
        if (maxEntries <= 0 || playbackHistory.isEmpty() || allSongs.isEmpty()) return emptyList()

        val songById = HashMap<String, Song>(allSongs.size)
        for (song in allSongs) {
            songById.putIfAbsent(song.id, song)
        }

        // Most-recent occurrence rank of each album / artist (first sight in
        // the newest-first history stream = recency).
        val albumRanks = LinkedHashMap<Long, Song>()
        val artistRanks = LinkedHashMap<Long, Song>()
        for (entry in playbackHistory) {
            val song = songById[entry.songId] ?: continue
            if (song.albumId > 0 && song.album.isNotBlank() && song.albumId !in albumRanks) {
                albumRanks[song.albumId] = song
            }
            if (song.artistId > 0 && song.displayArtist.isNotBlank() && song.artistId !in artistRanks) {
                artistRanks[song.artistId] = song
            }
        }
        if (albumRanks.isEmpty() && artistRanks.isEmpty()) return emptyList()

        val songsByArtist = HashMap<Long, Int>(artistRanks.size)
        for (song in allSongs) {
            if (song.artistId > 0) songsByArtist[song.artistId] = (songsByArtist[song.artistId] ?: 0) + 1
        }

        val albums = albumRanks.map { (albumId, song) ->
            SurfJumpBackInEntry.Album(
                albumId = albumId,
                title = song.album,
                artistName = song.albumArtist?.takeIf { it.isNotBlank() } ?: song.displayArtist,
                artworkUriString = song.albumArtUriString
            )
        }
        val artists = artistRanks.map { (artistId, song) ->
            SurfJumpBackInEntry.Artist(
                artistId = artistId,
                title = song.displayArtist,
                songCount = songsByArtist[artistId] ?: 1,
                artworkUriString = song.albumArtUriString
            )
        }

        // Interleave families by recency rank so a single artist's albums
        // don't dominate the head of the row.
        val merged = ArrayList<SurfJumpBackInEntry>(albums.size + artists.size)
        var albumIndex = 0
        var artistIndex = 0
        while (merged.size < maxEntries && (albumIndex < albums.size || artistIndex < artists.size)) {
            if (albumIndex < albums.size) {
                merged += albums[albumIndex++]
                if (merged.size >= maxEntries) break
            }
            if (artistIndex < artists.size) {
                merged += artists[artistIndex++]
            }
        }
        return merged.take(maxEntries)
    }

    /**
     * Builds "Top artists": local artists ranked by their total on-device
     * play count (minutes played break ties). Only artists the listener
     * actually played appear — a fresh install yields an empty row and the
     * section is hidden, exactly like Home hides Daily Mix until data exists.
     */
    fun buildTopArtists(
        engagementStats: Map<String, SongEngagementStats>,
        allSongs: List<Song>,
        maxArtists: Int = DEFAULT_TOP_ARTISTS
    ): List<SurfTopArtist> {
        if (maxArtists <= 0 || allSongs.isEmpty() || engagementStats.isEmpty()) return emptyList()

        data class Rollup(
            val name: String,
            val playCount: Int,
            val playedDurationMs: Long,
            val songCount: Int,
            val artUriString: String?
        )

        val rollups = HashMap<Long, Rollup>(64)
        for (song in allSongs) {
            if (song.artistId <= 0 || song.displayArtist.isBlank()) continue
            val stats = engagementStats[song.id] ?: continue
            val current = rollups[song.artistId]
            val newCount = (current?.playCount ?: 0) + stats.playCount
            val newDuration = (current?.playedDurationMs ?: 0L) + stats.totalPlayDurationMs
            // The representative artwork comes from the artist's most-played
            // song seen so far — pure offline, always available.
            val art = if ((current?.playCount ?: 0) >= stats.playCount) {
                current?.artUriString
            } else {
                song.albumArtUriString
            }
            rollups[song.artistId] = Rollup(
                name = song.displayArtist,
                playCount = newCount,
                playedDurationMs = newDuration,
                songCount = (current?.songCount ?: 0) + 1,
                artUriString = art ?: current?.artUriString
            )
        }

        return rollups.entries
            .filter { it.value.playCount > 0 }
            .sortedWith(
                compareByDescending<Map.Entry<Long, Rollup>> { it.value.playCount }
                    .thenByDescending { it.value.playedDurationMs }
            )
            .take(maxArtists)
            .map { (artistId, rollup) ->
                SurfTopArtist(
                    artistId = artistId,
                    name = rollup.name,
                    playCount = rollup.playCount,
                    songCount = rollup.songCount,
                    representativeArtUriString = rollup.artUriString
                )
            }
    }

    /**
     * Picks the "Because you played X" seed: the library's most played song
     * (play count first, most recently played breaks ties) with a usable
     * title — the same signal Home's Daily Mix ranks on. Returns null for a
     * brand-new listener, in which case the section is simply not shown.
     */
    fun pickRelatedSeed(
        engagementStats: Map<String, SongEngagementStats>,
        allSongs: List<Song>
    ): Song? {
        if (engagementStats.isEmpty() || allSongs.isEmpty()) return null
        var best: Song? = null
        var bestCount = -1
        var bestLastPlayed = -1L
        for (song in allSongs) {
            if (song.title.isBlank()) continue
            val stats = engagementStats[song.id] ?: continue
            if (stats.playCount <= 0) continue
            val better = stats.playCount > bestCount ||
                (stats.playCount == bestCount && stats.lastPlayedTimestamp > bestLastPlayed)
            if (better) {
                best = song
                bestCount = stats.playCount
                bestLastPlayed = stats.lastPlayedTimestamp
            }
        }
        return best
    }

    /**
     * The engagement stats [buildTopArtists] and [pickRelatedSeed] rank on —
     * the same fields [com.saine.pixeltune.data.DailyMixManager] exposes,
     * declared here so the builders stay free of Android types.
     */
    data class SongEngagementStats(
        val playCount: Int = 0,
        val totalPlayDurationMs: Long = 0L,
        val lastPlayedTimestamp: Long = 0L
    )

    const val DEFAULT_JUMP_BACK_IN_ENTRIES = 12
    const val DEFAULT_TOP_ARTISTS = 8
}
