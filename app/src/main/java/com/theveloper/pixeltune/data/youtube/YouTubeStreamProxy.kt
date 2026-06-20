package com.theveloper.pixeltune.data.youtube

import android.net.Uri
import com.theveloper.pixeltune.data.stream.CloudStreamSecurity
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.engine.*
import io.ktor.server.cio.*
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.header
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
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class YouTubeStreamProxy @Inject constructor(
    private val repository: YouTubeRepository,
    private val okHttpClient: OkHttpClient,
    private val userPreferencesRepository: com.theveloper.pixeltune.data.preferences.UserPreferencesRepository
) {
    private companion object {
        val ALLOWED_REMOTE_HOST_SUFFIXES = setOf(
            "googlevideo.com",
            "youtube.com",
            "ytimg.com"
        )
    }

    private var server: ApplicationEngine? = null
    private var actualPort: Int = 0
    private val proxyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startJob: Job? = null

    // Cache of resolved streaming URLs
    private val urlCache = ConcurrentHashMap<String, CachedUrl>()

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

                        Timber.d("YouTubeStreamProxy: fetching upstream for $youtubeId, url=${streamUrl.take(100)}")

                        // Proxy the audio stream
                        val requestBuilder = Request.Builder()
                            .url(streamUrl)
                            .header("Accept-Encoding", "identity") // MUST disable gzip to preserve Range requests for Media3/ExoPlayer
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                            
                        rangeValidation.normalizedHeader?.let {
                            requestBuilder.header("Range", it)
                        }

                        // CRITICAL FIX: Read the ENTIRE upstream response on Dispatchers.IO
                        // BEFORE handing anything to Ktor's response pipeline.
                        //
                        // The previous implementation used `call.respondBytesWriter { withContext(Dispatchers.IO) { ... } }`
                        // which caused a deadlock on Ktor's CIO engine: the CIO writer thread
                        // waits for the first `writeFully` call to produce data, but `writeFully`
                        // runs on Dispatchers.IO while the Ktor response headers haven't been
                        // flushed yet (CIO buffers headers until the first body chunk is ready).
                        // This left ExoPlayer stuck at `getResponseCode()` for 30 seconds until
                        // its SocketTimeoutException fired, causing the "stuck at 00:00" bug.
                        //
                        // The fix: buffer the entire upstream body into a ByteArray on the IO
                        // dispatcher, then send it with `call.respondBytes()`. Ktor's
                        // `respondBytes` sets all headers and writes the body in one atomic
                        // operation, eliminating the deadlock. Audio files are typically 3-10MB
                        // (itag 251 Opus ~3MB, itag 140 AAC ~5MB), which is fine to buffer.
                        val (upstreamCode, upstreamHeaders, bodyBytes) = withContext(Dispatchers.IO) {
                            okHttpClient.newCall(requestBuilder.build()).execute().use { upstream ->
                                val code = upstream.code
                                val headers = upstream.headers
                                val bytes = upstream.body?.bytes() ?: ByteArray(0)
                                Triple(code, headers, bytes)
                            }
                        }

                        Timber.d("YouTubeStreamProxy: upstream response for $youtubeId: code=$upstreamCode, bodySize=${bodyBytes.size}")

                        if (upstreamCode != 200 && upstreamCode != 206) {
                            call.respond(
                                CloudStreamSecurity.mapUpstreamStatusToProxyStatus(upstreamCode),
                                "Upstream stream request failed (code=$upstreamCode)"
                            )
                            return@get
                        }

                        val contentTypeHeader = upstreamHeaders["Content-Type"]
                        if (!CloudStreamSecurity.isSupportedAudioContentType(contentTypeHeader)) {
                            call.respond(HttpStatusCode.BadGateway, "Unsupported stream content type: $contentTypeHeader")
                            return@get
                        }

                        val contentLength = upstreamHeaders["Content-Length"]
                        if (!CloudStreamSecurity.isAcceptableContentLength(contentLength)) {
                            call.respond(HttpStatusCode(413, "Payload Too Large"), "Stream content too large")
                            return@get
                        }

                        val contentRange = upstreamHeaders["Content-Range"]
                        val acceptRanges = upstreamHeaders["Accept-Ranges"]
                        val responseContentType = contentTypeHeader
                            ?.substringBefore(';')
                            ?.trim()
                            ?.let { raw -> runCatching { ContentType.parse(raw) }.getOrNull() }
                            ?: ContentType.Audio.Any

                        if (upstreamCode == 206) {
                            call.response.status(HttpStatusCode.PartialContent)
                        } else {
                            call.response.status(HttpStatusCode.OK)
                        }
                        call.response.header("Accept-Ranges", acceptRanges ?: "bytes")
                        contentRange?.let { call.response.header("Content-Range", it) }

                        Timber.d("YouTubeStreamProxy: sending ${bodyBytes.size} bytes to ExoPlayer for $youtubeId")

                        // Send the buffered body as a single atomic response.
                        // Ktor's respondBytes sets Content-Length automatically from the
                        // ByteArray size and writes everything in one call — no streaming
                        // deadlock possible.
                        call.respondBytes(
                            bytes = bodyBytes,
                            contentType = responseContentType
                        )
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

    private suspend fun getOrFetchStreamUrl(youtubeId: String): String? {
        val quality = userPreferencesRepository.streamingQualityFlow.first()
        val cacheKey = "${youtubeId}_${quality.name}"

        // Check cache first
        urlCache[cacheKey]?.let { cached ->
            if (!cached.isExpired()) return cached.url
        }

        // Fetch fresh URL
        val result = repository.getAudioStreamUrl(youtubeId, quality)
        return result.getOrNull()?.also { url ->
            urlCache[cacheKey] = CachedUrl(url, System.currentTimeMillis())
        }
    }
}
