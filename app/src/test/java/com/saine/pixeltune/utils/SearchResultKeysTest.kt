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
 * FIX(online-search-chip-crash): JVM tests for the globally-unique
 * LazyColumn key derivation the ONLINE search results and the CloudCatalog
 * track list rely on.
 *
 * The crash these tests pin down: `IllegalArgumentException: Key "…" was used
 * multiple times` (Compose SaveableStateProvider) when the provider search
 * page repeated an entry — the same playlist in the top-result shelf AND the
 * main list (Playlists chip), the same channel in several musicShelfRenderers
 * (Artists chip), the same video twice inside a playlist (CloudCatalog), or
 * two DIFFERENT urls whose `hashCode()` ids collided.
 */
class SearchResultKeysTest {

    // ---------------------------------------------------------------------
    // builders
    // ---------------------------------------------------------------------

    private fun cloudPlaylist(url: String, name: String = url): SearchResultItem =
        SearchResultItem.CloudPlaylistItem(
            CloudPlaylist(
                id = url.hashCode().toString(),
                url = url,
                name = name,
                provider = CloudStreamProvider.YOUTUBE
            )
        )

    private fun cloudArtist(url: String, name: String = url): SearchResultItem =
        SearchResultItem.CloudArtistItem(
            CloudArtist(
                id = url.hashCode().toString(),
                url = url,
                name = name,
                provider = CloudStreamProvider.YOUTUBE
            )
        )

    private fun song(id: String): SearchResultItem =
        SearchResultItem.SongItem(Song.emptySong().copy(id = id, title = id))

    // ---------------------------------------------------------------------
    // disambiguate — the core uniqueness contract
    // ---------------------------------------------------------------------

    @Test
    fun `disambiguate passthrough - unique base keys are returned unchanged`() {
        val base = listOf("song_a", "cloud_playlist_b", "cloud_artist_c")
        assertEquals(base, SearchResultKeys.disambiguate(base))
    }

    @Test
    fun `disambiguate single element list is returned as-is`() {
        assertEquals(listOf("only"), SearchResultKeys.disambiguate(listOf("only")))
        assertTrue(SearchResultKeys.disambiguate(emptyList()).isEmpty())
    }

    @Test
    fun `disambiguate repeated base keys get deterministic ordinal suffixes`() {
        val base = listOf("cloud_playlist_x", "song_a", "cloud_playlist_x", "cloud_playlist_x")
        val expected = listOf(
            "cloud_playlist_x~0",
            "song_a",
            "cloud_playlist_x~1",
            "cloud_playlist_x~2"
        )
        assertEquals(expected, SearchResultKeys.disambiguate(base))
    }

    @Test
    fun `disambiguate output never contains a duplicate - the crash contract`() {
        // Simulated provider repeat + hash collision: three entries share the
        // base key "cloud_artist_same", one of them via a DIFFERENT url whose
        // hashcode was forced equal.
        val base = listOf(
            "cloud_artist_same",
            "cloud_artist_other",
            "cloud_artist_same",
            "cloud_artist_same"
        )
        val keys = SearchResultKeys.disambiguate(base)
        assertEquals(base.size, keys.size)
        assertEquals("every key must be unique", keys.size, keys.toSet().size)
    }

    @Test
    fun `disambiguate is stable for identical input - scroll anchors survive`() {
        val base = listOf("a", "a", "b", "a", "c")
        assertEquals(
            SearchResultKeys.disambiguate(base),
            SearchResultKeys.disambiguate(base)
        )
    }

    // ---------------------------------------------------------------------
    // uniqueKeysFor — the search results repro
    // ---------------------------------------------------------------------

    @Test
    fun `uniqueKeysFor - playlists chip repro - same playlist twice never collides`() {
        // The exact Playlists-chip crash shape: YT Music returned the same
        // playlist URL in the top-result shelf AND the main list.
        val results = listOf(
            cloudPlaylist("https://www.youtube.com/playlist?list=PLdup"),
            cloudPlaylist("https://www.youtube.com/playlist?list=PLother"),
            cloudPlaylist("https://www.youtube.com/playlist?list=PLdup") // repeat
        )
        val keys = SearchResultKeys.uniqueKeysFor(results)
        assertEquals(results.size, keys.size)
        assertEquals("keys must be globally unique", keys.size, keys.toSet().size)
        // The unique playlist keeps the exact historical key format.
        val uniquePlaylist = results[1] as SearchResultItem.CloudPlaylistItem
        assertEquals("cloud_playlist_${uniquePlaylist.playlist.id}", keys[1])
    }

