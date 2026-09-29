package com.saine.pixeltune.utils

import com.saine.pixeltune.data.model.CloudArtist
import com.saine.pixeltune.data.model.CloudPlaylist
import com.saine.pixeltune.data.model.CloudStreamProvider
import com.saine.pixeltune.data.model.SearchResultItem
import com.saine.pixeltune.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FIX(online-search-chip-crash): JVM regression tests for the LazyColumn key
 * builders of the ONLINE search results and the cloud catalog track list.
 *
 * The app hard-crashed with `IllegalArgumentException: Key "…" was used
 * multiple times` (Compose's SaveableStateProvider) when the Playlists or
 * Artists filter chips' results rendered, and when a playlist's tracks opened
 * on the CloudCatalog screen: the provider search pages repeat entries, the
 * result ids are url hashcodes, and the raw keys offered no disambiguation.
 *
 * These tests pin the exact contract the fix introduced:
 *  - unique base keys stay byte-identical to the historical format (item
 *    state / scroll anchors keep working exactly as before);
 *  - repeated base keys — from repeated provider entries OR hash collisions
 *    of DIFFERENT urls — are disambiguated with a deterministic suffix, so
 *    no two simultaneously-composable rows can ever share a key.
 */
class SearchResultKeysTest {

    private fun song(id: String) =
        SearchResultItem.SongItem(Song.emptySong().copy(id = id))

    private fun cloudPlaylist(id: String, isAlbum: Boolean = false) =
        SearchResultItem.CloudPlaylistItem(
            CloudPlaylist(
                id = id,
                url = "https://www.youtube.com/playlist?list=$id",
                name = "Playlist $id",
                isAlbum = isAlbum,
                provider = CloudStreamProvider.YOUTUBE
            )
        )

    private fun cloudArtist(id: String) =
        SearchResultItem.CloudArtistItem(
            CloudArtist(
                id = id,
                url = "https://www.youtube.com/channel/$id",
                name = "Artist $id",
                provider = CloudStreamProvider.YOUTUBE
            )
        )

    // ------------------------------------------------------------------
    // disambiguate() — the core crash guard.
    // ------------------------------------------------------------------

    @Test
    fun `empty and single-element lists pass through`() {
        assertTrue(SearchResultKeys.disambiguate(emptyList()).isEmpty())
        assertEquals(
            listOf("cloud_song_abc"),
            SearchResultKeys.disambiguate(listOf("cloud_song_abc"))
        )
    }

    @Test
    fun `unique keys are returned unchanged`() {
        val bases = listOf(
            "song_a",
            "cloud_playlist_b",
            "cloud_artist_c",
            "song_d"
        )
        assertEquals(bases, SearchResultKeys.disambiguate(bases))
    }

    @Test
    fun `repeated keys get distinct deterministic suffixes`() {
        // The exact scenario of the crash: the ARTISTS chip page contained the
        // same channel (same id) in more than one shelf.
        val bases = listOf(
            "cloud_artist_4711",   // top-result shelf
            "cloud_artist_8080",   // main shelf
            "cloud_artist_4711",   // top-result artist again in the main shelf
            "cloud_artist_4711"    // …and once more
        )
        val keys = SearchResultKeys.disambiguate(bases)

        assertEquals(4, keys.size)
        assertEquals(4, keys.toSet().size) // THE crash contract: globally unique
        // Deterministic ordinals, stable for a given list content.
        assertEquals("cloud_artist_4711~0", keys[0])
        assertEquals("cloud_artist_8080", keys[1])
        assertEquals("cloud_artist_4711~1", keys[2])
        assertEquals("cloud_artist_4711~2", keys[3])
    }

    @Test
    fun `different urls whose ids hash-collide are disambiguated too`() {
        // Ids are url hashcodes — two DIFFERENT playlists can collide. Both
        // rows must render (they are different items), so both keys must
        // exist and be unique.
        val bases = listOf(
            "cloud_playlist_1234",
            "cloud_playlist_1234" // hash-colliding but DISTINCT playlist
        )
        val keys = SearchResultKeys.disambiguate(bases)
        assertEquals(2, keys.size)
        assertEquals(2, keys.toSet().size)
    }

    @Test
    fun `output is deterministic for identical input`() {
        val bases = listOf("a", "a", "b", "a", "b")
        assertEquals(
            SearchResultKeys.disambiguate(bases),
            SearchResultKeys.disambiguate(bases)
        )
    }

    // ------------------------------------------------------------------
    // uniqueKeysFor() — the search results integration.
    // ------------------------------------------------------------------

    @Test
    fun `base keys keep the historical per-variant format`() {
        assertEquals("song_q1", SearchResultKeys.baseKeyOf(song("q1")))
        assertEquals(
            "cloud_playlist_p1",
            SearchResultKeys.baseKeyOf(cloudPlaylist("p1"))
        )
        assertEquals(
            "cloud_artist_a1",
            SearchResultKeys.baseKeyOf(cloudArtist("a1"))
        )
    }

    @Test
    fun `results page with a repeated artist never yields duplicate keys`() {
        // The exact crash repro: YT Music artists response repeating the same
        // channel across shelves (same url -> same id -> same base key).
        val results = listOf(
            cloudArtist("4711"),
            cloudArtist("8080"),
            cloudArtist("4711"),
            cloudArtist("9999"),
            cloudArtist("4711")
        )
        val keys = SearchResultKeys.uniqueKeysFor(results)
        assertEquals(results.size, keys.size)
        assertEquals(keys.size, keys.toSet().size)
        // Unique ids keep the exact historical key — recomposition behavior
        // for the common case is unchanged.
        assertEquals("cloud_artist_8080", keys[1])
        assertEquals("cloud_artist_9999", keys[3])
    }

    @Test
    fun `mixed results page across sections stays globally unique`() {
        // The results LazyColumn composes Songs / Albums / Artists / Playlists
        // sections in ONE layout — keys must be unique across ALL of them,
        // not just per section.
        val results = listOf(
            song("s1"),
            cloudPlaylist("p1", isAlbum = true),   // groups under Albums
            cloudPlaylist("p1"),                   // same id, groups under Playlists
            cloudArtist("a1"),
            song("s1")                              // repeated song id
        )
        val keys = SearchResultKeys.uniqueKeysFor(results)
        assertEquals(results.size, keys.size)
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `cloud catalog song list with repeated ids is disambiguated`() {
        // The "opening a playlist crashes" repro: the same video twice in a
        // YouTube playlist.
        val songIds = listOf("v1", "v2", "v3", "v2", "v2")
        val keys = SearchResultKeys.disambiguate(songIds.map { "cloud_song_$it" })
        assertEquals(songIds.size, keys.size)
        assertEquals(keys.size, keys.toSet().size)
        assertEquals("cloud_song_v2~0", keys[1])
        assertEquals("cloud_song_v2~1", keys[3])
        assertEquals("cloud_song_v2~2", keys[4])
    }
}
