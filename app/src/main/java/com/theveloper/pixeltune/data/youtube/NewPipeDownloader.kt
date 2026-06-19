package com.theveloper.pixeltune.data.youtube

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as ExtractorRequest
import org.schabi.newpipe.extractor.downloader.Response as ExtractorResponse
import javax.inject.Inject

class NewPipeDownloader @Inject constructor(
    private val client: OkHttpClient
) : Downloader() {

    /**
     * Browser-like User-Agent used for every NewPipe request.
     *
     * YouTube and SoundCloud both inspect the User-Agent header:
     *   - YouTube serves a "page needs to be reloaded" bot-check page when the UA is
     *     not a recognized browser, which surfaces as
     *     `ContentNotAvailableException` inside `YoutubeStreamExtractor.fetchPage()`
     *     and breaks playback (player stays at 00:00).
     *   - SoundCloud's homepage HTML returned to non-browser UAs may omit the
     *     `<script>` tags that `SoundcloudParsingHelper.clientId()` scans, which
     *     breaks the client_id extraction and returns no search results at all.
     *
     * The app-wide OkHttpClient interceptor in AppModule only sets the default
     * "PixelTune/1.0" UA when no UA is present, so this explicit header is preserved
     * end-to-end.
     */
    private val browserUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    override fun execute(request: ExtractorRequest): ExtractorResponse {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val requestBuilder = Request.Builder().url(url)

        // Force a browser User-Agent on every NewPipe request. NewPipe itself does
        // not set one, so without this the OkHttpClient's default app UA would be
        // used and YouTube/SoundCloud would block the requests.
        requestBuilder.header("User-Agent", browserUserAgent)

        // Also default Accept-Language to a browser-like value (some NewPipe
        // requests do not include one, and a missing Accept-Language can cause
        // YouTube to return region/consent redirects).
        requestBuilder.header("Accept-Language", "en-GB, en;q=0.9")

        val contentTypeHeader = headers.entries.find { it.key.equals("Content-Type", ignoreCase = true) }?.value?.firstOrNull()
        val mediaType = contentTypeHeader?.toMediaTypeOrNull()

        val methodNeedsBody = httpMethod == "POST" || httpMethod == "PUT" || httpMethod == "PATCH"
        val methodCannotHaveBody = httpMethod == "GET" || httpMethod == "HEAD" || httpMethod == "OPTIONS"

        if (dataToSend != null && !methodCannotHaveBody) {
            requestBuilder.method(httpMethod, dataToSend.toRequestBody(mediaType))
        } else if (methodNeedsBody) {
            requestBuilder.method(httpMethod, ByteArray(0).toRequestBody(mediaType))
        } else {
            requestBuilder.method(httpMethod, null)
        }

        headers.forEach { (key, values) ->
            if (key.equals("Content-Type", ignoreCase = true)) return@forEach

            // CRITICAL SOUNDCLOUD FIX: We MUST filter out Accept-Encoding.
            // When we let OkHttp handle this internally, it will perform transparent
            // GZIP decompression for us. This provides NewPipe with the uncompressed HTML
            // text it needs to extract the SoundCloud client_id.
            if (key.equals("Accept-Encoding", ignoreCase = true)) return@forEach

            // Never let NewPipe override our browser User-Agent — see comment above.
            if (key.equals("User-Agent", ignoreCase = true)) return@forEach

            if (values.size == 1) {
                requestBuilder.header(key, values[0])
            } else {
                values.forEach { value ->
                    requestBuilder.addHeader(key, value)
                }
            }
        }

        val okHttpRequest = requestBuilder.build()
        val response = client.newCall(okHttpRequest).execute()

        // NewPipeExtractor v0.26.1's `Response` constructor only accepts a `String`
        // body (the `byte[]` overload that existed in v0.24.x was removed). We read
        // the raw decompressed bytes from OkHttp and round-trip them through
        // ISO-8859-1 — a 1:1 byte<->char mapping that is lossless for ANY byte
        // sequence (UTF-8 text, Latin-1 text, or binary YouTube Protobufs). When a
        // NewPipe extractor needs the original bytes back it can call
        // `responseBody().getBytes(StandardCharsets.ISO_8859_1)` and get the exact
        // bytes OkHttp received after transparent gzip decompression.
        //
        // We deliberately do NOT use OkHttp's `.string()` here: that method decodes
        // using the Content-Type charset (defaulting to UTF-8) and would corrupt
        // non-text responses such as YouTube's binary Protobufs.
        val responseBytes = response.body?.bytes() ?: ByteArray(0)
        val responseBody = String(responseBytes, Charsets.ISO_8859_1)

        val responseHeaders = mutableMapOf<String, List<String>>()
        response.headers.names().forEach { name ->
            responseHeaders[name] = response.headers.values(name)
        }

        // Pass the lossless String body to NewPipe.
        return ExtractorResponse(
            response.code,
            response.message,
            responseHeaders,
            responseBody,
            response.request.url.toString()
        )
    }
}