    @Test
    fun `uniqueKeysFor - artists chip repro - same channel across shelves never collides`() {
        // The exact Artists-chip crash shape: the raw YT Music artists parse
        // found the same channel in two musicShelfRenderers.
        val results = listOf(
            cloudArtist("https://www.youtube.com/channel/UCcoldplay"),
            cloudArtist("https://www.youtube.com/channel/UCadele"),
            cloudArtist("https://www.youtube.com/channel/UCcoldplay") // repeat
        )
        val keys = SearchResultKeys.uniqueKeysFor(results)
        assertEquals(results.size, keys.size)
        assertEquals("keys must be globally unique", keys.size, keys.toSet().size)
    }

    @Test
    fun `uniqueKeysFor - hash collision between different urls is disambiguated`() {
        // Two DIFFERENT playlist urls whose hashcodes collide share the same
        // id — historically a fatal duplicate key. Find a real collision
        // cheaply: force it by constructing items with the same id directly.
        val a = CloudPlaylist(
            id = "777",
            url = "https://www.youtube.com/playlist?list=PLfirst",
            name = "First",
            provider = CloudStreamProvider.YOUTUBE
        )
        val b = CloudPlaylist(
            id = "777", // colliding id, different url
            url = "https://www.youtube.com/playlist?list=PLsecond",
            name = "Second",
            provider = CloudStreamProvider.YOUTUBE
        )
        val keys = SearchResultKeys.uniqueKeysFor(
            listOf(
                SearchResultItem.CloudPlaylistItem(a),
                SearchResultItem.CloudPlaylistItem(b)
            )
        )
        assertEquals(2, keys.size)
        assertEquals("colliding ids must still yield unique keys", 2, keys.toSet().size)
    }

    @Test
    fun `uniqueKeysFor - unique ids keep the exact historical key format`() {
        val songItem = song("dQw4w9WgXcQ")
        val playlistItem = cloudPlaylist("https://www.youtube.com/playlist?list=PLone")
        val artistItem = cloudArtist("https://www.youtube.com/channel/UConly")
        val keys = SearchResultKeys.uniqueKeysFor(listOf(songItem, playlistItem, artistItem))
        assertEquals("song_dQw4w9WgXcQ", keys[0])
        val playlist = (playlistItem as SearchResultItem.CloudPlaylistItem).playlist
        val artist = (artistItem as SearchResultItem.CloudArtistItem).artist
        assertEquals("cloud_playlist_${playlist.id}", keys[1])
        assertEquals("cloud_artist_${artist.id}", keys[2])
    }

    @Test
    fun `uniqueKeysFor - cross-section repeat is disambiguated too`() {
        // The results LazyColumn composes ALL sections in ONE layout — the
        // same base key in two different sections is just as fatal. A song
        // and a (hash-colliding) cloud playlist id share the base key prefix
        // only if the ids match, so simulate the direct case: the same song
        // id appearing twice in one page.
        val results = listOf(
            song("videoX"),
            song("videoY"),
            song("videoX") // provider repeat across shelves
        )
        val keys = SearchResultKeys.uniqueKeysFor(results)
        assertEquals(3, keys.size)
        assertEquals(3, keys.toSet().size)
        assertEquals("song_videoY", keys[1]) // unique entry unchanged
    }

    // ---------------------------------------------------------------------
    // baseKeyOf — the shared identity used by the Load-more dedupe
    // ---------------------------------------------------------------------

    @Test
    fun `baseKeyOf builds the historical key formats`() {
        assertEquals(
            "song_s",
            SearchResultKeys.baseKeyOf(song("s"))
        )
        assertEquals(
            "cloud_playlist_p",
            SearchResultKeys.baseKeyOf(
                SearchResultItem.CloudPlaylistItem(
                    CloudPlaylist(
                        id = "p",
                        url = "u",
                        name = "n",
                        provider = CloudStreamProvider.YOUTUBE
                    )
                )
            )
        )
        assertEquals(
            "cloud_artist_a",
            SearchResultKeys.baseKeyOf(
                SearchResultItem.CloudArtistItem(
                    CloudArtist(
                        id = "a",
                        url = "u",
                        name = "n",
                        provider = CloudStreamProvider.SOUNDCLOUD
                    )
                )
            )
        )
    }
}
