package com.theveloper.pixeltune.data.stream

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.header
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.OutputStream

/**
 * Streams an upstream HTTP audio response straight to a Ktor response,
 * chunk-by-chunk, without ever buffering the entire body in memory.
 *
 * ──────────────────────────────────────────────────────────────────────
 * ROOT-CAUSE NOTE (why this exists)
 * ──────────────────────────────────────────────────────────────────────
 * The legacy cloud-streaming proxies (YouTubeStreamProxy,
 * NeteaseStreamProxy, SoundCloudStreamProxy, GDriveStreamProxy) all read
 * the ENTIRE upstream response into a `ByteArray` via OkHttp's
 * `ResponseBody.bytes()` before forwarding anything to ExoPlayer.
 *
 * That pattern is the source of the "YouTube playback stuck at 00:00"
 * production bug, for two compounding reasons:
 *
 *   1. The default app-wide OkHttpClient has `readTimeout = 8s`. YouTube
 *      adaptively throttles audio streams, so a 5 MB body can take far
 *      longer than 8s to fully download. OkHttp's `bytes()` then throws
 *      `SocketTimeoutException` mid-read.
 *
 *   2. Because `bytes()` is called BEFORE `respondBytes`, the proxy never
 *      sends an HTTP status line to ExoPlayer until the whole body is
 *      buffered. ExoPlayer's `DefaultHttpDataSource.open()` blocks at
 *      `HttpURLConnectionImpl.getResponseCode()` waiting for the status
 *      line, hits its own 8s connect timeout, throws its own
 *      `SocketTimeoutException`, and the player just sits at 00:00 — no
 *      audio, no error surfaced to the UI.
 *
 * The previous "fix" comment in YouTubeStreamProxy claimed that
 * `respondBytesWriter + withContext(Dispatchers.IO)` had a CIO-engine
 * deadlock, and that buffering the body via `respondBytes` avoided it.
 * That conclusion was wrong: buffering shifts the timeout from ExoPlayer's
 * side to OkHttp's side, but it doesn't fix the underlying streaming
 * failure for throttled upstreams. The deadlock was actually caused by
 * calling `respondBytesWriter` from inside `withContext(Dispatchers.IO)`,
 * not by streaming itself.
 *
 * ──────────────────────────────────────────────────────────────────────
 * HOW THIS FIX WORKS
 * ──────────────────────────────────────────────────────────────────────
 *   1. Open the upstream OkHttp connection on Dispatchers.IO (the only
 *      network call). The caller MUST inject a streaming OkHttpClient
 *      with `readTimeout = 0` (see [@StreamingOkHttpClient] in di/) so
 *      throttled upstreams don't abort the call mid-stream.
 *
 *   2. Validate the upstream status code + Content-Type + Content-Length
 *      (same checks as before, same `CloudStreamSecurity` helpers).
 *
 *   3. Set the Ktor response status + headers IMMEDIATELY. Because
 *      `respondOutputStream` honors pre-set status/headers and flushes
 *      them through the CIO pipeline before invoking the body lambda,
 *      ExoPlayer now receives a status line within milliseconds of the
 *      upstream's response — no more 8s `getResponseCode()` timeout.
 *
 *   4. Stream the upstream body in chunks via
 *      `ApplicationCall.respondOutputStream`. We do NOT wrap the body
 *      lambda in `withContext(Dispatchers.IO)` — that is exactly what
 *      caused the legacy CIO deadlock. Ktor's CIO engine uses
 *      Dispatchers.IO under the hood with sufficient parallelism, so
 *      doing the blocking `InputStream.read` + `OutputStream.write`
 *      directly on the request handler's coroutine is safe for the
 *      single-stream-per-user playback scenario.
 *
 *   5. The OutputStream's `write` is synchronous (Ktor internally wraps
 *      the ByteWriteChannel with `runBlocking`), so it back-pressures
 *      naturally when ExoPlayer reads slowly — i.e. we never queue more
 *      than a few chunks in flight at any time.
 */
object CloudStreamForwarder {

    /** 8 KB — matches OkHttp's internal buffer size; small enough to keep
     *  memory pressure low, large enough to amortize per-call overhead. */
    private const val BUFFER_SIZE = 8 * 1024

    /**
     * Forwards an upstream HTTP response to a downstream Ktor response,
     * streaming the body in chunks.
     *
     * Caller is responsible for:
     *   - Building the upstream Request (URL + Range header + per-source
     *     headers such as a browser User-Agent).
     *   - Validating the upstream URL against the per-source allow-list
     *     via `CloudStreamSecurity.isSafeRemoteStreamUrl(...)` BEFORE
     *     calling this function.
     *   - Injecting a streaming OkHttpClient (see [@StreamingOkHttpClient])
     *     with no per-read timeout.
     *
     * @param call The Ktor ApplicationCall to write the response to.
     * @param client The streaming OkHttpClient.
     * @param upstreamRequest A fully-built OkHttp Request for the upstream
     *        audio URL (including Range header if the client requested one).
     */
    suspend fun forwardStream(
        call: ApplicationCall,
        client: OkHttpClient,
        upstreamRequest: Request
    ) {
        // 1. Open the upstream connection on Dispatchers.IO. This is the
        //    only network call; the returned Response body is NOT yet read.
        //    We must close it ourselves in a finally block.
        val upstream = withContext(Dispatchers.IO) {
            client.newCall(upstreamRequest).execute()
        }

        try {
            forwardOpenedStream(call, upstream)
        } catch (e: Exception) {
            // Only log if this is NOT a client-disconnect (those are normal —
            // ExoPlayer tears down the connection on every seek / pause).
            val msg = e.toString()
            if (!msg.contains("ChannelWriteException") &&
                !msg.contains("ClosedChannelException") &&
                !msg.contains("Broken pipe") &&
                !msg.contains("JobCancellationException") &&
                !msg.contains("ConnectionReset")
            ) {
                Timber.e(e, "CloudStreamForwarder: error forwarding upstream stream")
            }
        } finally {
            // Closing the OkHttp Response also closes the body InputStream,
            // which releases the underlying socket back to the connection pool
            // (or closes it if the server sent Connection: close).
            withContext(Dispatchers.IO) {
                runCatching { upstream.close() }
            }
        }
    }

