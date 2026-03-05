package com.theveloper.pixelplay.data.youtube

import android.net.Uri
import com.theveloper.pixelplay.data.stream.CloudStreamSecurity
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.engine.*
import io.ktor.server.cio.*
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.header
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully
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

@Singleton
class YouTubeStreamProxy @Inject constructor(
    private val repository: YouTubeRepository,
    private val okHttpClient: OkHttpClient
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
        // YouTube URLs typically expire in ~6 hours, let's be safe and refetch after 5
        fun isExpired(): Boolean = System.currentTimeMillis() - timestamp > 5 * 60 * 60 * 1000
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
                        val requestBuilder = Request.Builder().url(streamUrl)
                        call.request.headers["User-Agent"]?.let { userAgent ->
                            requestBuilder.header("User-Agent", userAgent)
                        }
                        rangeValidation.normalizedHeader?.let {
                            requestBuilder.header("Range", it)
                        }

                        val response = withContext(Dispatchers.IO) {
                            okHttpClient.newCall(requestBuilder.build()).execute()
                        }

                        response.use { upstream ->
                            if (upstream.code != 200 && upstream.code != 206) {
                                call.respond(
                                    CloudStreamSecurity.mapUpstreamStatusToProxyStatus(upstream.code),
                                    "Upstream stream request failed"
                                )
                                return@get
                            }

                            val body = upstream.body ?: run {
                                call.respond(HttpStatusCode.InternalServerError, "Empty body")
                                return@get
                            }

                            val contentTypeHeader = upstream.header("Content-Type")
                            if (!CloudStreamSecurity.isSupportedAudioContentType(contentTypeHeader)) {
                                call.respond(HttpStatusCode.BadGateway, "Unsupported stream content type")
                                return@get
                            }

                            val contentLength = upstream.header("Content-Length")
                            if (!CloudStreamSecurity.isAcceptableContentLength(contentLength)) {
                                call.respond(HttpStatusCode(413, "Payload Too Large"), "Stream content too large")
                                return@get
                            }

                            val contentRange = upstream.header("Content-Range")
                            val acceptRanges = upstream.header("Accept-Ranges")
                            val responseContentType = contentTypeHeader
                                ?.substringBefore(';')
                                ?.trim()
                                ?.let { raw -> runCatching { ContentType.parse(raw) }.getOrNull() }
                                ?: ContentType.Audio.Any

                            call.respond(object : io.ktor.http.content.OutgoingContent.WriteChannelContent() {
                                override val contentLength: Long? = contentLength?.toLongOrNull()
                                override val contentType: ContentType = responseContentType
                                override val status: HttpStatusCode = if (upstream.code == 206) HttpStatusCode.PartialContent else HttpStatusCode.OK
                                override val headers: io.ktor.http.Headers = io.ktor.http.Headers.build {
                                    acceptRanges?.let { append("Accept-Ranges", it) } ?: append("Accept-Ranges", "bytes")
                                    contentRange?.let { append("Content-Range", it) }
                                }

                                override suspend fun writeTo(channel: io.ktor.utils.io.ByteWriteChannel) {
                                    withContext(Dispatchers.IO) {
                                        body.byteStream().use { input ->
                                            val buffer = ByteArray(64 * 1024)
                                            var bytesRead: Int
                                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                                channel.writeFully(buffer, 0, bytesRead)
                                            }
                                        }
                                    }
                                }
                            })
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

    private suspend fun getOrFetchStreamUrl(youtubeId: String): String? {
        // Check cache first
        urlCache[youtubeId]?.let { cached ->
            if (!cached.isExpired()) return cached.url
        }

        // Fetch fresh URL
        val result = repository.getAudioStreamUrl(youtubeId)
        return result.getOrNull()?.also { url ->
            urlCache[youtubeId] = CachedUrl(url, System.currentTimeMillis())
        }
    }
}
