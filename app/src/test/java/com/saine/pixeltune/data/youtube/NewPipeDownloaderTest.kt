package com.saine.pixeltune.data.youtube

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.schabi.newpipe.extractor.downloader.Request as ExtractorRequest

/**
 * Unit tests for [NewPipeDownloader].
 *
 * These tests pin down the downloader's HTTP identity contract, which guards
 * against two production incident classes that were reproducible from the
 * in-app logcats:
 *
 *   1. YouTube playback stuck at 00:00
 *      youtube.com HTML fetches (e.g. the search results page) were served a
 *      bot-check page ("The page needs to be reloaded") whenever the request
 *      carried a non-browser User-Agent; the proxy then returned 404 to
 *      ExoPlayer, which silently sat at 00:00.
 *
 *   2. SoundCloud search returning zero results
 *      Some SoundCloud responses served to non-browser UAs omit the `<script>`
 *      tags that `SoundcloudParsingHelper.clientId()` scans, breaking the
 *      client_id extraction and surfacing as `ParsingException: Could not get
 *      client id`.
 *
 * The contract these tests enforce (see [NewPipeDownloader.execute]):
 *   - Requests WITHOUT a User-Agent (the youtube.com results HTML, the
 *     SoundCloud homepage, sw.js) get a desktop Chrome browser UA — the fix
 *     for both incidents above.
 *   - NewPipe's OWN User-Agent — the ANDROID / IOS / VISIONOS InnerTube client
 *     UAs that NewPipeExtractor v0.26.3 sends on its stream-extraction POSTs —
 *     is forwarded verbatim. Overwriting it with a desktop Chrome UA is a
 *     client-identity mismatch that YouTube answers with degraded responses
 *     or bot checks (slow/failed stream resolution).
 *   - Accept-Encoding is never forwarded verbatim so OkHttp keeps performing
 *     transparent gzip decompression (the SoundCloud client_id scraper needs
 *     uncompressed HTML).
 *   - Response bodies are decoded with the charset the server declared in
 *     Content-Type, defaulting to UTF-8 — the same behavior as OkHttp's
 *     `.string()` and the official NewPipe app downloader. An ISO-8859-1
 *     decode corrupted every non-ASCII title/artist into mojibake ("Ã©",
 *     "ðŸŽµ") — the garbled YouTube/SoundCloud metadata users reported.
 *
 * No network calls are made — the OkHttp clients below install short-circuit
 * interceptors that return synthetic 200 responses and capture the outgoing
 * request headers / response body bytes for assertion.
 */
class NewPipeDownloaderTest {

    private val expectedBrowserUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /**
     * Builds an OkHttpClient that captures every header on the outgoing request
     * and returns a synthetic 200 response without performing any network I/O.
     *
     * Because the interceptor never calls `chain.proceed()`, OkHttp's automatic
     * `Accept-Encoding: gzip` injection (which only happens at network time) is
     * NOT applied — so the captured headers reflect exactly what our code put
     * on the Request.Builder.
     */
    private class HeaderCaptureClient {
        val capturedHeaders = mutableMapOf<String, String>()

