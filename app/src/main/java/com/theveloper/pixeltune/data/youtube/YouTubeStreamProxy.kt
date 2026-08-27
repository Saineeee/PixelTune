package com.theveloper.pixeltune.data.youtube

import android.net.Uri
import com.theveloper.pixeltune.data.stream.CloudStreamForwarder
import com.theveloper.pixeltune.data.stream.CloudStreamSecurity
import com.theveloper.pixeltune.data.stream.ForwardOutcome
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.engine.*
import io.ktor.server.cio.*
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import com.theveloper.pixeltune.di.StreamingOkHttpClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class YouTubeStreamProxy @Inject constructor(
    private val repository: YouTubeRepository,
    @StreamingOkHttpClient private val okHttpClient: OkHttpClient,
    private val userPreferencesRepository: com.theveloper.pixeltune.data.preferences.UserPreferencesRepository
) {
    private companion object {
        val ALLOWED_REMOTE_HOST_SUFFIXES = setOf(
            "googlevideo.com",
            "youtube.com",
            "ytimg.com"
        )

        /**
         * FIX(cloud-streaming-speed): upstream status codes that mean "the
         * cached stream URL went stale" — googlevideo signed URLs carry an
         * `expire` parameter, and once it passes, the CDN answers 403/404.
         * When the forwarder sees one of these, it does NOT answer ExoPlayer;
         * instead the proxy re-resolves the stream URL once and retries.
         */
        val STALE_UPSTREAM_STATUS_CODES = setOf(403, 404, 410)
    }

    private var server: ApplicationEngine? = null
    private var actualPort: Int = 0
    private val proxyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startJob: Job? = null

    // Cache of resolved streaming URLs
    private val urlCache = ConcurrentHashMap<String, CachedUrl>()

    /**
     * FIX(cloud-streaming-speed): one mutex per cache key, serializing
     * stream-URL resolutions for the same video.
     *
     * ExoPlayer opens a media source's DataSource multiple times in quick
     * succession (header/moov reads, ranged re-reads, and every seek).
     * Without serialization, EACH of those proxy requests that missed the
     * URL cache kicked off its OWN full NewPipe extraction — 6-7 sequential
     * network requests each, in parallel — doubling/tripling playback-start
     * latency and multiplying the innertube request volume against YouTube
     * (which itself raises bot-check risk). With the mutex, concurrent
     * callers wait for the first resolution and then hit the freshly
     * populated cache.
     */
    private val resolutionMutexes = ConcurrentHashMap<String, Mutex>()

    // FIX(playback-start-latency): video ids with an in-flight background
    // prefetch — see [prefetch].
    private val prefetchInFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private data class CachedUrl(val url: String, val timestamp: Long) {
        fun isExpired(): Boolean {
            try {
                val uri = android.net.Uri.parse(url)
                val expireParam = uri.getQueryParameter("expire")?.toLongOrNull()
                if (expireParam != null) {
                    // expireParam is in seconds. Convert to ms and add a 5-minute safety buffer.
                    return System.currentTimeMillis() > (expireParam * 1000L) - (5 * 60 * 1000L)
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to parse expire param from cached URL")
            }
            // Fallback to 5 hours if no expire param is found
            return System.currentTimeMillis() - timestamp > 5 * 60 * 60 * 1000
        }
    }

    fun isReady(): Boolean = actualPort > 0

    suspend fun awaitReady(timeoutMs: Long = 10_000L): Boolean {
        if (isReady()) return true

        val stepMs = 50L
        var elapsed = 0L
        while (elapsed < timeoutMs) {
            if (isReady()) return true
            delay(stepMs)
            elapsed += stepMs
        }
        return false
    }

    fun getProxyUrl(youtubeId: String): String {
        if (actualPort == 0) {
            Timber.w("YouTubeStreamProxy: getProxyUrl called but actualPort is 0")
            return ""
        }
        // Basic validation for YouTube ID format
        if (!youtubeId.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) {
             Timber.w("YouTubeStreamProxy: getProxyUrl rejected invalid youtubeId: $youtubeId")
             return ""
        }
        return "http://127.0.0.1:$actualPort/youtube/$youtubeId"
    }

    fun resolveYouTubeUri(uriString: String): String? {
        val uri = Uri.parse(uriString)
        if (uri.scheme != "youtube") return null
        val youtubeId = uri.host ?: return null
        if (!youtubeId.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) return null
        return getProxyUrl(youtubeId)
    }

    /**
     * FIX(playback-start-latency): warms the resolved-stream-URL cache for the
     * given PROXY url (e.g. "http://127.0.0.1:port/youtube/<id>") in the
     * background, so ExoPlayer's first request for that song doesn't pay the
     * full NewPipe extraction cost (4-5 sequential /player + /next requests,
     * several seconds) on the playback critical path.
     *
     * Called by MusicService whenever a new song becomes the current one to
     * prefetch the NEXT queued song — by the time the user skips or the queue
     * auto-advances, the URL is already resolved and playback starts instantly.
     *
     * Safe by construction:
     *  - accepts only URLs this proxy itself produced (scheme http/https,
     *    loopback host, "/youtube/{11-char id}" path) — anything else is
     *    ignored;
     *  - skips when a fresh (non-expired) cache entry already exists;
     *  - an in-flight guard dedupes concurrent prefetches for the same id so
     *    rapid track changes never spawn duplicate extractions;
     *  - runs on the proxy's own IO scope; failures are logged and swallowed —
     *    a prefetch can never disturb playback (the real request will simply
     *    resolve on demand, exactly as before).
     */
    fun prefetch(proxyUrl: String) {
        if (actualPort == 0) return
        val youtubeId = parseOwnProxyUrl(proxyUrl) ?: return

        // One in-flight prefetch per video id (checked synchronously, without
        // touching DataStore — the quality/cache checks happen inside the
        // coroutine below so this method never blocks its caller, which is the
        // main thread when invoked from MusicService's player listener).
        if (!prefetchInFlight.add(youtubeId)) return

        proxyScope.launch {
            try {
                val resolved = getOrFetchStreamUrl(youtubeId)
                if (resolved != null) {
                    Timber.d("YouTubeStreamProxy: prefetched stream URL for $youtubeId")
                } else {
                    Timber.d("YouTubeStreamProxy: prefetch found no stream URL for $youtubeId")
                }
            } catch (e: Exception) {
                Timber.d(e, "YouTubeStreamProxy: background prefetch failed for $youtubeId")
            } finally {
                prefetchInFlight.remove(youtubeId)
            }
        }
    }

    /** Extracts the video id from one of THIS proxy's own URLs, or null. */
    private fun parseOwnProxyUrl(proxyUrl: String): String? {
        return try {
            val uri = Uri.parse(proxyUrl)
            val isLoopback = uri.host == "127.0.0.1" || uri.host == "localhost"
            if (!isLoopback) return null
            val path = uri.path ?: return null
            val segments = path.split("/").filter { it.isNotEmpty() }
            if (segments.size != 2 || segments[0] != "youtube") return null
            val id = segments[1]
            if (!id.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) null else id
        } catch (e: Exception) {
            null
        }
    }

    fun start() {
        startJob?.cancel()
        startJob = proxyScope.launch {
            try {
                val freePort = ServerSocket(0).use { it.localPort }
                val createdServer = createServer(freePort)
                createdServer.start(wait = false)
                server = createdServer
                actualPort = freePort
                Timber.d("YouTubeStreamProxy started on port $actualPort")
            } catch (e: CancellationException) {
                Timber.d("YouTubeStreamProxy start cancelled")
            } catch (e: Exception) {
                Timber.e(e, "Failed to start YouTubeStreamProxy")
            }
        }
    }

    fun stop() {
        startJob?.cancel()
        startJob = null
        proxyScope.coroutineContext.cancelChildren()
        server?.stop(1000, 2000)
        server = null
        actualPort = 0
        urlCache.clear()
        Timber.d("YouTubeStreamProxy stopped")
    }

    private fun createServer(port: Int): ApplicationEngine {
        return embeddedServer(CIO, host = "127.0.0.1", port = port) {
            routing {
                get("/youtube/{youtubeId}") {
                    val youtubeId = call.parameters["youtubeId"]
                    if (youtubeId == null || !youtubeId.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) {
                        call.respond(HttpStatusCode.BadRequest, "Invalid YouTube ID")
                        return@get
                    }

                    try {
                        val rangeValidation = CloudStreamSecurity.validateRangeHeader(call.request.headers["Range"])
                        if (!rangeValidation.isValid) {
                            call.respond(HttpStatusCode(416, "Range Not Satisfiable"), "Invalid range header")
                            return@get
                        }

                        val streamUrl = getOrFetchStreamUrl(youtubeId)
                        if (streamUrl == null) {
                            call.respond(HttpStatusCode.NotFound, "No stream URL available")
                            return@get
                        }

                        if (!CloudStreamSecurity.isSafeRemoteStreamUrl(
                                url = streamUrl,
                                allowedHostSuffixes = ALLOWED_REMOTE_HOST_SUFFIXES,
                                allowHttpForAllowedHosts = true
                            )
                        ) {
                            call.respond(HttpStatusCode.BadGateway, "Rejected upstream stream URL")
                            return@get
                        }

                        // Build the upstream OkHttp request.
                        //
                        // The browser User-Agent is REQUIRED — YouTube serves a
                        // "page needs to be reloaded" bot-check page to non-browser
                        // UAs, which surfaces as a 404 to ExoPlayer and keeps the
                        // player frozen at 00:00. The streaming OkHttpClient
                        // (see @StreamingOkHttpClient in di/) only injects a
                        // default app UA when no UA is present, so this explicit
                        // header wins end-to-end.
                        //
                        // Accept-Encoding: identity disables gzip so the upstream
                        // byte ranges line up 1:1 with what we forward to
                        // ExoPlayer — Media3's DefaultHttpDataSource relies on
                        // exact byte offsets for Range-based seeking.
                        fun buildUpstreamRequest(url: String): Request {
                            val builder = Request.Builder()
                                .url(url)
                                .header("Accept-Encoding", "identity")
                                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                            rangeValidation.normalizedHeader?.let {
                                builder.header("Range", it)
                            }
                            return builder.build()
                        }

                        // FIX: Stream the upstream response chunk-by-chunk through
                        // CloudStreamForwarder instead of buffering the entire body
                        // in memory via OkHttp's `bytes()`. This is the actual fix
                        // for the "YouTube playback stuck at 00:00" bug.
                        //
                        // The forwarder:
                        //   1. Opens the upstream connection on Dispatchers.IO.
                        //   2. Sets Ktor response status + headers IMMEDIATELY
                        //      (Ktor flushes them before the body lambda runs).
                        //   3. Streams bytes from the OkHttp InputStream to the
                        //      Ktor OutputStream in 8 KB chunks.
                        //
                        // Combined with the streaming OkHttpClient's
                        // readTimeout=0, throttled YouTube streams no longer
                        // abort mid-read, and ExoPlayer receives the HTTP
                        // status line + first audio bytes within milliseconds
                        // of the upstream's response.
                        //
                        // FIX(cloud-streaming-speed): the deferred status codes
                        // make the forwarder return INSTEAD of answering
                        // ExoPlayer when googlevideo rejects the cached signed
                        // URL (403/404/410 — the URL's `expire` parameter has
                        // passed). The self-heal below then re-resolves the
                        // stream URL once and forwards again, so a seek that
                        // lands after URL expiry recovers in ~one extraction
                        // instead of failing playback outright.
                        val outcome = CloudStreamForwarder.forwardStream(
                            call = call,
                            client = okHttpClient,
                            upstreamRequest = buildUpstreamRequest(streamUrl),
                            deferUpstreamStatusCodes = STALE_UPSTREAM_STATUS_CODES
                        )

                        if (outcome is ForwardOutcome.UpstreamRejected) {
                            Timber.d(
                                "YouTubeStreamProxy: cached stream URL for %s rejected " +
                                    "upstream (code=%d) — re-resolving once",
                                youtubeId, outcome.statusCode
                            )
                            val freshUrl = runCatching {
                                getOrFetchStreamUrl(youtubeId, forceRefresh = true)
                            }.getOrNull()

                            if (freshUrl != null &&
                                CloudStreamSecurity.isSafeRemoteStreamUrl(
                                    url = freshUrl,
                                    allowedHostSuffixes = ALLOWED_REMOTE_HOST_SUFFIXES,
                                    allowHttpForAllowedHosts = true
                                )
                            ) {
                                val retryOutcome = CloudStreamForwarder.forwardStream(
                                    call = call,
                                    client = okHttpClient,
                                    upstreamRequest = buildUpstreamRequest(freshUrl)
                                )
                                if (retryOutcome is ForwardOutcome.UpstreamRejected) {
                                    call.respond(
                                        HttpStatusCode.BadGateway,
                                        "Upstream rejected refreshed stream URL (code=${retryOutcome.statusCode})"
                                    )
                                }
                            } else {
                                call.respond(
                                    HttpStatusCode.BadGateway,
                                    "Upstream stream URL expired (code=${outcome.statusCode})"
                                )
                            }
                        }
                    } catch (e: Exception) {
                        val msg = e.toString()
                        if (msg.contains("ChannelWriteException") ||
                            msg.contains("ClosedChannelException") ||
                            msg.contains("Broken pipe") ||
                            msg.contains("JobCancellationException")) {
                            // Client disconnected, normal behavior
                        } else {
                            Timber.e(e, "Error streaming YouTube video $youtubeId")
                        }
                    }
                }
            }
        }
    }

    /**
     * Resolves (or reuses) the googleaudio stream URL for [youtubeId].
     *
     * FIX(cloud-streaming-speed):
     *  - Mutex-per-key dedup — see [resolutionMutexes].
     *  - [forceRefresh] bypasses and evicts the cache entry first; used by
     *    the stale-URL self-heal path after the CDN rejected a previously
     *    cached URL (403/404/410).
     */
    private suspend fun getOrFetchStreamUrl(youtubeId: String, forceRefresh: Boolean = false): String? {
        val quality = userPreferencesRepository.streamingQualityFlow.first()
        val cacheKey = "${youtubeId}_${quality.name}"

        if (forceRefresh) {
            urlCache.remove(cacheKey)
        } else {
            urlCache[cacheKey]?.let { cached ->
                if (!cached.isExpired()) return cached.url
            }
        }

        val mutex = resolutionMutexes.computeIfAbsent(cacheKey) { Mutex() }
        return mutex.withLock {
            // Double-check inside the lock: another proxy request may have
            // completed the extraction while this caller was waiting.
            if (!forceRefresh) {
                urlCache[cacheKey]?.let { cached ->
                    if (!cached.isExpired()) return@withLock cached.url
                }
            }
            val result = repository.getAudioStreamUrl(youtubeId, quality)
            result.getOrNull()?.also { url ->
                urlCache[cacheKey] = CachedUrl(url, System.currentTimeMillis())
            }
        }
    }
}
