package com.theveloper.pixeltune.data.youtube

import com.theveloper.pixeltune.data.model.SearchFilterType
import com.theveloper.pixeltune.data.model.SearchResultItem
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

    /** The EXACT LazyColumn key logic SearchScreen generates for results. */
    private fun lazyColumnKeys(results: List<SearchResultItem>): List<String> {
        // section grouping identical to SearchResultsList
        data class Section(val type: SearchFilterType, val items: List<SearchResultItem>)
        val grouped = results.groupBy { item: SearchResultItem ->
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
                sectionItems.forEachIndexed { index, item ->
                    keys += when (item) {
                        is SearchResultItem.SongItem -> "song_${item.song.id}"
                        is SearchResultItem.AlbumItem -> "album_${item.album.id}"
                        is SearchResultItem.ArtistItem -> "artist_${item.artist.id}"
                        is SearchResultItem.PlaylistItem -> "playlist_${item.playlist.id}_${index}"
                        is SearchResultItem.CloudPlaylistItem -> "cloud_playlist_${item.playlist.id}_${index}"
                        is SearchResultItem.CloudArtistItem -> "cloud_artist_${item.artist.id}_${index}"
                    }
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
                    previousPage = com.theveloper.pixeltune.data.model.SearchPage(
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
