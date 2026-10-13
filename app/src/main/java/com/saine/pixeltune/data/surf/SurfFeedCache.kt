package com.saine.pixeltune.data.surf

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * SURF: in-memory TTL cache with stale-while-revalidate semantics for the
 * discovery feed's online pages (trending lists, genre searches).
 *
 * There is no existing TTL cache for extractor RESULTS in the app (the stream
 * proxies only cache resolved URLs), so this small helper follows the proxies'
 * precedent: [ConcurrentHashMap] storage plus a per-key [Mutex] so concurrent
 * collectors single-flight the network fetch instead of hammering the
 * provider. A stale entry is served immediately ([peek]) while
 * [getOrFetch] refreshes it in the background, so opening the tab twice in a
 * row never shows a spinner for data the app already has.
 *
 * The clock is injectable so the TTL behaviour is unit-testable without
 * sleeping the test thread.
 */
class SurfFeedCache<T : Any>(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {

    private data class Entry<T>(val value: T, val storedAtMillis: Long)

    private val entries = ConcurrentHashMap<String, Entry<T>>()
    private val inFlight = ConcurrentHashMap<String, Mutex>()

    /** The cached value if one exists — fresh OR stale (stale-while-revalidate). */
    fun peek(key: String): T? = entries[key]?.value

    /** True when [key] has a value whose age is still within the TTL. */
    fun isFresh(key: String): Boolean {
        val entry = entries[key] ?: return false
        return nowMillis() - entry.storedAtMillis < ttlMillis
    }

    /**
     * Returns the fresh cached value, or fetches and stores a new one
     * (single-flight per key). A failed [fetch] propagates AFTER leaving any
     * previously stored value untouched, so a stale entry survives a network
     * hiccup and keeps serving [peek].
     */
    suspend fun getOrFetch(key: String, fetch: suspend () -> T): T {
        entries[key]?.let { entry ->
            if (nowMillis() - entry.storedAtMillis < ttlMillis) return entry.value
        }
        val mutex = inFlight.getOrPut(key) { Mutex() }
        return mutex.withLock {
            // Another collector may have refreshed the entry while this
            // caller waited on the mutex — re-check before hitting the network.
            entries[key]?.let { entry ->
                if (nowMillis() - entry.storedAtMillis < ttlMillis) return@withLock entry.value
            }
            val value = fetch()
            entries[key] = Entry(value, nowMillis())
            value
        }
    }

    /** Drops the cached value for [key] (provider switch, manual retry). */
    fun invalidate(key: String) {
        entries.remove(key)
    }

    fun clear() {
        entries.clear()
    }

    companion object {
        /** 30 minutes — trending lists and genre charts change slowly. */
        const val DEFAULT_TTL_MILLIS: Long = 30L * 60L * 1000L
    }
}

/**
 * SURF: the shared cache-key format for every [SurfFeedCache] entry. The tab
 * ([com.saine.pixeltune.presentation.viewmodel.SurfViewModel]) and the "see
 * all" list ([com.saine.pixeltune.presentation.viewmodel.SurfListViewModel])
 * must derive the SAME key for the same provider feed or they would each
 * keep a private copy — these helpers pin that contract in one place.
 */
object SurfFeedKeys {
    fun trending(providerName: String): String = "trending_$providerName"
    fun related(providerName: String, seedId: String): String = "related_${providerName}_$seedId"
}
