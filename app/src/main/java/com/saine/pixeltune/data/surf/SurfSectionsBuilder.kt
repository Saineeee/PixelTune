package com.saine.pixeltune.data.surf

import androidx.compose.runtime.Immutable
import com.saine.pixeltune.data.model.Song
import com.saine.pixeltune.data.stats.PlaybackStatsRepository

/**
 * SURF: one card in the "Jump back in" row — a local album or artist the
 * listener recently played, ready to re-open through the existing
 * AlbumDetail / ArtistDetail screens, or a cloud-streamed song replayed
 * straight from its persisted metadata snapshot.
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

    /**
     * A cloud-streamed song (YouTube / SoundCloud) the listener recently
     * played — rebuilt from the playback-history metadata snapshot exactly
     * like the "Recently Played" surfaces rebuild theirs, so tapping it
     * replays the song through the same proxy resolution that screen uses
     * (the engine resolves the persisted `youtube://` / `soundcloud://`
     * scheme URI back to the current session's proxy URL).
     *
     * The model type is fully qualified because this class's own name would
     * shadow [com.saine.pixeltune.data.model.Song] inside its declaration.
     */
    @Immutable
    data class Song(
        val song: com.saine.pixeltune.data.model.Song,
        override val artworkUriString: String? = song.albumArtUriString
    ) : SurfJumpBackInEntry {
        override val title: String get() = song.title
    }
}

/**
 * SURF: one avatar in the "Top artists" row — either a local artist (the
 * Room [com.saine.pixeltune.Screen.ArtistDetail] route) or a cloud artist
 * the listener only knows from streaming, whose page is the provider's own
 * search for that artist's tracks.
 */
@Immutable
data class SurfTopArtist(
    val artistId: Long,
    val name: String,
    val playCount: Int,
    val songCount: Int,
    val representativeArtUriString: String?,
    /** Cloud artists have no Room row — their page is a provider search. */
    val isCloud: Boolean = false
)

/**
 * SURF: the on-device personalisation behind the discovery feed's offline
 * sections. Pure functions over (library songs, playback history,
 * engagement stats) so the ranking is unit-testable — mirrors how
 * [com.saine.pixeltune.data.DailyMixManager] keeps its scoring pure of the
 * network layer. Personalisation NEVER leaves the device: these builders run
 * on local Room / MediaStore data only.
 *
 * Cloud plays participate everywhere: the playback history carries a
 * metadata snapshot for every streamed song (title / artist / artwork /
 * normalized scheme URI), and the engagement stats are keyed by song id
 * regardless of origin — so a listener who only ever streams still gets
 * their "Jump back in", "Top artists" and "Because you played" sections.
 */
object SurfSectionsBuilder {

    /**
     * Builds "Jump back in": the albums and artists behind the listener's
     * most recent LOCAL plays plus the cloud songs themselves (most-recent
     * first), interleaving the album / artist / cloud-song families so the
     * row shows breadth instead of a run of one artist's albums.
     *
     * Cloud plays resolve to playable [SurfJumpBackInEntry.Song] cards when
     * their history entry carries a metadata snapshot; entries without one
     * (recorded by older app versions) are skipped.
     */
    fun buildJumpBackIn(
        playbackHistory: List<PlaybackStatsRepository.PlaybackHistoryEntry>,
        allSongs: List<Song>,
        maxEntries: Int = DEFAULT_JUMP_BACK_IN_ENTRIES
    ): List<SurfJumpBackInEntry> {
        if (maxEntries <= 0 || playbackHistory.isEmpty()) return emptyList()

        val songById = HashMap<String, Song>(allSongs.size)
        for (song in allSongs) {
            songById.putIfAbsent(song.id, song)
        }

        // Most-recent occurrence rank of each album / artist (first sight in
        // the newest-first history stream = recency), and the recency-ordered
        // cloud songs themselves.
        val albumRanks = LinkedHashMap<Long, Song>()
        val artistRanks = LinkedHashMap<Long, Song>()
        val cloudSongs = ArrayList<Song>(playbackHistory.size)
        for (entry in playbackHistory) {
            val song = songById[entry.songId]
            if (song != null) {
                if (song.albumId > 0 && song.album.isNotBlank() && song.albumId !in albumRanks) {
                    albumRanks[song.albumId] = song
                }
                if (song.artistId > 0 && song.displayArtist.isNotBlank() && song.artistId !in artistRanks) {
                    artistRanks[song.artistId] = song
                }
            } else if (entry.hasMetadataSnapshot) {
                // A cloud-streamed song (or a local song deleted since) —
                // replayable from the snapshot exactly like Recently Played.
                val cloudSong = entry.toCloudSong() ?: continue
                if (cloudSong.title.isNotBlank()) {
                    cloudSongs += cloudSong
                }
            }
        }
        if (albumRanks.isEmpty() && artistRanks.isEmpty() && cloudSongs.isEmpty()) return emptyList()

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
        val songs = cloudSongs.map { SurfJumpBackInEntry.Song(it) }

        // Interleave families by recency rank so a single artist's albums
        // don't dominate the head of the row — album, artist, cloud song,
        // repeat, until the row is full.
        val merged = ArrayList<SurfJumpBackInEntry>(albums.size + artists.size + songs.size)
        var albumIndex = 0
        var artistIndex = 0
        var songIndex = 0
        while (merged.size < maxEntries &&
            (albumIndex < albums.size || artistIndex < artists.size || songIndex < songs.size)
        ) {
            if (albumIndex < albums.size) {
                merged += albums[albumIndex++]
                if (merged.size >= maxEntries) break
            }
            if (artistIndex < artists.size) {
                merged += artists[artistIndex++]
                if (merged.size >= maxEntries) break
            }
            if (songIndex < songs.size) {
                merged += songs[songIndex++]
            }
        }
        return merged.take(maxEntries)
    }

