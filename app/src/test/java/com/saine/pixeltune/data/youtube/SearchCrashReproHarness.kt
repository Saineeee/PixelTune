package com.saine.pixeltune.data.youtube

import com.saine.pixeltune.data.model.SearchFilterType
import com.saine.pixeltune.data.model.SearchResultItem
import com.saine.pixeltune.data.model.Song
import com.saine.pixeltune.utils.SearchResultKeys
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * JVM harness reproducing the ONLINE SEARCH crash reports:
 *  - clicking the PLAYLISTS filter chip crashes immediately
 *  - SONGS / ALBUMS chips + scrolling down crashes
 *
 * Runs the REAL NewPipe-backed repository search (network) for every filter
 * chip and prints the exact data the SearchScreen renders — item types, ids,
 * duplicate ids, and continuation state — so the UI-side crash can be
 * reproduced against real data instead of theory.
 */
class SearchCrashReproHarness {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() {
            // These tests hit the REAL YouTube Music endpoints through the
            // same NewPipe path the app uses. Offline / CI environments skip
            // them instead of failing (they are regression documentation for
            // the duplicate-LazyColumn-key crash, not gate-keepers).
            // NOTE: TCP connect, not InetAddress.isReachable — ICMP echo is
            // blocked in many containers/sandboxes even when HTTPS works.
            assumeTrue(
                "network unreachable — skipping live search harness",
                runCatching {
                    java.net.Socket().use { s ->
                        s.connect(java.net.InetSocketAddress("music.youtube.com", 443), 4000)
                    }
                    true
                }.getOrDefault(false)
            )
            NewPipe.init(
                NewPipeDownloader(
                    OkHttpClient.Builder()
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .build()
                )
            )
        }
    }

    private val repo = YouTubeRepository(OkHttpClient())

    private fun describe(item: SearchResultItem): String = when (item) {
        is SearchResultItem.SongItem ->
            "SongItem(id=${item.song.id}, dur=${item.song.duration}, art=${item.song.albumArtUriString?.take(60)})"
        is SearchResultItem.AlbumItem -> "AlbumItem(id=${item.album.id})"
        is SearchResultItem.ArtistItem -> "ArtistItem(id=${item.artist.id})"
        is SearchResultItem.PlaylistItem -> "PlaylistItem(id=${item.playlist.id})"
        is SearchResultItem.CloudPlaylistItem ->
            "CloudPlaylistItem(id=${item.playlist.id}, isAlbum=${item.playlist.isAlbum}, name=${item.playlist.name.take(40)}, trackCount=${item.playlist.trackCount}, url=${item.playlist.url}, art=${item.playlist.artworkUrl?.take(60)})"
        is SearchResultItem.CloudArtistItem ->
            "CloudArtistItem(id=${item.artist.id}, subs=${item.artist.subscriberCount}, monthly=${item.artist.monthlyAudienceCount})"
    }

    /**
     * The EXACT LazyColumn key logic the FIXED SearchScreen generates for
     * results: index-free base keys ("song_<id>" / "cloud_playlist_<id>" / …)
     * globally disambiguated by [SearchResultKeys] — the same helper the UI
     * calls ([SearchResultKeys.uniqueKeysFor]).
     *
     * Before the fix the keys were the raw base keys — provider pages that
     * repeat an entry (the same playlist in the top-result shelf AND the
     * list; the same channel in several musicShelfRenderers) then produced
     * "Key was used multiple times" crashes; the id hashcodes can collide
     * for DIFFERENT urls on top of that.
     */
    private fun lazyColumnKeys(results: List<SearchResultItem>): List<String> {
        // The FIXED key derivation: globally-unique keys over the whole list,
        // paired to items BY POSITION (exactly what SearchResultsList's
        // keyedResults does — equals-based lookup would collapse duplicates).
        val uniqueKeys = SearchResultKeys.uniqueKeysFor(results)

        // section grouping identical to SearchResultsList, keeping each
        // element's global index
        val grouped = results
            .mapIndexed { index, item -> index to item }
            .groupBy { (_, item) ->
                when (item) {
                    is SearchResultItem.SongItem -> SearchFilterType.SONGS
                    is SearchResultItem.AlbumItem -> SearchFilterType.ALBUMS
                    is SearchResultItem.ArtistItem -> SearchFilterType.ARTISTS
                    is SearchResultItem.PlaylistItem -> SearchFilterType.PLAYLISTS
                    is SearchResultItem.CloudPlaylistItem ->
                        if (item.playlist.isAlbum) SearchFilterType.ALBUMS else SearchFilterType.PLAYLISTS
                    is SearchResultItem.CloudArtistItem -> SearchFilterType.ARTISTS
                }
            }

        val keys = mutableListOf<String>()
        // sectionOrder: SONGS, ALBUMS, ARTISTS, PLAYLISTS
        listOf(
            SearchFilterType.SONGS,
            SearchFilterType.ALBUMS,
            SearchFilterType.ARTISTS,
            SearchFilterType.PLAYLISTS
        ).forEach { filterType ->
            val sectionItems = grouped[filterType] ?: emptyList()
            if (sectionItems.isNotEmpty()) {
                keys += "header_${filterType.name}"
                sectionItems.forEach { (globalIndex, _) ->
                    keys += uniqueKeys[globalIndex]
                }
            }
        }
        // FIXED structure: the load-more row is registered ONCE, AFTER the
        // sectionOrder.forEach loop — not once per section.
        keys += "search_load_more"
        return keys
    }

    private fun reportDuplicates(keys: List<String>) {
        val dupes = keys.groupBy { it }.filter { it.value.size > 1 }
        if (dupes.isNotEmpty()) {
            println("!!! DUPLICATE LAZYCOLUMN KEYS (would crash: 'Key was used multiple times'): ${dupes.keys}")
            fail("Duplicate LazyColumn keys detected: ${dupes.keys} — two simultaneously composed items share a key, which SaveableStateProvider rejects with IllegalArgumentException")
        } else {
            println("no duplicate keys — list structure is crash-safe")
        }
    }

    /**
     * FIX(online-search-chip-crash): models the CloudCatalogScreen track-list
     * keys for a playlist's extracted tracks — the "app crashes after OPENING
     * a playlist" repro. YouTube playlists regularly list the same video
     * twice; the repository now dedupes and the screen's keys are
     * disambiguated via [SearchResultKeys.disambiguate], so this must never
     * produce a duplicate.
     */
    private fun cloudCatalogKeys(songs: List<Song>): List<String> {
        val keys = SearchResultKeys.disambiguate(songs.map { "cloud_song_${it.id}" })
        val dupes = keys.groupBy { it }.filter { it.value.size > 1 }
        if (dupes.isNotEmpty()) {
            fail("Duplicate CloudCatalog track keys detected: ${dupes.keys}")
        }
        // Report the RAW duplicate song ids the provider returned — the
        // repository-level dedupe should have removed them BEFORE the list
        // reached the screen; a non-empty report here means the dedupe
        // regressed (the screen keys would still be safe, but the rows would
        // be duplicated).
        val rawDupes = songs.map { it.id }.groupBy { it }.filter { it.value.size > 1 }
        if (rawDupes.isNotEmpty()) {
            println("NOTE — provider returned repeated track ids (repository dedupe should filter these): ${rawDupes.keys}")
        } else {
            println("track ids are unique after repository dedupe")
        }
        return keys
    }

    @Test
    fun `artists chip search - the shelf-repeat crash case`() = runBlocking {
        println("\n================ YT ARTISTS CHIP ================")
        try {
            val page = repo.searchYouTubePaged("coldplay", SearchFilterType.ARTISTS) { id -> "http://127.0.0.1:1/yt/$id" }
            println("result count: ${page.results.size}, hasMore: ${page.hasMore}, continuation: ${page.continuation?.javaClass?.simpleName}")
            page.results.take(25).forEach { println("  ${describe(it)}") }
            // The raw artist URL dedupe contract — the same channel used to
            // reach the list twice (multiple musicShelfRenderers).
            val urls = page.results.mapNotNull { (it as? SearchResultItem.CloudArtistItem)?.artist?.url }
            println("duplicate artist urls in page 1: " + urls.groupBy { it }.filter { it.value.size > 1 }.keys)
            reportDuplicates(lazyColumnKeys(page.results))
        } catch (t: Throwable) {
            fail("ARTISTS search threw: ${t.javaClass.simpleName}: ${t.message}\n${t.stackTraceToString().take(2500)}")
        }
    }

    @Test
    fun `playlists chip search - the immediate crash case`() = runBlocking {
        println("\n================ YT PLAYLISTS CHIP ================")
        try {
            val page = repo.searchYouTubePaged("coldplay", SearchFilterType.PLAYLISTS) { id -> "http://127.0.0.1:1/yt/$id" }
            println("result count: ${page.results.size}, hasMore: ${page.hasMore}, continuation: ${page.continuation?.javaClass?.simpleName}")
            page.results.take(25).forEach { println("  ${describe(it)}") }
            println("dup item ids: " + page.results.mapNotNull { (it as? SearchResultItem.CloudPlaylistItem)?.playlist?.id }
                .groupBy { it }.filter { it.value.size > 1 }.keys)
            reportDuplicates(lazyColumnKeys(page.results))
        } catch (t: Throwable) {
            fail("PLAYLISTS search threw: ${t.javaClass.simpleName}: ${t.message}\n${t.stackTraceToString().take(2500)}")
        }
    }

    @Test
    fun `songs chip search plus continuation - the scroll crash case`() = runBlocking {
        println("\n================ YT SONGS CHIP ================")
        try {
            val page = repo.searchYouTubePaged("coldplay", SearchFilterType.SONGS) { id -> "http://127.0.0.1:1/yt/$id" }
            println("result count: ${page.results.size}, hasMore: ${page.hasMore}, continuation: ${page.continuation?.javaClass?.simpleName}")
            page.results.take(8).forEach { println("  ${describe(it)}") }
            val ids = page.results.mapNotNull { (it as? SearchResultItem.SongItem)?.song?.id }
            println("duplicate song ids in page 1: " + ids.groupBy { it }.filter { it.value.size > 1 }.keys)
            reportDuplicates(lazyColumnKeys(page.results))

            if (page.continuation != null) {
                println("---- loading page 2 (getMoreYouTubeSearchResults) ----")
                val page2 = repo.getMoreYouTubeSearchResults(
                    query = "coldplay",
                    filter = SearchFilterType.SONGS,
                    previousPage = com.saine.pixeltune.data.model.SearchPage(
                        results = emptyList(), hasMore = true, continuation = page.continuation
                    ),
                    proxyUrlProvider = { id -> "http://127.0.0.1:1/yt/$id" }
                )
                println("page2 count: ${page2.results.size}, hasMore: ${page2.hasMore}")
                page2.results.take(8).forEach { println("  ${describe(it)}") }
                val ids2 = page2.results.mapNotNull { (it as? SearchResultItem.SongItem)?.song?.id }
                println("duplicate song ids in page 2: " + ids2.groupBy { it }.filter { it.value.size > 1 }.keys)
                val combined = ids + ids2
                println("duplicate song ids ACROSS pages: " + combined.groupBy { it }.filter { it.value.size > 1 }.keys)
            }
        } catch (t: Throwable) {
            fail("SONGS search threw: ${t.javaClass.simpleName}: ${t.message}\n${t.stackTraceToString().take(2500)}")
        }
    }

    @Test
    fun `albums chip search - the scroll crash case`() = runBlocking {
        println("\n================ YT ALBUMS CHIP ================")
        try {
            val page = repo.searchYouTubePaged("coldplay", SearchFilterType.ALBUMS) { id -> "http://127.0.0.1:1/yt/$id" }
            println("result count: ${page.results.size}, hasMore: ${page.hasMore}, continuation: ${page.continuation?.javaClass?.simpleName}")
            page.results.take(25).forEach { println("  ${describe(it)}") }
            reportDuplicates(lazyColumnKeys(page.results))
        } catch (t: Throwable) {
            fail("ALBUMS search threw: ${t.javaClass.simpleName}: ${t.message}\n${t.stackTraceToString().take(2500)}")
        }
    }

    @Test
    fun `opening a playlist - the cloud catalog track-key crash case`() = runBlocking {
        println("\n================ YT OPEN PLAYLIST (CloudCatalog keys) ================")
        try {
            val page = repo.searchYouTubePaged("coldplay", SearchFilterType.PLAYLISTS) { id -> "http://127.0.0.1:1/yt/$id" }
            val playlist = (page.results.firstOrNull { it is SearchResultItem.CloudPlaylistItem }
                as? SearchResultItem.CloudPlaylistItem)?.playlist
            if (playlist == null) {
                println("no playlist result — nothing to open, skipping")
                return@runBlocking
            }
            println("opening playlist: ${playlist.name} (${playlist.url})")
            val tracks = repo.getCloudPlaylistTracks(playlist) { id -> "http://127.0.0.1:1/yt/$id" }
                .getOrNull()
            if (tracks == null) {
                println("track extraction failed (network/provider) — skipping key check")
                return@runBlocking
            }
            println("tracks: ${tracks.songs.size}, hasMore: ${tracks.hasMore}")
            tracks.songs.take(10).forEach { println("  song id=${it.id} title=${it.title}") }
            cloudCatalogKeys(tracks.songs)
        } catch (t: Throwable) {
            fail("OPEN PLAYLIST threw: ${t.javaClass.simpleName}: ${t.message}\n${t.stackTraceToString().take(2500)}")
        }
    }

    @Test
    fun `all chip baseline - known working case`() = runBlocking {
        println("\n================ YT ALL CHIP (baseline) ================")
        try {
            val page = repo.searchYouTubePaged("coldplay", SearchFilterType.ALL) { id -> "http://127.0.0.1:1/yt/$id" }
            println("result count: ${page.results.size}, hasMore: ${page.hasMore}, continuation: ${page.continuation?.javaClass?.simpleName}")
            page.results.take(8).forEach { println("  ${describe(it)}") }
            reportDuplicates(lazyColumnKeys(page.results))
        } catch (t: Throwable) {
            fail("ALL search threw: ${t.javaClass.simpleName}: ${t.message}\n${t.stackTraceToString().take(2500)}")
        }
    }
}
