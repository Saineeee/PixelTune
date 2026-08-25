package com.theveloper.pixeltune.data.soundcloud

import android.net.Uri
import com.theveloper.pixeltune.data.stream.CloudStreamForwarder
import com.theveloper.pixeltune.data.stream.CloudStreamSecurity
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
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.net.ServerSocket
import java.net.URLDecoder
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
    }

    private var server: ApplicationEngine? = null
    private var actualPort: Int = 0
    private val proxyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startJob: Job? = null

    // Cache of resolved streaming URLs
    private val urlCache = ConcurrentHashMap<String, CachedUrl>()

    private data class CachedUrl(val url: String, val timestamp: Long) {
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

    fun getProxyUrl(encodedUrl: String): String {
        if (actualPort == 0) {
            Timber.w("SoundCloudStreamProxy: getProxyUrl called but actualPort is 0")
            return ""
        }
        return "http://127.0.0.1:$actualPort/soundcloud/$encodedUrl"
    }

    fun resolveSoundCloudUri(uriString: String): String? {
        val uri = Uri.parse(uriString)
        if (uri.scheme != "soundcloud") return null
        // The host would be the encoded soundcloud url, but we might just need to verify
        // that it looks like soundcloud. For our proxy, the `contentUriString` will actually
        // be `http://127.0.0.1...`, so this resolver is mostly for custom scheme handling if any.
        return null
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
                        val requestBuilder = Request.Builder().url(streamUrl)
                        rangeValidation.normalizedHeader?.let {
                            requestBuilder.header("Range", it)
                        }

                        // FIX: Stream chunk-by-chunk through CloudStreamForwarder
                        // instead of buffering the whole upstream body in memory.
                        // Same root-cause fix as YouTubeStreamProxy — see the
                        // forwarder's KDoc for the full analysis of the original
                        // "playback stuck at 00:00" production bug.
                        CloudStreamForwarder.forwardStream(
                            call = call,
                            client = okHttpClient,
                            upstreamRequest = requestBuilder.build()
                        )
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

    private suspend fun getOrFetchStreamUrl(soundCloudUrl: String): String? {
        val quality = userPreferencesRepository.streamingQualityFlow.first()
        val cacheKey = "$soundCloudUrl-${quality.name}"

        // Check cache first
        urlCache[cacheKey]?.let { cached ->
            if (!cached.isExpired()) return cached.url
        }

        // Fetch fresh URL
        val result = repository.getAudioStreamUrl(soundCloudUrl, quality)
        return result.getOrNull()?.also { url ->
            urlCache[cacheKey] = CachedUrl(url, System.currentTimeMillis())
        }
    }
}