    /**
     * Builds "Top artists": local artists ranked by their total on-device
     * play count (minutes played break ties) merged with the artists behind
     * the listener's recent CLOUD plays — the same signal the streaming
     * services themselves rank on. Local and cloud rollups for the same
     * artist name merge into the local identity (its ArtistDetail page is
     * the richer destination) with summed counts. Only artists the listener
     * actually played appear — a fresh install yields an empty row and the
     * section is hidden, exactly like Home hides Daily Mix until data exists.
     */
    fun buildTopArtists(
        engagementStats: Map<String, SongEngagementStats>,
        allSongs: List<Song>,
        playbackHistory: List<PlaybackStatsRepository.PlaybackHistoryEntry> = emptyList(),
        maxArtists: Int = DEFAULT_TOP_ARTISTS
    ): List<SurfTopArtist> {
        if (maxArtists <= 0) return emptyList()

        data class Rollup(
            val name: String,
            var playCount: Int,
            var playedDurationMs: Long,
            var songCount: Int,
            var artUriString: String?,
            var cloud: Boolean
        )

        val rollups = LinkedHashMap<String, Rollup>(64)
        for (song in allSongs) {
            if (song.artistId <= 0 || song.displayArtist.isBlank()) continue
            val stats = engagementStats[song.id] ?: continue
            val key = song.displayArtist.trim().lowercase()
            val current = rollups[key]
            val newCount = (current?.playCount ?: 0) + stats.playCount
            val newDuration = (current?.playedDurationMs ?: 0L) + stats.totalPlayDurationMs
            // The representative artwork comes from the artist's most-played
            // song seen so far — pure offline, always available.
            val art = if ((current?.playCount ?: 0) >= stats.playCount) {
                current?.artUriString
            } else {
                song.albumArtUriString
            }
            rollups[key] = Rollup(
                name = song.displayArtist,
                playCount = newCount,
                playedDurationMs = newDuration,
                songCount = (current?.songCount ?: 0) + 1,
                artUriString = art ?: current?.artUriString,
                cloud = false
            )
        }

        // Cloud rollups: artists whose songs only exist in the playback
        // history (streamed from the current provider), counted from the
        // per-song engagement rows the tracker writes for every play.
        if (playbackHistory.isNotEmpty()) {
            val songById = HashMap<String, Song>(allSongs.size)
            for (song in allSongs) {
                songById.putIfAbsent(song.id, song)
            }
            for (entry in playbackHistory) {
                if (songById.containsKey(entry.songId)) continue
                val artist = entry.artist?.trim()?.takeIf { it.isNotBlank() } ?: continue
                val key = artist.lowercase()
                val stats = engagementStats[entry.songId]
                val current = rollups[key]
                if (current != null && !current.cloud) {
                    // The library knows this artist — add the streamed plays
                    // to the local identity instead of a duplicate avatar.
                    current.playCount += stats?.playCount ?: 1
                    current.playedDurationMs += stats?.totalPlayDurationMs ?: 0L
                    continue
                }
                val plays = stats?.playCount ?: 1
                val art = entry.albumArtUri
                if (current != null) {
                    current.playCount += plays
                    current.playedDurationMs += stats?.totalPlayDurationMs ?: 0L
                    if (current.artUriString.isNullOrBlank()) current.artUriString = art
                } else {
                    rollups[key] = Rollup(
                        name = artist,
                        playCount = plays,
                        playedDurationMs = stats?.totalPlayDurationMs ?: 0L,
                        songCount = 1,
                        artUriString = art,
                        cloud = true
                    )
                }
            }
        }

        // The local artist ids (for the ArtistDetail navigation) resolve from
        // the library AFTER the rollups are final — one pass, no per-entry map.
        val artistIdByName = HashMap<String, Long>(64)
        for (song in allSongs) {
            if (song.artistId > 0 && song.displayArtist.isNotBlank()) {
                artistIdByName.putIfAbsent(song.displayArtist.trim().lowercase(), song.artistId)
            }
        }

        return rollups.entries
            .filter { it.value.playCount > 0 }
            .sortedWith(
                compareByDescending<Map.Entry<String, Rollup>> { it.value.playCount }
                    .thenByDescending { it.value.playedDurationMs }
            )
            .take(maxArtists)
            .map { (_, rollup) ->
                val localId = if (rollup.cloud) null else artistIdByName[rollup.name.lowercase()]
                SurfTopArtist(
                    artistId = localId ?: -1L,
                    name = rollup.name,
                    playCount = rollup.playCount,
                    songCount = rollup.songCount,
                    representativeArtUriString = rollup.artUriString,
                    isCloud = localId == null
                )
            }
    }