    /**
     * Same as [forwardStream] but for a Response that the caller has already
     * opened (e.g. for tests, or for proxies that need to do custom header
     * negotiation before forwarding). The caller is responsible for closing
     * the Response.
     */
    suspend fun forwardOpenedStream(
        call: ApplicationCall,
        upstream: okhttp3.Response
    ) {
        val upstreamCode = upstream.code
        val upstreamHeaders = upstream.headers

        // 2. Validate upstream status code.
        if (upstreamCode != 200 && upstreamCode != 206) {
            call.respond(
                CloudStreamSecurity.mapUpstreamStatusToProxyStatus(upstreamCode),
                "Upstream stream request failed (code=$upstreamCode)"
            )
            return
        }

        val contentTypeHeader = upstreamHeaders["Content-Type"]
        if (!CloudStreamSecurity.isSupportedAudioContentType(contentTypeHeader)) {
            call.respond(
                HttpStatusCode.BadGateway,
                "Unsupported stream content type: $contentTypeHeader"
            )
            return
        }

        val contentLength = upstreamHeaders["Content-Length"]
        if (!CloudStreamSecurity.isAcceptableContentLength(contentLength)) {
            call.respond(
                HttpStatusCode(413, "Payload Too Large"),
                "Stream content too large"
            )
            return
        }

        val contentRange = upstreamHeaders["Content-Range"]
        val acceptRanges = upstreamHeaders["Accept-Ranges"]
        val responseContentType = contentTypeHeader
            ?.substringBefore(';')
            ?.trim()
            ?.let { raw -> runCatching { ContentType.parse(raw) }.getOrNull() }
            ?: ContentType.Audio.Any

        // 3. Set response status + headers IMMEDIATELY.
        //    Ktor's respondOutputStream honors these pre-set values and
        //    flushes them through the CIO pipeline before invoking the
        //    body lambda — ExoPlayer therefore receives the HTTP status
        //    line within milliseconds of the upstream's response.
        if (upstreamCode == 206) {
            call.response.status(HttpStatusCode.PartialContent)
        } else {
            call.response.status(HttpStatusCode.OK)
        }
        call.response.header("Accept-Ranges", acceptRanges ?: "bytes")
        contentRange?.let { call.response.header("Content-Range", it) }

        // 4. Stream the upstream body chunk-by-chunk through
        //    respondOutputStream. We deliberately do NOT wrap the lambda in
        //    withContext(Dispatchers.IO) — that is what caused the legacy
        //    CIO engine deadlock mentioned in the original YouTubeStreamProxy
        //    code comments. Ktor's CIO engine already dispatches route
        //    handlers on Dispatchers.IO, so doing the blocking InputStream
        //    reads + OutputStream writes on the request handler's coroutine
        //    is safe (and is in fact what TelegramStreamProxy already does
        //    with respondBytesWriter + writeFully for local files).
        call.respondOutputStream(contentType = responseContentType) {
            streamBody(upstream, this)
        }
    }

    /**
     * Reads from the upstream OkHttp Response body and writes to the Ktor
     * OutputStream in fixed-size chunks. Visible for testing — unit tests
     * verify that an arbitrary upstream byte stream is forwarded byte-for-byte
     * without ever holding more than [BUFFER_SIZE] bytes in memory at once.
     */
    internal fun streamBody(upstream: okhttp3.Response, output: OutputStream) {
        val body = upstream.body
        if (body == null) {
            // No body to stream — send an empty response. ExoPlayer will
            // treat this as a zero-length stream and immediately complete.
            output.flush()
            return
        }

        val input = body.byteStream()
        val buffer = ByteArray(BUFFER_SIZE)

        try {
            while (true) {
                // Blocking read on the request handler's coroutine.
                // OkHttp's InputStream honors the streaming client's
                // readTimeout (0 = infinite) — so throttled YouTube
                // streams no longer abort mid-read.
                val read = input.read(buffer)
                if (read <= 0) break

                // OutputStream.write is synchronous — Ktor wraps the
                // ByteWriteChannel with runBlocking internally, so this
                // back-pressures naturally when ExoPlayer reads slowly.
                output.write(buffer, 0, read)
                output.flush()
            }
        } finally {
            runCatching { output.flush() }
        }
    }
}
