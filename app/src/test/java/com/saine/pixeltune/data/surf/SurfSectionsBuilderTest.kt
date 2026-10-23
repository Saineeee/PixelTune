package com.saine.pixeltune.data.surf

import com.google.common.truth.Truth.assertThat
import com.saine.pixeltune.data.model.Song
import com.saine.pixeltune.data.stats.PlaybackStatsRepository
import org.junit.Test

class SurfSectionsBuilderTest {

    private fun song(
        id: String,
        title: String = "Song $id",
        artist: String = "Artist $id",
        artistId: Long = id.hashCode().toLong(),
        album: String = "Album $id",
        albumId: Long = ("album$album").hashCode().toLong(),
        genre: String? = null,
        art: String? = "art://$id"
    ): Song = Song.emptySong().copy(
        id = id,
        title = title,
        artist = artist,
        artistId = artistId,
        album = album,
        albumId = albumId,
        albumArtUriString = art,
        genre = genre
    )

    private fun historyEntry(
        songId: String,
        timestamp: Long,
        title: String? = null,
        artist: String? = null,
        album: String? = null,
        albumArtUri: String? = null,
        contentUri: String? = null,
        songDurationMs: Long? = null
    ) = PlaybackStatsRepository.PlaybackHistoryEntry(
        songId = songId,
        timestamp = timestamp,
        title = title,
        artist = artist,
        album = album,
        albumArtUri = albumArtUri,
        contentUri = contentUri,
        songDurationMs = songDurationMs
    )

    /** A cloud-streamed play as the tracker records it (metadata snapshot). */
    private fun cloudPlay(
        songId: String,
        title: String,
        artist: String,
        timestamp: Long,
        contentUri: String = "youtube://$songId",
        art: String? = "https://art/$songId.jpg",
        durationMs: Long = 200_000L
    ) = historyEntry(
        songId = songId,
        timestamp = timestamp,
        title = title,
        artist = artist,
        album = null,
        albumArtUri = art,
        contentUri = contentUri,
        songDurationMs = durationMs
    )

    // ------------------------------------------------------------------
    // Jump back in
    // ------------------------------------------------------------------

    @Test
    fun `jump back in orders entries by play recency`() {
        val oldest = song("old", artist = "Old Artist", artistId = 1L, album = "Old Album", albumId = 10L)
        val newest = song("new", artist = "New Artist", artistId = 2L, album = "New Album", albumId = 20L)
        val allSongs = listOf(oldest, newest)
        // The tracker serves history newest-first; the builder relies on it.
        val history = listOf(
            historyEntry("new", 2_000L),
            historyEntry("old", 1_000L)
        )

        val entries = SurfSectionsBuilder.buildJumpBackIn(history, allSongs)

        // Most recent play wins: the newest song's album + artist lead.
        assertThat(entries.map { it.title }).containsAtLeast("New Album", "New Artist").inOrder()
        assertThat(entries.size).isEqualTo(4)
    }

    @Test
    fun `jump back in turns cloud plays into playable song entries`() {
        val local = song("local", artist = "A", artistId = 1L, album = "Local Album", albumId = 10L)
        val history = listOf(
            cloudPlay("cloud-song", "Cloud Song", "Cloud Artist", 3_000L),
            historyEntry("local", 2_000L)
        )

        val entries = SurfSectionsBuilder.buildJumpBackIn(history, listOf(local))

        assertThat(entries.map { it.title }).containsNoDuplicates()
        assertThat(entries.map { it.title }).containsExactly("Local Album", "A", "Cloud Song").inOrder()
        val cloudEntry = entries.filterIsInstance<SurfJumpBackInEntry.Song>().single()
        assertThat(cloudEntry.song.id).isEqualTo("cloud-song")
        assertThat(cloudEntry.song.artist).isEqualTo("Cloud Artist")
        assertThat(cloudEntry.song.contentUriString).isEqualTo("youtube://cloud-song")
        assertThat(cloudEntry.song.youtubeId).isEqualTo("cloud-song")
        assertThat(cloudEntry.song.albumArtUriString).isEqualTo("https://art/cloud-song.jpg")
        assertThat(cloudEntry.song.duration).isEqualTo(200_000L)
    }

    @Test
    fun `jump back in cloud entry keeps a null youtubeId for soundcloud uris`() {
        val history = listOf(
            cloudPlay(
                "sc-1",
                "SC Song",
                "SC Artist",
                1_000L,
                contentUri = "soundcloud://encoded-payload"
            )
        )

        val entries = SurfSectionsBuilder.buildJumpBackIn(history, emptyList())

        val cloudEntry = entries.filterIsInstance<SurfJumpBackInEntry.Song>().single()
        assertThat(cloudEntry.song.youtubeId).isNull()
    }

