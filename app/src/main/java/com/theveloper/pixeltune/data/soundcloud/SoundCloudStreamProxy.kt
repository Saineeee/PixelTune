package com.theveloper.pixeltune.data.soundcloud

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
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import com.theveloper.pixeltune.data.preferences.UserPreferencesRepository
import com.theveloper.pixeltune.di.StreamingOkHttpClient
import kotlinx.coroutines.flow.first

@Singleton
class SoundCloudStreamProxy @Inject constructor(
    private val repository: SoundCloudRepository,
    @StreamingOkHttpClient private val okHttpClient: OkHttpClient,
    private val userPreferencesRepository: UserPreferencesRepository
) {
    private companion object {
        val ALLOWED_REMOTE_HOST_SUFFIXES = setOf(
            "soundcloud.com",
            "soundcloud.cloud",
            "sndcdn.com"
        )

        /**
         * FIX(cloud-streaming-speed): upstream status codes that mean "the
         * cached stream URL went stale". SoundCloud progressive URLs are
         * served from CloudFront with a signed `Policy` query parameter that
         * expires within minutes-to-hours; once it passes, the CDN answers
         * 403/404/410. The proxy then re-resolves the stream URL once and
         * retries instead of failing the seek / playback outright.
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
     * stream-URL resolutions for the same track — ExoPlayer opens a media
     * source's DataSource several times in quick succession (header reads,
     * ranged re-reads, every seek), and without serialization each cache
     * miss triggered a SEPARATE full SoundCloud extraction. Concurrent
     * callers now wait for the first resolution and hit the freshly
     * populated cache. See YouTubeStreamProxy.resolutionMutexes.
     */
    private val resolutionMutexes = ConcurrentHashMap<String, Mutex>()

    // FIX(playback-start-latency): encoded track urls with an in-flight
    // background prefetch — see [prefetch].
    private val prefetchInFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private data class CachedUrl(val url: String, val timestamp: Long) {
        /**
         * FIX(cloud-streaming-speed): 30 minutes (was 5 HOURS).
         *
         * SoundCloud progressive stream URLs are CloudFront URLs signed with
         * a `Policy` query parameter whose lifetime is minutes-to-hours — a
         * 5-hour cache meant any seek after the first ~30 minutes requested
         * an upstream URL that had long expired (403), surfacing as a failed
         * or endlessly-rebuffering seek. 30 minutes keeps re-extraction rare
         * (one request per half hour of continuous listening) while never
         * serving a URL old enough to be reasonably considered expired. The
         * stale-URL self-heal below covers any residual gap.
         */
        fun isExpired(): Boolean = System.currentTimeMillis() - timestamp > 30 * 60 * 1000
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

    fun getProxyUrl(encodedUrl: String): String {
        if (actualPort == 0) {
            Timber.w("SoundCloudStreamProxy: getProxyUrl called but actualPort is 0")
            return ""
        }
        return "http://127.0.0.1:$actualPort/soundcloud/$encodedUrl"
    }

    fun resolveSoundCloudUri(uriString: String): String? {
        if (!uriString.startsWith("soundcloud://", ignoreCase = true)) return null
        // IMPROVE(playback-restore): resolve the persisted scheme form
        // `soundcloud://<encodedTrackUrl>` back to the CURRENT session's proxy
        // URL. The proxy rebinds to a new port on every app launch, so any
        // persisted HTTP proxy URL (favorites, last-playback snapshot,
        // listening-history entries) is stale on the next run — the
        // restart-safe scheme form is what gets stored, and this resolver is
        // what makes it playable again.
        //
        // The payload is the URL-encoded track URL (URLEncoder escapes '/' as
        // %2F and ':' as %3A), so the whole track URL survives as a single
        // opaque token after "soundcloud://". Work on the RAW string — never
        // on Uri.getHost(), which may return a decoded/normalized form and
        // would double-decode on the proxy side.
        val rawPayload = uriString.substringAfter("soundcloud://", "")
            .trim()
            .takeIf { it.isNotEmpty() } ?: return null
        if (actualPort == 0) {
            Timber.w("SoundCloudStreamProxy: resolveSoundCloudUri called but proxy not started yet")
            return null
        }
        // FIX(soundcloud-playback-restore): the payload may be persisted in
        // EITHER encoding. The intended form is the URL-ENCODED track URL
        // (single opaque token), but [CloudUriUtils.normalizeCloudUriForStorage]
        // — used by the last-playback snapshot, favorites and the downloads
        // index — extracts the payload via Uri.pathSegments, which DECODES
        // %2F/%3A. A decoded payload rebuilt into the proxy URL verbatim
        // produced
        //   http://127.0.0.1:<port>/soundcloud/https://soundcloud.com/a/t
        // — a MULTI-SEGMENT path that the single-segment Ktor route
        // "/soundcloud/{url}" never matches, so the proxy answered 404 and
        // the previously-playing SoundCloud song failed to replay after the
        // app was closed and reopened (YouTube was unaffected: its payload
        // is a slash-free 11-char video id).
        //
        // A decoded payload always contains the literal "://" of its inner
        // https URL, while a correctly-encoded token never does (":" and "/"
        // are %3A/%2F) — so that distinguishes the two forms unambiguously.
        // Re-encode decoded payloads with the same URLEncoder the live search
        // path uses, restoring the exact single-segment token the route (and
        // the proxy's URLDecoder) expect; encoded payloads pass through.
        val routePayload = if (rawPayload.contains("://")) {
            runCatching { URLEncoder.encode(rawPayload, "UTF-8") }.getOrDefault(rawPayload)
        } else {
            rawPayload
        }
        return getProxyUrl(routePayload)
    }

    /**
     * FIX(playback-start-latency): warms the resolved-stream-URL cache for the
     * given PROXY url (e.g. "http://127.0.0.1:port/soundcloud/<encoded track
     * url>") in the background, so ExoPlayer's first request for that song
     * doesn't pay the SoundCloud extraction cost (track resolve + transcoding
     * lookup) on the playback critical path.
     *
     * Called by MusicService whenever a new song becomes the current one to
     * prefetch the NEXT queued song. Mirrors YouTubeStreamProxy.prefetch:
     *  - accepts only URLs this proxy itself produced (loopback host,
     *    "/soundcloud/{...}" path);
     *  - skips fresh cache entries and dedupes in-flight prefetches;
     *  - runs on the proxy's own IO scope; failures are swallowed — the real
     *    request simply resolves on demand, exactly as before.
     */
    fun prefetch(proxyUrl: String) {
        if (actualPort == 0) return
        val encodedUrl = parseOwnProxyUrl(proxyUrl) ?: return

        // Checked synchronously; quality/cache reads happen inside the
        // coroutine so this never blocks the (main-thread) caller.
        if (!prefetchInFlight.add(encodedUrl)) return

        proxyScope.launch {
            try {
                // getOrFetchStreamUrl checks the cache (keyed on the DECODED
                // track url + quality) before extracting, so an already-warm
                // entry returns instantly.
                val trackUrl = URLDecoder.decode(encodedUrl, "UTF-8")
                val resolved = getOrFetchStreamUrl(trackUrl)
                if (resolved != null) {
                    Timber.d("SoundCloudStreamProxy: prefetched stream URL for $trackUrl")
                } else {
                    Timber.d("SoundCloudStreamProxy: prefetch found no stream URL for $trackUrl")
                }
            } catch (e: Exception) {
                Timber.d(e, "SoundCloudStreamProxy: background prefetch failed")
            } finally {
                prefetchInFlight.remove(encodedUrl)
            }
        }
    }

    /** Extracts the encoded track url from one of THIS proxy's own URLs, or null. */
    private fun parseOwnProxyUrl(proxyUrl: String): String? {
        return try {
            val uri = android.net.Uri.parse(proxyUrl)
            val isLoopback = uri.host == "127.0.0.1" || uri.host == "localhost"
            if (!isLoopback) return null
            val path = uri.path ?: return null
            val segments = path.split("/").filter { it.isNotEmpty() }
            if (segments.size != 2 || segments[0] != "soundcloud") return null
            val encoded = segments[1]
            if (encoded.isEmpty()) null else encoded
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
                Timber.d("SoundCloudStreamProxy started on port $actualPort")
            } catch (e: CancellationException) {
                Timber.d("SoundCloudStreamProxy start cancelled")
            } catch (e: Exception) {
                Timber.e(e, "Failed to start SoundCloudStreamProxy")
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
        Timber.d("SoundCloudStreamProxy stopped")
    }

    private fun createServer(port: Int): ApplicationEngine {
        return embeddedServer(CIO, host = "127.0.0.1", port = port) {
            routing {
                get("/soundcloud/{url}") {
                    val encodedUrl = call.parameters["url"]
                    if (encodedUrl == null) {
                        call.respond(HttpStatusCode.BadRequest, "Missing SoundCloud URL")
                        return@get
                    }

                    try {
                        val soundCloudUrl = URLDecoder.decode(encodedUrl, "UTF-8")

                        val rangeValidation = CloudStreamSecurity.validateRangeHeader(call.request.headers["Range"])
                        if (!rangeValidation.isValid) {
                            call.respond(HttpStatusCode(416, "Range Not Satisfiable"), "Invalid range header")
                            return@get
                        }

                        val streamUrl = getOrFetchStreamUrl(soundCloudUrl)
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

                        // Proxy the audio stream.
                        fun buildUpstreamRequest(url: String): Request {
                            val builder = Request.Builder().url(url)
                            rangeValidation.normalizedHeader?.let {
                                builder.header("Range", it)
                            }
                            return builder.build()
                        }

                        // FIX: Stream chunk-by-chunk through CloudStreamForwarder
                        // instead of buffering the whole upstream body in memory.
                        // Same root-cause fix as YouTubeStreamProxy — see the
                        // forwarder's KDoc for the full analysis of the original
                        // "playback stuck at 00:00" production bug.
                        //
                        // FIX(cloud-streaming-speed): deferred stale-status codes —
                        // when CloudFront rejects the cached signed URL (403/404/410,
                        // the `Policy` signature expired), the forwarder returns
                        // instead of answering ExoPlayer, and the self-heal below
                        // re-resolves the stream URL once and forwards again.
                        val outcome = CloudStreamForwarder.forwardStream(
                            call = call,
                            client = okHttpClient,
                            upstreamRequest = buildUpstreamRequest(streamUrl),
                            deferUpstreamStatusCodes = STALE_UPSTREAM_STATUS_CODES
                        )

                        if (outcome is ForwardOutcome.UpstreamRejected) {
                            Timber.d(
                                "SoundCloudStreamProxy: cached stream URL rejected " +
                                    "upstream (code=%d) — re-resolving once",
                                outcome.statusCode
                            )
                            val freshUrl = runCatching {
                                getOrFetchStreamUrl(soundCloudUrl, forceRefresh = true)
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
                            Timber.e(e, "Error streaming SoundCloud track")
                        }
                    }
                }
            }
        }
    }

    /**
     * Resolves (or reuses) the progressive stream URL for [soundCloudUrl].
     *
     * FIX(cloud-streaming-speed):
     *  - Mutex-per-key dedup — see [resolutionMutexes].
     *  - [forceRefresh] bypasses and evicts the cache entry first; used by
     *    the stale-URL self-heal path after CloudFront rejected a previously
     *    cached URL (403/404/410).
     */
    private suspend fun getOrFetchStreamUrl(soundCloudUrl: String, forceRefresh: Boolean = false): String? {
        val quality = userPreferencesRepository.streamingQualityFlow.first()
        val cacheKey = "$soundCloudUrl-${quality.name}"

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
            val result = repository.getAudioStreamUrl(soundCloudUrl, quality)
            result.getOrNull()?.also { url ->
                urlCache[cacheKey] = CachedUrl(url, System.currentTimeMillis())
            }
        }
    }
}