    /**
     * Picks the "Because you played X" seed: the most played song across the
     * library AND the cloud (play count first, most recently played breaks
     * ties) with a usable title — the same signal Home's Daily Mix ranks on.
     * Cloud candidates rebuild from the playback-history metadata snapshot
     * so the related fetch can seed the provider's own radio for that exact
     * track. Returns null for a brand-new listener, in which case the
     * section is simply not shown.
     */
    fun pickRelatedSeed(
        engagementStats: Map<String, SongEngagementStats>,
        allSongs: List<Song>,
        playbackHistory: List<PlaybackStatsRepository.PlaybackHistoryEntry> = emptyList()
    ): Song? {
        var best: Song? = null
        var bestCount = -1
        var bestLastPlayed = -1L

        for (song in allSongs) {
            if (song.title.isBlank()) continue
            val stats = engagementStats[song.id] ?: continue
            if (stats.playCount <= 0) continue
            if (isBetterSeed(stats.playCount, stats.lastPlayedTimestamp, bestCount, bestLastPlayed)) {
                best = song
                bestCount = stats.playCount
                bestLastPlayed = stats.lastPlayedTimestamp
            }
        }

        if (playbackHistory.isNotEmpty()) {
            val songById = HashSet<String>(allSongs.size)
            for (song in allSongs) {
                songById += song.id
            }
            for (entry in playbackHistory) {
                if (entry.songId in songById) continue
                if (!entry.hasMetadataSnapshot) continue
                val stats = engagementStats[entry.songId] ?: continue
                if (stats.playCount <= 0) continue
                val cloudSong = entry.toCloudSong() ?: continue
                if (cloudSong.title.isBlank()) continue
                if (isBetterSeed(stats.playCount, stats.lastPlayedTimestamp, bestCount, bestLastPlayed)) {
                    best = cloudSong
                    bestCount = stats.playCount
                    bestLastPlayed = stats.lastPlayedTimestamp
                }
            }
        }
        return best
    }

    private fun isBetterSeed(count: Int, lastPlayed: Long, bestCount: Int, bestLastPlayed: Long): Boolean =
        count > bestCount || (count == bestCount && lastPlayed > bestLastPlayed)

    /**
     * Rebuilds a playable [Song] from a history entry's metadata snapshot —
     * the same reconstruction the "Recently Played" surfaces use, with the
     * YouTube video id recovered from the persisted `youtube://<id>` scheme
     * URI so the related-tracks fetch can seed the track's own radio mix.
     * Returns null for entries without a snapshot.
     */
    private fun PlaybackStatsRepository.PlaybackHistoryEntry.toCloudSong(): Song? {
        if (!hasMetadataSnapshot) return null
        val contentUri = contentUri.orEmpty()
        return Song.emptySong().copy(
            id = songId,
            title = title.orEmpty(),
            artist = artist ?: "Unknown Artist",
            artistId = -1L,
            album = album ?: "",
            albumId = -1L,
            albumArtUriString = albumArtUri,
            path = "",
            contentUriString = contentUri,
            duration = songDurationMs ?: 0L,
            dateAdded = timestamp,
            youtubeId = extractYoutubeId(contentUri)
        )
    }

    /** `youtube://<videoId>` -> `<videoId>`; null for anything else. */
    private fun extractYoutubeId(contentUriString: String): String? {
        val schemeIndex = contentUriString.indexOf("youtube://")
        if (schemeIndex != 0) return null
        val videoId = contentUriString.removePrefix("youtube://").trim()
        return videoId.takeIf { it.isNotEmpty() }
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
