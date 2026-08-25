package com.theveloper.pixeltune.data.gdrive

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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.net.ServerSocket
import com.theveloper.pixeltune.di.StreamingOkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local HTTP proxy server for streaming Google Drive audio.
 *
 * Resolves `gdrive://{fileId}` URIs by proxying requests to the Drive REST API
 * with the required Authorization header. Follows the same architectural pattern
 * as [com.theveloper.pixeltune.data.netease.NeteaseStreamProxy] and
 * [com.theveloper.pixeltune.data.telegram.TelegramStreamProxy] using Ktor CIO.
 *
 * Streams the upstream body chunk-by-chunk through [CloudStreamForwarder]
 * instead of buffering the entire body in memory via OkHttp's `bytes()`.
 * Same fix as [com.theveloper.pixeltune.data.youtube.YouTubeStreamProxy] —
 * see the forwarder's KDoc for the root-cause analysis of the original
 * "playback stuck at 00:00" production bug.
 */
@Singleton
class GDriveStreamProxy @Inject constructor(
    private val repository: GDriveRepository,
    @StreamingOkHttpClient private val okHttpClient: OkHttpClient
) {
    private companion object {
        val ALLOWED_REMOTE_HOST_SUFFIXES = setOf(
            "googleapis.com",
            "googleusercontent.com"
        )
    }

    private var server: ApplicationEngine? = null
    private var actualPort: Int = 0
    private val proxyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startJob: Job? = null

    fun isReady(): Boolean = actualPort > 0

    fun getProxyUrl(fileId: String): String {
        if (actualPort == 0) {
            Timber.w("GDriveStreamProxy: getProxyUrl called but actualPort is 0")
            return ""
        }
        if (!CloudStreamSecurity.validateGDriveFileId(fileId)) {
            Timber.w("GDriveStreamProxy: getProxyUrl rejected invalid fileId")
            return ""
        }
        return "http://127.0.0.1:$actualPort/gdrive/$fileId"
    }

    /**
     * Parse a `gdrive://` URI and return the proxy URL.
     * Returns null if the URI is not a valid GDrive URI.
     */
    fun resolveGDriveUri(uriString: String): String? {
        val uri = Uri.parse(uriString)
        if (uri.scheme != "gdrive") return null
        val fileId = uri.host ?: return null
        if (!CloudStreamSecurity.validateGDriveFileId(fileId)) return null
        return getProxyUrl(fileId)
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
                Timber.d("GDriveStreamProxy started on port $actualPort")
            } catch (e: CancellationException) {
                Timber.d("GDriveStreamProxy start cancelled")
            } catch (e: Exception) {
                Timber.e(e, "Failed to start GDriveStreamProxy")
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
        Timber.d("GDriveStreamProxy stopped")
    }

    private fun createServer(port: Int): ApplicationEngine {
        return embeddedServer(CIO, host = "127.0.0.1", port = port) {
            routing {
                get("/gdrive/{fileId}") {
                    val fileId = call.parameters["fileId"]
                    if (fileId.isNullOrBlank() || !CloudStreamSecurity.validateGDriveFileId(fileId)) {
                        call.respond(HttpStatusCode.BadRequest, "Invalid File ID")
                        return@get
                    }

                    var openedResponse: okhttp3.Response? = null
                    try {
                        val rangeValidation = CloudStreamSecurity.validateRangeHeader(call.request.headers["Range"])
                        if (!rangeValidation.isValid) {
                            call.respond(HttpStatusCode(416, "Range Not Satisfiable"), "Invalid range header")
                            return@get
                        }

                        val streamUrl = repository.getStreamUrl(fileId)
                        if (!CloudStreamSecurity.isSafeRemoteStreamUrl(
                                url = streamUrl,
                                allowedHostSuffixes = ALLOWED_REMOTE_HOST_SUFFIXES
                            )
                        ) {
                            call.respond(HttpStatusCode.BadGateway, "Rejected upstream stream URL")
                            return@get
                        }

                        val authHeader = repository.getAuthHeader()

                        if (authHeader.isBlank() || authHeader == "Bearer ") {
                            call.respond(HttpStatusCode.Unauthorized, "No auth token")
                            return@get
                        }

                        // Build the request to Google Drive
                        val requestBuilder = Request.Builder()
                            .url(streamUrl)
                            .header("Authorization", authHeader)

                        rangeValidation.normalizedHeader?.let {
                            requestBuilder.header("Range", it)
                        }

                        var response = withContext(Dispatchers.IO) {
                            okHttpClient.newCall(requestBuilder.build()).execute()
                        }

                        // If 401, try refreshing the token and retry once
                        if (response.code == 401) {
                            response.close()
                            Timber.d("GDriveStreamProxy: 401 received, refreshing token...")
                            val refreshResult = repository.refreshAccessToken()
                            if (refreshResult.isSuccess) {
                                val newAuthHeader = repository.getAuthHeader()
                                if (newAuthHeader.isBlank() || newAuthHeader == "Bearer ") {
                                    call.respond(HttpStatusCode.Unauthorized, "Token refresh failed")
                                    return@get
                                }
                                val retryRequest = Request.Builder()
                                    .url(streamUrl)
                                    .header("Authorization", newAuthHeader)
                                rangeValidation.normalizedHeader?.let {
                                    retryRequest.header("Range", it)
                                }
                                response = withContext(Dispatchers.IO) {
                                    okHttpClient.newCall(retryRequest.build()).execute()
                                }
                            }
                        }

                        // Hand the opened Response to CloudStreamForwarder, which
                        // will validate the upstream status / headers, set the Ktor
                        // response status + headers IMMEDIATELY, and stream the body
                        // chunk-by-chunk through respondOutputStream.
                        //
                        // The forwarder takes responsibility for closing the Response
                        // (it uses `forwardOpenedStream` here, NOT the higher-level
                        // `forwardStream`, because we've already opened the upstream
                        // connection above to handle the 401-refresh flow).
                        //
                        // NOTE: this means we don't close the response ourselves —
                        // the forwarder is the single owner of the response lifecycle
                        // once it's handed off.
                        openedResponse = response
                        CloudStreamForwarder.forwardOpenedStream(
                            call = call,
                            upstream = response
                        )
                    } catch (e: Exception) {
                        val msg = e.toString()
                        if (msg.contains("ChannelWriteException") ||
                            msg.contains("ClosedChannelException") ||
                            msg.contains("Broken pipe") ||
                            msg.contains("JobCancellationException")
                        ) {
                            // Client disconnected, normal behavior
                        } else {
                            Timber.e(e, "Error streaming GDrive file $fileId")
                        }
                    } finally {
                        // Safety net: ensure the upstream Response is always closed
                        // even if the forwarder threw or the route handler was
                        // cancelled before forwardOpenedStream completed. Closing
                        // an already-closed Response is a no-op.
                        openedResponse?.let { resp ->
                            withContext(Dispatchers.IO) {
                                runCatching { resp.close() }
                            }
                        }
                    }
                }
            }
        }
    }
}