    @Test
    fun `jump back in is filled from cloud plays alone when the library is empty`() {
        val history = (1..4).map { index ->
            cloudPlay("cloud$index", "Cloud Title $index", "Artist $index", 1_000L * (4 - index))
        }

        val entries = SurfSectionsBuilder.buildJumpBackIn(history, emptyList())

        assertThat(entries.map { it.title }).containsExactly(
            "Cloud Title 1", "Cloud Title 2", "Cloud Title 3", "Cloud Title 4"
        ).inOrder()
    }

    @Test
    fun `jump back in skips cloud plays without a metadata snapshot`() {
        val history = listOf(historyEntry("legacy-cloud", 3_000L))

        val entries = SurfSectionsBuilder.buildJumpBackIn(history, emptyList())

        assertThat(entries).isEmpty()
    }

    @Test
    fun `jump back in interleaves albums artists and cloud songs`() {
        val local = song("local", artist = "Local Artist", artistId = 1L, album = "Local Album", albumId = 10L)
        val history = listOf(
            cloudPlay("c1", "Cloud One", "Cloud Artist", 30_000L),
            historyEntry("local", 20_000L)
        )

        val entries = SurfSectionsBuilder.buildJumpBackIn(history, listOf(local))

        assertThat(entries.map { it.title }).containsExactly(
            "Local Album", "Local Artist", "Cloud One"
        ).inOrder()
    }

    @Test
    fun `jump back in deduplicates repeated albums and artists`() {
        val a1 = song("a1", artist = "Artist A", artistId = 1L, album = "Album A", albumId = 10L)
        val a2 = song("a2", artist = "Artist A", artistId = 1L, album = "Album A", albumId = 10L)
        val history = listOf(
            historyEntry("a1", 2_000L),
            historyEntry("a2", 1_000L)
        )

        val entries = SurfSectionsBuilder.buildJumpBackIn(history, listOf(a1, a2))

        assertThat(entries.map { it.title }).containsExactly("Album A", "Artist A").inOrder()
    }

    @Test
    fun `jump back in interleaves albums and artists instead of running one family first`() {
        // Three albums and three artists, all from distinct plays, newest
        // first interleaved by history order.
        val songs = listOf(
            song("s1", artist = "A1", artistId = 1L, album = "B1", albumId = 11L),
            song("s2", artist = "A2", artistId = 2L, album = "B2", albumId = 22L),
            song("s3", artist = "A3", artistId = 3L, album = "B3", albumId = 33L)
        )
        val history = listOf(
            historyEntry("s1", 30_000L),
            historyEntry("s2", 20_000L),
            historyEntry("s3", 10_000L)
        )

        val entries = SurfSectionsBuilder.buildJumpBackIn(history, songs)

        val titles = entries.map { it.title }
        assertThat(titles).containsExactly("B1", "A1", "B2", "A2", "B3", "A3").inOrder()
    }

    @Test
    fun `jump back in respects the entry cap`() {
        val songs = (1..10).map { index ->
            song("s$index", artist = "A$index", artistId = index.toLong(), album = "B$index", albumId = 100L + index)
        }
        val history = songs.mapIndexed { index, s -> historyEntry(s.id, 1_000L * (songs.size - index)) }

        val entries = SurfSectionsBuilder.buildJumpBackIn(history, songs, maxEntries = 5)

        assertThat(entries.size).isEqualTo(5)
    }

    @Test
    fun `jump back in is empty without history`() {
        val songs = listOf(song("a"))
        assertThat(SurfSectionsBuilder.buildJumpBackIn(emptyList(), songs)).isEmpty()
        assertThat(SurfSectionsBuilder.buildJumpBackIn(listOf(historyEntry("a", 1L)), emptyList())).isEmpty()
    }

    @Test
    fun `jump back in artist entries carry the library song count`() {
        val a1 = song("a1", artist = "Artist A", artistId = 7L, album = "Album A", albumId = 10L)
        val a2 = song("a2", artist = "Artist A", artistId = 7L, album = "Album B", albumId = 20L)
        val history = listOf(historyEntry("a2", 1_000L))

        val entries = SurfSectionsBuilder.buildJumpBackIn(history, listOf(a1, a2))

        val artistEntry = entries.filterIsInstance<SurfJumpBackInEntry.Artist>().single()
        assertThat(artistEntry.songCount).isEqualTo(2)
    }

    // ------------------------------------------------------------------
    // Top artists
    // ------------------------------------------------------------------