        val client: OkHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.request().headers.forEach { (name, value) ->
                    capturedHeaders[name] = value
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ByteArray(0).toResponseBody(null))
                    .build()
            }
            .build()
    }

    /**
     * Runs the downloader against the given NewPipe request headers and returns
     * the captured outgoing headers.
     *
     * We use NewPipe's [Request.Builder] API (rather than the public 6-arg
     * constructor) because it is stable across NewPipe versions and lets the
     * test stay readable. The Builder sets `automaticLocalizationHeader=false`
     * and `localization=null` by default, which matches what NewPipe's own
     * `get(url)` / `post(url, body)` helpers produce internally.
     */
    private fun executeAndCaptureHeaders(
        newPipeHeaders: Map<String, List<String>>
    ): HeaderCaptureClient {
        val capture = HeaderCaptureClient()
        val downloader = NewPipeDownloader(capture.client)
        val request = ExtractorRequest.Builder()
            .httpMethod("GET")
            .url("https://www.example.com/")
            .headers(newPipeHeaders)
            .build()
        downloader.execute(request)
        return capture
    }

    @Test
    fun `sets browser User-Agent when the incoming request has no UA`() {
        val capture = executeAndCaptureHeaders(emptyMap())

        val ua = capture.capturedHeaders["User-Agent"]
        assertNotNull(ua, "User-Agent must be present on every NewPipe request")
        assertEquals(expectedBrowserUa, ua)
    }

    @Test
    fun `forwards a NewPipe-supplied User-Agent instead of overwriting it with the browser UA`() {
        // NewPipeExtractor v0.26.3 identifies its mobile InnerTube clients
        // (ANDROID / IOS / VISIONOS) through the User-Agent on the
        // stream-extraction POSTs. Overwriting that with a desktop Chrome UA
        // is a client-identity mismatch that YouTube answers with degraded
        // responses or bot checks (slow/failed stream resolution), so when
        // NewPipe supplies a UA it MUST be forwarded verbatim — the browser
        // UA is only a default for requests that carry none.
        val capture = executeAndCaptureHeaders(
            mapOf("User-Agent" to listOf("NewPipe/0.26.1 (parser)"))
        )

        val ua = capture.capturedHeaders["User-Agent"]
        assertEquals("NewPipe/0.26.1 (parser)", ua)
        assertNotEquals(expectedBrowserUa, ua)
    }

    @Test
    fun `detects a NewPipe-supplied User-Agent case-insensitively and never duplicates it`() {
        // Defensive: the "does NewPipe provide its own User-Agent?" check is
        // case-insensitive, so a NewPipe header keyed as "user-agent"
        // (lowercase) must ALSO be recognized and forwarded as-is — and the
        // browser UA default must NOT be appended on top of it, which would
        // produce two User-Agent values and let OkHttp pick one arbitrarily.
        val capture = executeAndCaptureHeaders(
            mapOf("user-agent" to listOf("NewPipe/0.26.1 (parser)"))
        )

        val uaHeaders = capture.capturedHeaders.entries
            .filter { it.key.equals("User-Agent", ignoreCase = true) }
        assertEquals(1, uaHeaders.size, "exactly one User-Agent header must be sent")
        assertEquals("NewPipe/0.26.1 (parser)", uaHeaders.single().value)
        assertNotEquals(expectedBrowserUa, uaHeaders.single().value)
    }

    @Test
    fun `sets Accept-Language header for browser-like requests`() {
        // A missing Accept-Language can cause YouTube to return region/consent
        // redirects and can degrade SoundCloud's response shape, so we always
        // send one. This mirrors what a real Chrome request would carry.
        val capture = executeAndCaptureHeaders(emptyMap())

        val acceptLanguage = capture.capturedHeaders["Accept-Language"]
        assertNotNull(acceptLanguage, "Accept-Language must be present")
        assertEquals("en-GB, en;q=0.9", acceptLanguage)
    }

    @Test
    fun `forwards non-conflicting custom headers from the NewPipe request`() {
        // The UA override must NOT be a blanket "drop all NewPipe headers"
        // behavior — only User-Agent and Accept-Encoding are special-cased.
        // Other headers (cookies, X-YouTube-Client-*, Accept, etc.) must still
        // be forwarded so NewPipe's extractors keep working.
        val capture = executeAndCaptureHeaders(
            mapOf(
                "X-Custom-Header" to listOf("custom-value"),
                "Accept" to listOf("application/json")
            )
        )

        assertEquals("custom-value", capture.capturedHeaders["X-Custom-Header"])
        assertEquals("application/json", capture.capturedHeaders["Accept"])
    }

    @Test
    fun `does not forward NewPipe-supplied Accept-Encoding verbatim`() {
        // Forwarding `Accept-Encoding: gzip` would prevent OkHttp from
        // transparently decompressing the response — NewPipe's SoundCloud
        // client_id scraper expects raw (decompressed) HTML bytes, and feeding
        // it gzipped bytes was a previous cause of `Could not get client id`.
        // Our downloader skips Accept-Encoding; OkHttp then handles it
        // internally and decompresses for us.
        val capture = executeAndCaptureHeaders(
            mapOf("Accept-Encoding" to listOf("gzip, deflate"))
        )

        // Our short-circuit interceptor never reaches OkHttp's network-time
        // auto-injection, so the captured request must NOT carry any
        // Accept-Encoding value that we explicitly forwarded.
        val forwarded = capture.capturedHeaders["Accept-Encoding"]
        assertEquals(
            null,
            forwarded,
            "NewPipe-supplied Accept-Encoding must not be forwarded verbatim"
        )
    }

    @Test
    fun `returns NewPipe Response with the upstream status code and body string`() {
        // End-to-end smoke test: ensure the Response wrapper we hand back to
        // NewPipe carries the synthetic 200 status and the (empty) body string,
        // so the extractor's `response.responseBody()` / `response.responseCode()`
        // calls don't blow up downstream.
        //
        // NewPipeExtractor v0.26.1's Response constructor only accepts a String
        // body (the byte[] overload that existed in v0.24.x was removed), so we
        // verify the contract here too.
        val capture = HeaderCaptureClient()
        val downloader = NewPipeDownloader(capture.client)
        val request = ExtractorRequest.Builder()
            .httpMethod("GET")
            .url("https://www.example.com/")
            .build()

        val response = downloader.execute(request)

        assertEquals(200, response.responseCode())
        assertNotNull(response.responseBody())
        assertEquals("", response.responseBody())
    }

    /**
     * Builds an OkHttpClient whose interceptor returns a synthetic 200 response
     * carrying exactly [payload] as the body (and [contentType] as BOTH the
     * Content-Type header and the body's media type — [NewPipeDownloader]
     * resolves the decode charset from the response HEADER, so it must be
     * present on the wire) — no network I/O.
     */
    private fun binaryResponseClient(payload: ByteArray, contentType: String?): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .apply {
                        if (contentType != null) header("Content-Type", contentType)
                    }
                    .body(payload.toResponseBody(contentType?.toMediaType()))
                    .build()
            }
            .build()

    private fun executeWithClient(client: OkHttpClient): org.schabi.newpipe.extractor.downloader.Response {
        val downloader = NewPipeDownloader(client)
        val request = ExtractorRequest.Builder()
            .httpMethod("GET")
            .url("https://www.example.com/")
            .build()
        return downloader.execute(request)
    }

    @Test
    fun `decodes UTF-8 bodies correctly so non-ASCII metadata never becomes mojibake`() {
        // FIX(mojibake-metadata): bodies are decoded with the Content-Type
        // charset (default UTF-8), NOT ISO-8859-1. Under ISO-8859-1 every
        // multi-byte UTF-8 sequence was garbled ("é" → "Ã©", emojis → "ðŸŽµ",
        // Bengali/Hindi/Korean titles → symbol soup). NewPipe's consumers
        // (nanojson, Jsoup, regex) parse the String body directly, so a
        // correct decode is what keeps YouTube/SoundCloud titles readable.
        val payload = "Café ☕ 音乐 — PixelTune".toByteArray(Charsets.UTF_8)

        val response = executeWithClient(
            binaryResponseClient(payload, "application/json; charset=UTF-8")
        )

        assertEquals("Café ☕ 音乐 — PixelTune", response.responseBody())
    }

    @Test
    fun `honors a non-UTF-8 charset declared in the Content-Type header`() {
        // A server that really serves Latin-1 content gets a Latin-1 decode:
        // 0xE9 is "é" in ISO-8859-1 (a UTF-8 decode would replace it with
        // U+FFFD instead).
        val payload = byteArrayOf(0xE9.toByte(), 'A'.code.toByte())

        val response = executeWithClient(
            binaryResponseClient(payload, "text/html; charset=ISO-8859-1")
        )

        assertEquals("éA", response.responseBody())
    }

    @Test
    fun `replaces invalid UTF-8 bytes with U+FFFD instead of corrupting them into mojibake`() {
        // Genuinely binary bytes (e.g. binary Protobuf inner-tube payloads)
        // that are not valid UTF-8 decode with U+FFFD replacement — the same
        // behavior as OkHttp's `.string()` and the official NewPipe downloader
        // — never as Latin-1 mojibake characters ("Ã", "Â", ...).
        val binaryPayload = byteArrayOf(
            0x0A, 0x12.toByte(), 0x08.toByte(), 0x01, 0x12.toByte(), 0x0E.toByte(),
            0xFF.toByte(), 0xFE.toByte(), 0xFD.toByte(), 0xFC.toByte(),
            0x00, 0x7F, 0x80.toByte(), 0x81.toByte(), 0xC0.toByte(), 0xC1.toByte()
        )

        // No Content-Type → the UTF-8 default applies.
        val response = executeWithClient(binaryResponseClient(binaryPayload, null))

        val decoded = response.responseBody()!!
        // Every valid ASCII byte survives unchanged; every invalid byte
        // becomes U+FFFD — no mojibake characters anywhere in the body.
        assertTrue(
            decoded.all { it.code < 0x80 || it == '\uFFFD' },
            "decoded body must contain only ASCII and U+FFFD, got: " +
                decoded.map { it.code }
        )
        // All 8 bytes 0xFF 0xFE 0xFD 0xFC 0x80 0x81 0xC0 0xC1 are individually
        // invalid UTF-8 and must each yield exactly one replacement char.
        assertEquals(8, decoded.count { it == '\uFFFD' })
        // The ASCII control-byte prefix survives byte-for-byte.
        assertEquals("\u000A\u0012\u0008\u0001\u0012\u000E", decoded.substring(0, 6))
    }
}
