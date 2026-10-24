package com.saine.pixeltune.presentation.viewmodel

import app.cash.turbine.test
import com.saine.pixeltune.MainCoroutineExtension
import com.saine.pixeltune.data.model.SearchFilterType
import com.saine.pixeltune.data.model.SearchHistoryItem
import com.saine.pixeltune.data.model.SearchPage
import com.saine.pixeltune.data.model.SearchResultItem
import com.saine.pixeltune.data.model.Song
import com.saine.pixeltune.data.preferences.UserPreferencesRepository
import com.saine.pixeltune.data.repository.MusicRepository
import com.saine.pixeltune.data.soundcloud.SoundCloudRepository
import com.saine.pixeltune.data.soundcloud.SoundCloudStreamProxy
import com.saine.pixeltune.data.youtube.YouTubeRepository
import com.saine.pixeltune.data.youtube.YouTubeStreamProxy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.io.IOException

/**
 * Unit tests for the ONLINE SEARCH AUTOCOMPLETE pipeline in
 * [SearchStateHolder] — the suggestion StateFlows, the debounced
 * provider requests, stale-response invalidation and the interaction with
 * the existing search / history flow.
 *
 * The provider repositories are mocked (no live YouTube / SoundCloud
 * requests), and the suggestion pipeline's IO dispatcher is swapped for the
 * test scheduler so debounce windows, cancellation and stale-guard behavior
 * can be asserted with deterministic virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MainCoroutineExtension::class)
class SearchStateHolderSuggestionsTest {

    /** Mirrors SearchStateHolder.SUGGESTION_DEBOUNCE_MS (private in the holder). */
    private val suggestionDebounceMs = 300L

    /** Mirrors SearchStateHolder.MIN_SUGGESTION_QUERY_LENGTH (private in the holder). */
    private val minSuggestionQueryLength = 2

    private val mockMusicRepository: MusicRepository = mockk()
    private val mockYouTubeRepository: YouTubeRepository = mockk()
    private val mockYouTubeStreamProxy: YouTubeStreamProxy = mockk(relaxed = true)
    private val mockSoundCloudRepository: SoundCloudRepository = mockk()
    private val mockSoundCloudStreamProxy: SoundCloudStreamProxy = mockk(relaxed = true)
    private val mockUserPreferencesRepository: UserPreferencesRepository = mockk(relaxed = true)

    private lateinit var holder: SearchStateHolder

    /** Per-test scopes passed to [SearchStateHolder.initialize]; cancelled in [tearDown]. */
    private val holderScopes = mutableListOf<CoroutineScope>()

    @BeforeEach
    fun setUp() {
        // SearchStateHolder.initialize() observes the history-limit preference
        // flow — feed it the default so the collector is a no-op.
        every { mockUserPreferencesRepository.onlineSearchHistoryLimitFlow } returns flowOf(
            UserPreferencesRepository.DEFAULT_ONLINE_SEARCH_HISTORY_LIMIT
        )

        holder = SearchStateHolder(
            musicRepository = mockMusicRepository,
            youTubeRepository = mockYouTubeRepository,
            youTubeStreamProxy = mockYouTubeStreamProxy,
            soundCloudRepository = mockSoundCloudRepository,
            soundCloudStreamProxy = mockSoundCloudStreamProxy,
            userPreferencesRepository = mockUserPreferencesRepository
        )
    }

    @AfterEach
    fun tearDown() {
        holderScopes.forEach { it.cancel() }
        holderScopes.clear()
    }

    /**
     * Wires the holder's suggestion pipeline onto THIS test's scheduler
     * (virtual time controls debounce + fetch) and starts the observers on a
     * scope sharing that scheduler, like PlayerViewModel does with
     * viewModelScope in production.
     *
     * NOTE: a plain child-Job scope on the test scheduler is used instead of
     * [TestScope.backgroundScope] — in kotlinx-coroutines-test 1.9.0,
     * backgroundScope tasks are not executed by explicit advanceUntilIdle()
     * calls from the test body, which would silently drop every debounced
     * request. The scope is tracked and cancelled in tearDown.
     *
     * The scheduler is advanced once BEFORE any request so the debounced
     * collector actually SUBSCRIBES first — a no-replay SharedFlow drops
     * emissions that happen before subscribers exist (in production the
     * Main.immediate scope makes the collector subscribe synchronously, so
     * this race is test-only).
     */
    private fun TestScope.initHolder(): SearchStateHolder {
        holder.suggestionsIoDispatcher = StandardTestDispatcher(testScheduler)
        val holderScope = CoroutineScope(coroutineContext + Job())
        holderScopes += holderScope
        holder.initialize(holderScope)
        advanceUntilIdle()
        return holder
    }

    // -------------------------------------------------------------------------
    // 1. Minimum query length
    // -------------------------------------------------------------------------

    @Test
    fun `queries shorter than the minimum length do not issue autocomplete requests`() = runTest {
        initHolder()

        holder.requestSuggestions("a")
        holder.requestSuggestions(" ")
        holder.requestSuggestions("")
        advanceUntilIdle()

        coVerify(exactly = 0) { mockYouTubeRepository.getSuggestions(any()) }
        coVerify(exactly = 0) { mockSoundCloudRepository.getSuggestions(any()) }
        assertTrue(holder.searchSuggestions.value.isEmpty())
        assertFalse(holder.isLoadingSuggestions.value)
    }

    @Test
    fun `a two character query is long enough to request suggestions`() = runTest {
        initHolder()
        val query = "ab".repeat(1)
        assertTrue(query.length >= minSuggestionQueryLength)
        coEvery { mockYouTubeRepository.getSuggestions(query) } returns listOf("abba", "about")

        holder.requestSuggestions(query)
        advanceUntilIdle()

        coVerify(exactly = 1) { mockYouTubeRepository.getSuggestions(query) }
        assertEquals(listOf("abba", "about"), holder.searchSuggestions.value)
    }

    // -------------------------------------------------------------------------
    // 2. Valid queries fetch suggestions from the active provider
    // -------------------------------------------------------------------------

    @Test
    fun `typing a valid query eventually requests suggestions from the active provider`() = runTest {
        initHolder()
        coEvery { mockYouTubeRepository.getSuggestions("one piece") } returns listOf(
            "one piece opening",
            "one piece ost"
        )

        holder.requestSuggestions("one piece")
        // Still inside the debounce window: nothing has been requested yet.
        advanceTimeBy(suggestionDebounceMs - 50)
        coVerify(exactly = 0) { mockYouTubeRepository.getSuggestions(any()) }

        // Past the debounce window: the request fired and published.
        advanceTimeBy(100)
        coVerify(exactly = 1) { mockYouTubeRepository.getSuggestions("one piece") }
        assertEquals(listOf("one piece opening", "one piece ost"), holder.searchSuggestions.value)
        assertFalse(holder.isLoadingSuggestions.value)
    }

    @Test
    fun `the suggestion state flow is observable`() = runTest {
        initHolder()
        coEvery { mockYouTubeRepository.getSuggestions("one piece") } returns listOf("one piece ost")

        holder.searchSuggestions.test {
            // Initial value: no suggestions.
            assertTrue(awaitItem().isEmpty())

            holder.requestSuggestions("one piece")
            advanceUntilIdle()

            assertEquals(listOf("one piece ost"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // -------------------------------------------------------------------------
    // 3. Debouncing
    // -------------------------------------------------------------------------

    @Test
    fun `debouncing coalesces rapid query edits into a single provider request`() = runTest {
        initHolder()
        coEvery { mockYouTubeRepository.getSuggestions(any()) } returns emptyList()

        holder.requestSuggestions("on")
        advanceTimeBy(100)
        holder.requestSuggestions("one")
        advanceTimeBy(100)
        holder.requestSuggestions("one p")
        advanceTimeBy(100)
        holder.requestSuggestions("one piece")
        advanceUntilIdle()

        // Only the FINAL query produced a provider request — never one per keystroke.
        coVerify(exactly = 1) { mockYouTubeRepository.getSuggestions("one piece") }
        coVerify(exactly = 0) { mockYouTubeRepository.getSuggestions("on") }
        coVerify(exactly = 0) { mockYouTubeRepository.getSuggestions("one") }
        coVerify(exactly = 0) { mockYouTubeRepository.getSuggestions("one p") }
    }

    // -------------------------------------------------------------------------
    // 4. Suggestions correspond to the latest query
    // -------------------------------------------------------------------------

    @Test
    fun `suggestions always correspond to the latest query`() = runTest {
        initHolder()
        coEvery { mockYouTubeRepository.getSuggestions("one") } returns listOf("one ok")
        coEvery { mockYouTubeRepository.getSuggestions("only one") } returns listOf("only one ok")

        holder.requestSuggestions("one")
        advanceUntilIdle()
        assertEquals(listOf("one ok"), holder.searchSuggestions.value)

        holder.requestSuggestions("only one")
        advanceUntilIdle()
        assertEquals(listOf("only one ok"), holder.searchSuggestions.value)
    }

    // -------------------------------------------------------------------------
    // 5. Stale responses cannot replace newer suggestions
    // -------------------------------------------------------------------------

    @Test
    fun `a slow response for an older query can never overwrite newer suggestions`() = runTest {
        initHolder()
        // Simulates a request whose cancellation cannot stop the underlying
        // network call — it completes and returns long after it was replaced.
        // (collectLatest cannot interrupt a non-cancellable fetch, so the
        // newer request's handling resumes only once the stale one lands.)
        coEvery { mockYouTubeRepository.getSuggestions("one") } coAnswers {
            withContext(NonCancellable) {
                delay(1_000)
                listOf("one ok")
            }
        }
        coEvery { mockYouTubeRepository.getSuggestions("only one") } returns listOf("only one ok")

        holder.requestSuggestions("one")
        advanceTimeBy(suggestionDebounceMs + 100) // fetch for "one" is in flight

        holder.requestSuggestions("only one")
        // Run EVERYTHING out: the stale "one" response lands, is discarded by
        // the request-id guard, and the newer query's suggestions publish.
        advanceUntilIdle()

        assertEquals(listOf("only one ok"), holder.searchSuggestions.value)
        // The stale response was delivered — but never published.
        coVerify(exactly = 1) { mockYouTubeRepository.getSuggestions("one") }
        assertFalse(holder.isLoadingSuggestions.value)
    }

    @Test
    fun `a response that outlives a cleared panel cannot repopulate it`() = runTest {
        initHolder()
        coEvery { mockYouTubeRepository.getSuggestions("one piece") } coAnswers {
            withContext(NonCancellable) {
                delay(1_000)
                listOf("one piece ost")
            }
        }

        holder.requestSuggestions("one piece")
        advanceTimeBy(suggestionDebounceMs + 100) // in flight

        // The user commits the query — the panel must hide and stay hidden.
        holder.clearSuggestions()
        assertTrue(holder.searchSuggestions.value.isEmpty())

        advanceUntilIdle() // the late response lands after the clear
        assertTrue(holder.searchSuggestions.value.isEmpty())
        assertFalse(holder.isLoadingSuggestions.value)
    }

    // -------------------------------------------------------------------------
    // 6. Blank queries clear the suggestions
    // -------------------------------------------------------------------------

    @Test
    fun `blank queries clear the suggestions without a provider request`() = runTest {
        initHolder()
        coEvery { mockYouTubeRepository.getSuggestions("one piece") } returns listOf("one piece ost")

        holder.requestSuggestions("one piece")
        advanceUntilIdle()
        assertTrue(holder.searchSuggestions.value.isNotEmpty())

        holder.requestSuggestions("")
        // Cleared IMMEDIATELY (no debounce) and nothing re-fetched.
        assertTrue(holder.searchSuggestions.value.isEmpty())
        assertFalse(holder.isLoadingSuggestions.value)
        advanceUntilIdle()
        coVerify(exactly = 1) { mockYouTubeRepository.getSuggestions(any()) }
    }

    // -------------------------------------------------------------------------
    // 7. Switching to Local mode
    // -------------------------------------------------------------------------

    @Test
    fun `switching to Local mode clears suggestions and invalidates in-flight requests`() = runTest {
        initHolder()
        coEvery { mockYouTubeRepository.getSuggestions("one piece") } coAnswers {
            withContext(NonCancellable) {
                delay(1_000)
                listOf("one piece ost")
            }
        }

        holder.requestSuggestions("one piece")
        advanceTimeBy(suggestionDebounceMs + 100)
        assertTrue(holder.isLoadingSuggestions.value)

        holder.toggleSearchMode(false)
        assertTrue(holder.searchSuggestions.value.isEmpty())

        advanceUntilIdle() // the in-flight response lands AFTER the switch
        assertTrue(holder.searchSuggestions.value.isEmpty())
        assertFalse(holder.isLoadingSuggestions.value)
    }

    @Test
    fun `no autocomplete requests are issued while in Local mode`() = runTest {
        initHolder()
        holder.toggleSearchMode(false)

        holder.requestSuggestions("one piece")
        advanceUntilIdle()

        coVerify(exactly = 0) { mockYouTubeRepository.getSuggestions(any()) }
        coVerify(exactly = 0) { mockSoundCloudRepository.getSuggestions(any()) }
        assertTrue(holder.searchSuggestions.value.isEmpty())
    }

    // -------------------------------------------------------------------------
    // 8. Provider switching
    // -------------------------------------------------------------------------

    @Test
    fun `changing providers uses the correct provider for subsequent requests`() = runTest {
        initHolder()
        coEvery { mockYouTubeRepository.getSuggestions("one piece") } returns listOf("yt suggestion")
        coEvery { mockSoundCloudRepository.getSuggestions("one piece") } returns listOf("sc suggestion")

        holder.requestSuggestions("one piece")
        advanceUntilIdle()
        coVerify(exactly = 1) { mockYouTubeRepository.getSuggestions("one piece") }
        assertEquals(listOf("yt suggestion"), holder.searchSuggestions.value)

        holder.setOnlineProvider(SearchStateHolder.OnlineProvider.SOUNDCLOUD)
        // Suggestions from the OLD provider are dropped immediately.
        assertTrue(holder.searchSuggestions.value.isEmpty())

        holder.requestSuggestions("one piece")
        advanceUntilIdle()
        coVerify(exactly = 1) { mockSoundCloudRepository.getSuggestions("one piece") }
        coVerify(exactly = 1) { mockYouTubeRepository.getSuggestions("one piece") }
        assertEquals(listOf("sc suggestion"), holder.searchSuggestions.value)
    }

    @Test
    fun `a debounced request that outlives a provider switch is discarded`() = runTest {
        initHolder()
        coEvery { mockYouTubeRepository.getSuggestions("one piece") } coAnswers {
            withContext(NonCancellable) {
                delay(1_000)
                listOf("yt suggestion")
            }
        }

        holder.requestSuggestions("one piece")
        advanceTimeBy(suggestionDebounceMs + 100) // in flight on the old provider

        holder.setOnlineProvider(SearchStateHolder.OnlineProvider.SOUNDCLOUD)
        advanceUntilIdle()

        // The old provider's late response must not repopulate the panel.
        assertTrue(holder.searchSuggestions.value.isEmpty())
        assertFalse(holder.isLoadingSuggestions.value)
    }

    // -------------------------------------------------------------------------
    // 10. Regular search works even when suggestions are unavailable
    // -------------------------------------------------------------------------

    @Test
    fun `a suggestion failure never blocks the normal search pipeline`() = runTest {
        initHolder()
        coEvery { mockYouTubeRepository.getSuggestions("one piece") } throws IOException("network down")

        val song = Song(
            id = "yt-1",
            title = "One Piece Opening 1",
            artist = "Artist",
            album = "Album",
            genre = null,
            albumArtUriString = "",
            artistId = 1L,
            albumId = 1L,
            contentUriString = "http://proxy/yt-1",
            duration = 120_000L,
            bitrate = null,
            sampleRate = null,
            path = "",
            mimeType = "audio/mpeg"
        )
        coEvery {
            mockYouTubeRepository.searchYouTubePaged("one piece", any(), any())
        } returns SearchPage(results = listOf(SearchResultItem.SongItem(song)))

        // Suggestions fail...
        holder.requestSuggestions("one piece")
        advanceUntilIdle()
        assertTrue(holder.searchSuggestions.value.isEmpty())
        assertFalse(holder.isLoadingSuggestions.value)

        // ...and the normal search pipeline still runs for the same query.
        holder.performSearch("one piece")
        advanceUntilIdle()
        // The search pipeline hops the real Dispatchers.IO; give the mock's
        // response a moment to publish (bounded real-time wait).
        awaitTrue { holder.searchResults.value.isNotEmpty() }
        assertEquals(1, holder.searchResults.value.size)
    }

    // -------------------------------------------------------------------------
    // 11. Existing search history behavior
    // -------------------------------------------------------------------------

    @Test
    fun `search history recording keeps working alongside suggestions`() = runTest {
        initHolder()
        coEvery { mockYouTubeRepository.getSuggestions("one piece") } returns listOf("one piece ost")
        coEvery { mockMusicRepository.addSearchHistoryItem(any(), any()) } returns Unit
        coEvery { mockMusicRepository.getRecentSearchHistory(any()) } returns listOf(
            SearchHistoryItem(query = "one piece", timestamp = 1L)
        )

        holder.requestSuggestions("one piece")
        holder.onSearchQuerySubmitted("one piece")
        advanceUntilIdle()

        // History uses the real Dispatchers.IO internally — bounded real wait.
        awaitTrue {
            holder.searchHistory.value.any { it.query == "one piece" }
        }
        coVerify(atLeast = 1) { mockMusicRepository.addSearchHistoryItem("one piece", any()) }
        assertEquals("one piece", holder.searchHistory.value.first().query)
    }

    // -------------------------------------------------------------------------
    // 13. Failure handling
    // -------------------------------------------------------------------------

    @Test
    fun `network or extractor failures do not crash or leave the loading indicator stuck`() = runTest {
        initHolder()
        coEvery { mockYouTubeRepository.getSuggestions("one piece") } throws RuntimeException("extractor blew up")

        holder.requestSuggestions("one piece")
        advanceTimeBy(suggestionDebounceMs + 100)

        // No crash: loading stopped, panel empty, query independent state intact.
        assertFalse(holder.isLoadingSuggestions.value)
        assertTrue(holder.searchSuggestions.value.isEmpty())
    }

    @Test
    fun `loading is exposed while a request is in flight and cleared when it finishes`() = runTest {
        initHolder()
        coEvery { mockYouTubeRepository.getSuggestions("one piece") } coAnswers {
            delay(500)
            listOf("one piece ost")
        }

        holder.requestSuggestions("one piece")
        advanceTimeBy(suggestionDebounceMs + 100)
        assertTrue(holder.isLoadingSuggestions.value)

        advanceUntilIdle()
        assertFalse(holder.isLoadingSuggestions.value)
        assertEquals(listOf("one piece ost"), holder.searchSuggestions.value)
    }

    @Test
    fun `a suggestion request cancelled by a newer one leaves no stuck loading flag`() = runTest {
        initHolder()
        coEvery { mockYouTubeRepository.getSuggestions("one") } coAnswers {
            delay(1_000)
            listOf("one ok")
        }
        coEvery { mockYouTubeRepository.getSuggestions("only one") } returns listOf("only one ok")

        holder.requestSuggestions("one")
        advanceTimeBy(suggestionDebounceMs + 100) // first fetch in flight
        assertTrue(holder.isLoadingSuggestions.value)

        holder.requestSuggestions("only one") // supersedes + cancels the first
        advanceUntilIdle()

        assertFalse(holder.isLoadingSuggestions.value)
        assertEquals(listOf("only one ok"), holder.searchSuggestions.value)
    }

    // -------------------------------------------------------------------------

    /**
     * Waits (real time, bounded) for [condition] to become true. Needed for
     * assertions that depend on the existing search / history pipelines,
     * which run on the REAL Dispatchers.IO in unit tests.
     */
    private suspend fun awaitTrue(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        withContext(Dispatchers.Default) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!condition() && System.currentTimeMillis() < deadline) {
                delay(10)
            }
        }
        assertTrue(condition(), "Condition was not met within ${timeoutMs}ms")
    }
}
