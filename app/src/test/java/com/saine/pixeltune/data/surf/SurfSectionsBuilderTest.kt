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

    private fun historyEntry(songId: String, timestamp: Long) =
        PlaybackStatsRepository.PlaybackHistoryEntry(songId = songId, timestamp = timestamp)

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
    fun `jump back in skips cloud plays that are not in the library`() {
        val local = song("local", artist = "A", artistId = 1L, album = "Local Album", albumId = 10L)
        val history = listOf(
            historyEntry("cloud-song", 3_000L),
            historyEntry("local", 2_000L)
        )

        val entries = SurfSectionsBuilder.buildJumpBackIn(history, listOf(local))

        assertThat(entries.map { it.title }).containsNoDuplicates()
        assertThat(entries.map { it.title }).containsExactly("Local Album", "A")
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
}