    @Test
    fun `top artists rank by total play count across their songs`() {
        val playedLots = (1..3).map { index ->
            song("hot$index", artist = "Hot Artist", artistId = 1L)
        }
        val playedSome = song("mid", artist = "Mid Artist", artistId = 2L)
        val neverPlayed = song("cold", artist = "Cold Artist", artistId = 3L)
        val allSongs = playedLots + playedSome + neverPlayed
        val stats = buildMap {
            playedLots.forEach { put(it.id, SurfSectionsBuilder.SongEngagementStats(playCount = 2)) }
            put(playedSome.id, SurfSectionsBuilder.SongEngagementStats(playCount = 3))
            put(neverPlayed.id, SurfSectionsBuilder.SongEngagementStats(playCount = 0))
        }

        val artists = SurfSectionsBuilder.buildTopArtists(stats, allSongs)

        // Total play count across songs: 2+2+2 = 6 beats 3.
        assertThat(artists.first().name).isEqualTo("Hot Artist")
        assertThat(artists.map { it.name }).containsExactly("Hot Artist", "Mid Artist").inOrder()
    }

    @Test
    fun `top artists exclude artists never played`() {
        val never = song("cold", artist = "Cold Artist", artistId = 3L)
        val artists = SurfSectionsBuilder.buildTopArtists(emptyMap(), listOf(never))
        assertThat(artists).isEmpty()
    }

    @Test
    fun `top artists break count ties with played duration`() {
        val short = song("short", artist = "Short", artistId = 1L)
        val long = song("long", artist = "Long", artistId = 2L)
        val stats = mapOf(
            short.id to SurfSectionsBuilder.SongEngagementStats(playCount = 2, totalPlayDurationMs = 1_000L),
            long.id to SurfSectionsBuilder.SongEngagementStats(playCount = 2, totalPlayDurationMs = 60_000L)
        )

        val artists = SurfSectionsBuilder.buildTopArtists(stats, listOf(short, long))

        assertThat(artists.first().name).isEqualTo("Long")
    }

    @Test
    fun `top artists representative art comes from the most played song`() {
        val lessPlayed = song("less", artist = "A", artistId = 1L, art = "art://less")
        val morePlayed = song("more", artist = "A", artistId = 1L, art = "art://more")
        val stats = mapOf(
            lessPlayed.id to SurfSectionsBuilder.SongEngagementStats(playCount = 1),
            morePlayed.id to SurfSectionsBuilder.SongEngagementStats(playCount = 5)
        )

        val artists = SurfSectionsBuilder.buildTopArtists(stats, listOf(lessPlayed, morePlayed))

        assertThat(artists.single().representativeArtUriString).isEqualTo("art://more")
    }

    @Test
    fun `top artists cap the row length`() {
        val songs = (1..10).map { index ->
            song("s$index", artist = "A$index", artistId = index.toLong())
        }
        val stats = songs.associate {
            it.id to SurfSectionsBuilder.SongEngagementStats(playCount = 1)
        }

        val artists = SurfSectionsBuilder.buildTopArtists(stats, songs, maxArtists = 3)

        assertThat(artists.size).isEqualTo(3)
    }

    @Test
    fun `top artists roll up streamed artists from the playback history`() {
        val history = listOf(
            cloudPlay("c1", "Song One", "Streamed Artist", 3_000L),
            cloudPlay("c2", "Song Two", "Streamed Artist", 2_000L),
            cloudPlay("c3", "Song Three", "Other Artist", 1_000L)
        )
        val stats = mapOf(
            "c1" to SurfSectionsBuilder.SongEngagementStats(playCount = 4, totalPlayDurationMs = 40_000L),
            "c2" to SurfSectionsBuilder.SongEngagementStats(playCount = 2, totalPlayDurationMs = 20_000L),
            "c3" to SurfSectionsBuilder.SongEngagementStats(playCount = 3, totalPlayDurationMs = 30_000L)
        )

        val artists = SurfSectionsBuilder.buildTopArtists(stats, emptyList(), history)

        // Streamed Artist: 4 + 2 = 6 plays beats Other Artist's 3.
        assertThat(artists.map { it.name }).containsExactly("Streamed Artist", "Other Artist").inOrder()
        val streamed = artists.first()
        assertThat(streamed.isCloud).isTrue()
        assertThat(streamed.artistId).isEqualTo(-1L)
        assertThat(streamed.playCount).isEqualTo(6)
        assertThat(streamed.representativeArtUriString).isEqualTo("https://art/c1.jpg")
    }

    @Test
    fun `top artists cloud rollups count a bare history presence as one play`() {
        val history = listOf(cloudPlay("c1", "Song One", "Streamed Artist", 3_000L))

        val artists = SurfSectionsBuilder.buildTopArtists(emptyMap(), emptyList(), history)

        val streamed = artists.single()
        assertThat(streamed.playCount).isEqualTo(1)
    }

