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

                        // Proxy the audio stream
                        val requestBuilder = Request.Builder()
                            .url(streamUrl)
                            .header("Accept-Encoding", "identity") // MUST disable gzip to preserve Range requests for Media3/ExoPlayer
                            // FIX: Add a standard browser User-Agent to bypass YouTube's 403 Forbidden bot checks
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                            
                        rangeValidation.normalizedHeader?.let {
                            requestBuilder.header("Range", it)
                        }

                        // CRITICAL FIX: Read the ENTIRE upstream response on Dispatchers.IO
                        // BEFORE handing anything to Ktor's response pipeline.
                        //
                        // The previous `respondBytesWriter` + `withContext(Dispatchers.IO)`
                        // pattern caused a deadlock on Ktor's CIO engine: response headers
                        // were never flushed until the first `writeFully` call returned, but
                        // the first `writeFully` could not return until bytes were read from
                        // the upstream OkHttp body — which itself could be delayed by YouTube's
                        // adaptive throttling. ExoPlayer's `DefaultHttpDataSource.open`
                        // therefore sat in `HttpURLConnectionImpl.getResponseCode()` waiting
                        // for the HTTP status line, eventually throwing
                        // `SocketTimeoutException: timeout` after the 8 s default read timeout.
                        // User-visible symptom: player loads but stays frozen at 00:00 with
                        // no error shown.
                        //
                        // Buffering the entire body first lets us send the response as a
                        // single atomic `respondBytes` call, which forces Ktor to flush
                        // status + headers + body in one shot. Memory cost is bounded by
                        // `CloudStreamSecurity.MAX_STREAM_CONTENT_LENGTH_BYTES` (2 GiB)
                        // checked above, and YouTube audio streams are typically a few MB.
                        val (upstreamCode, upstreamHeaders, bodyBytes) = withContext(Dispatchers.IO) {
                            okHttpClient.newCall(requestBuilder.build()).execute().use { upstream ->
                                val code = upstream.code
                                val headers = upstream.headers
                                val bytes = upstream.body?.bytes() ?: ByteArray(0)
                                Triple(code, headers, bytes)
                            }
                        }

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

                        // Send the buffered body as a single atomic response. Ktor will
                        // compute Content-Length from the byte array size and flush status
                        // + headers + body together, avoiding the CIO engine deadlock.
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
