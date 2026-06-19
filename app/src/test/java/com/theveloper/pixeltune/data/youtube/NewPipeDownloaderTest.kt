package com.theveloper.pixeltune.data.youtube

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.schabi.newpipe.extractor.downloader.Request as ExtractorRequest

/**
 * Unit tests for [NewPipeDownloader].
 *
 * These tests pin down the browser User-Agent fix that addresses two production
 * bugs that were reproducible from the in-app logcats:
 *
 *   1. YouTube playback stuck at 00:00
 *      NewPipe's `YoutubeStreamExtractor.fetchPage()` was throwing
 *      `ContentNotAvailableException: The page needs to be reloaded` because the
 *      app-wide OkHttp interceptor was replacing every request's User-Agent with
 *      `PixelTune/1.0 (Android; Music Player)`. YouTube saw a non-browser UA and
 *      served a bot-check page; the proxy then returned 404 to ExoPlayer, which
 *      silently sat at 00:00.
 *
 *   2. SoundCloud search returning zero results
 *      Some SoundCloud responses served to non-browser UAs omit the `<script>`
 *      tags that `SoundcloudParsingHelper.clientId()` scans, breaking the
 *      client_id extraction and surfacing as `ParsingException: Could not get
 *      client id`.
 *
 * The fix lives in two places:
 *   - [NewPipeDownloader] explicitly sets a Chrome browser UA on every request
 *     it issues and refuses to let NewPipe's own `User-Agent` header override it.
 *   - `AppModule.provideOkHttpClient` only injects the default app UA when no UA
 *     is present, so the browser UA set here survives end-to-end.
 *
 * No network calls are made — the OkHttp client below installs a short-circuit
 * interceptor that returns a synthetic 200 response and captures the outgoing
 * request headers for assertion.
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
    fun `does not let a NewPipe-supplied User-Agent override the browser UA`() {
        // NewPipe sometimes attaches its own UA to outgoing requests (e.g. when
        // the underlying extractor sets `User-Agent` in its headers map). Our
        // downloader MUST ignore that and always send the browser UA — otherwise
        // YouTube sees a non-browser UA and serves the "page needs to be reloaded"
        // bot-check page, which is the exact bug we are guarding against.
        val capture = executeAndCaptureHeaders(
            mapOf("User-Agent" to listOf("NewPipe/0.26.1 (parser)"))
        )

        val ua = capture.capturedHeaders["User-Agent"]
        assertEquals(
            expectedBrowserUa,
            ua,
            "Browser UA must win over any UA supplied by NewPipe"
        )
    }

    @Test
    fun `overrides User-Agent case-insensitively`() {
        // Defensive: header-name matching in our skip-list is case-insensitive,
        // so a NewPipe header keyed as "user-agent" (lowercase) must also be
        // ignored, not appended on top of our browser UA (which would produce
        // two User-Agent values and let OkHttp pick one arbitrarily).
        val capture = executeAndCaptureHeaders(
            mapOf("user-agent" to listOf("NewPipe/0.26.1 (parser)"))
        )

        val ua = capture.capturedHeaders["User-Agent"]
        assertEquals(expectedBrowserUa, ua)
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

    @Test
    fun `response body round-trips binary bytes losslessly via ISO-8859-1`() {
        // YouTube returns binary Protobuf bodies for some inner-tube requests.
        // NewPipe v0.26.1's Response body is typed as String, so we encode the
        // raw bytes via ISO-8859-1 (a 1:1 byte<->char mapping). Verify that a
        // caller doing `responseBody().getBytes(ISO_8859_1)` gets the exact
        // original bytes back — including non-UTF-8 byte sequences.
        val binaryPayload = byteArrayOf(
            0x0A, 0x12.toByte(), 0x08.toByte(), 0x01, 0x12.toByte(), 0x0E.toByte(),
            0xFF.toByte(), 0xFE.toByte(), 0xFD.toByte(), 0xFC.toByte(),
            0x00, 0x7F, 0x80.toByte(), 0x81.toByte(), 0xC0.toByte(), 0xC1.toByte()
        )

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(binaryPayload.toResponseBody(null))
                    .build()
            }
            .build()

        val downloader = NewPipeDownloader(client)
        val request = ExtractorRequest.Builder()
            .httpMethod("GET")
            .url("https://www.example.com/")
            .build()
        val response = downloader.execute(request)

        val roundTripped = response.responseBody()!!.toByteArray(Charsets.ISO_8859_1)

        // Byte-for-byte equality with the original payload — no UTF-8 corruption.
        assertEquals(binaryPayload.size, roundTripped.size)
        for (i in binaryPayload.indices) {
            assertEquals(binaryPayload[i], roundTripped[i], "byte mismatch at index $i")
        }
    }
}
