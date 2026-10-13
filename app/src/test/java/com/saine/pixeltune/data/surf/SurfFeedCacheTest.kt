package com.saine.pixeltune.data.surf

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Test

class SurfFeedCacheTest {

    @Test
    fun `fresh entry is returned without fetching`() = runBlocking {
        val cache = SurfFeedCache<String>(ttlMillis = 1000L, nowMillis = { 5000L })
        cache.putForTest("key", "value")

        var fetches = 0
        val value = cache.getOrFetch("key") { fetches++; "fetched" }

        assertThat(value).isEqualTo("value")
        assertThat(fetches).isEqualTo(0)
        assertThat(cache.isFresh("key")).isTrue()
        assertThat(cache.peek("key")).isEqualTo("value")
    }

    @Test
    fun `expired entry refetches and stores the new value`() = runBlocking {
        var now = 0L
        val cache = SurfFeedCache<String>(ttlMillis = 1000L, nowMillis = { now })
        cache.putForTest("key", "stale")
        now = 5000L // the entry is now older than the TTL

        val value = cache.getOrFetch("key") { "fresh" }

        assertThat(value).isEqualTo("fresh")
        assertThat(cache.peek("key")).isEqualTo("fresh")
    }

    @Test
    fun `stale entry survives a failed refresh`() = runBlocking {
        var now = 0L
        val cache = SurfFeedCache<String>(ttlMillis = 1000L, nowMillis = { now })
        cache.putForTest("key", "stale")
        now = 5000L

        // Stale-while-revalidate: peek() still serves the stale value even
        // after the background refresh failed.
        assertThat(cache.peek("key")).isEqualTo("stale")
        assertThat(cache.isFresh("key")).isFalse()

        val error = runCatching { cache.getOrFetch("key") { error("offline") } }
        assertThat(error.isFailure).isTrue()
        assertThat(cache.peek("key")).isEqualTo("stale")
    }

    @Test
    fun `concurrent fetches for one key single-flight the network call`() = runBlocking {
        val cache = SurfFeedCache<String>(ttlMillis = 10_000L, nowMillis = { 0L })
        var fetches = 0

        val values = (1..5).map {
            async {
                cache.getOrFetch("key") {
                    fetches++
                    kotlinx.coroutines.delay(50)
                    "value"
                }
            }
        }.awaitAll()

        assertThat(values).containsExactly("value", "value", "value", "value", "value")
        assertThat(fetches).isEqualTo(1)
    }

    @Test
    fun `different keys fetch independently`() = runBlocking {
        val cache = SurfFeedCache<Int>(ttlMillis = 10_000L, nowMillis = { 0L })

        val a = cache.getOrFetch("a") { 1 }
        val b = cache.getOrFetch("b") { 2 }

        assertThat(a).isEqualTo(1)
        assertThat(b).isEqualTo(2)
    }

    @Test
    fun `invalidate drops the entry so the next read refetches`() = runBlocking {
        val cache = SurfFeedCache<String>(ttlMillis = 10_000L, nowMillis = { 0L })
        cache.putForTest("key", "old")

        cache.invalidate("key")

        assertThat(cache.peek("key")).isNull()
        val value = cache.getOrFetch("key") { "new" }
        assertThat(value).isEqualTo("new")
    }

    @Test
    fun `missing key reports not fresh`() {
        val cache = SurfFeedCache<String>(ttlMillis = 1000L, nowMillis = { 0L })
        assertThat(cache.isFresh("missing")).isFalse()
        assertThat(cache.peek("missing")).isNull()
    }
}

/** Test-only direct store (the production path always goes through getOrFetch). */
private suspend fun <T : Any> SurfFeedCache<T>.putForTest(key: String, value: T) {
    getOrFetch(key) { value }
}