    @Test
    fun `top artists merge streamed plays into the same-name local artist`() {
        val local = song("l1", artist = "Shared Artist", artistId = 9L)
        val history = listOf(cloudPlay("c1", "Cloud Song", "Shared Artist", 1_000L))
        val stats = mapOf(
            local.id to SurfSectionsBuilder.SongEngagementStats(playCount = 2),
            "c1" to SurfSectionsBuilder.SongEngagementStats(playCount = 5)
        )

        val artists = SurfSectionsBuilder.buildTopArtists(stats, listOf(local), history)

        val merged = artists.single()
        assertThat(merged.isCloud).isFalse()
        assertThat(merged.artistId).isEqualTo(9L)
        assertThat(merged.playCount).isEqualTo(7)
    }

    @Test
    fun `top artists count a cloud artist with the local library empty`() {
        val history = listOf(cloudPlay("c1", "Song One", "Solo Cloud Artist", 1_000L))
        val stats = mapOf(
            "c1" to SurfSectionsBuilder.SongEngagementStats(playCount = 2)
        )

        val artists = SurfSectionsBuilder.buildTopArtists(stats, emptyList(), history)

        assertThat(artists.single().isCloud).isTrue()
    }

    // ------------------------------------------------------------------
    // Related seed
    // ------------------------------------------------------------------

    @Test
    fun `related seed picks the most played song`() {
        val regular = song("regular")
        val favorite = song("favorite")
        val stats = mapOf(
            regular.id to SurfSectionsBuilder.SongEngagementStats(playCount = 1),
            favorite.id to SurfSectionsBuilder.SongEngagementStats(playCount = 9)
        )

        val seed = SurfSectionsBuilder.pickRelatedSeed(stats, listOf(regular, favorite))

        assertThat(seed?.id).isEqualTo("favorite")
    }

    @Test
    fun `related seed breaks play count ties with recency`() {
        val older = song("older")
        val newer = song("newer")
        val stats = mapOf(
            older.id to SurfSectionsBuilder.SongEngagementStats(playCount = 4, lastPlayedTimestamp = 100L),
            newer.id to SurfSectionsBuilder.SongEngagementStats(playCount = 4, lastPlayedTimestamp = 900L)
        )

        val seed = SurfSectionsBuilder.pickRelatedSeed(stats, listOf(older, newer))

        assertThat(seed?.id).isEqualTo("newer")
    }

    @Test
    fun `related seed is null without engagement or library`() {
        val onlySong = song("a")
        assertThat(SurfSectionsBuilder.pickRelatedSeed(emptyMap(), listOf(onlySong))).isNull()
        assertThat(
            SurfSectionsBuilder.pickRelatedSeed(
                mapOf(onlySong.id to SurfSectionsBuilder.SongEngagementStats(playCount = 3)),
                emptyList()
            )
        ).isNull()
    }

    @Test
    fun `related seed skips songs with blank titles`() {
        val blank = song("blank", title = " ")
        val stats = mapOf(blank.id to SurfSectionsBuilder.SongEngagementStats(playCount = 10))

        assertThat(SurfSectionsBuilder.pickRelatedSeed(stats, listOf(blank))).isNull()
    }

    @Test
    fun `related seed prefers the most streamed cloud song over local plays`() {
        val local = song("local")
        val history = listOf(
            cloudPlay("yt-1", "Streamed Hit", "Cloud Artist", 5_000L)
        )
        val stats = mapOf(
            local.id to SurfSectionsBuilder.SongEngagementStats(playCount = 2),
            "yt-1" to SurfSectionsBuilder.SongEngagementStats(playCount = 7)
        )

        val seed = SurfSectionsBuilder.pickRelatedSeed(stats, listOf(local), history)

        assertThat(seed?.id).isEqualTo("yt-1")
        assertThat(seed?.youtubeId).isEqualTo("yt-1")
        assertThat(seed?.artist).isEqualTo("Cloud Artist")
    }

    @Test
    fun `related seed skips cloud plays without engagement`() {
        val history = listOf(cloudPlay("c1", "Unfinalized", "Cloud Artist", 1_000L))

        val seed = SurfSectionsBuilder.pickRelatedSeed(emptyMap(), emptyList(), history)

        assertThat(seed).isNull()
    }

    @Test
    fun `related seed cloud candidate falls back to the local best`() {
        val local = song("local")
        val history = listOf(cloudPlay("c1", "Bare Cloud Song", "Cloud Artist", 1_000L))
        val stats = mapOf(local.id to SurfSectionsBuilder.SongEngagementStats(playCount = 1))

        val seed = SurfSectionsBuilder.pickRelatedSeed(stats, listOf(local), history)

        assertThat(seed?.id).isEqualTo("local")
    }
}
