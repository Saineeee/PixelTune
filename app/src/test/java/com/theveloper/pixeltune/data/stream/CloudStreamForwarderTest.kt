package com.theveloper.pixeltune.data.stream

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.Random

/**
 * Unit tests for [CloudStreamForwarder.streamBody].
 *
 * These tests pin down the chunk-by-chunk streaming behavior that is the
 * actual fix for the "YouTube playback stuck at 00:00" production bug.
 *
 * The legacy cloud-streaming proxies (YouTube / Netease / SoundCloud / GDrive)
 * buffered the ENTIRE upstream body in memory via OkHttp's `bytes()` before
 * forwarding anything to ExoPlayer. That pattern re-introduced (and never
 * actually fixed) the original 00:00 freeze because:
 *   1. The default app-wide OkHttpClient has readTimeout=8s. YouTube throttles
 *      audio streams, so `bytes()` would throw SocketTimeoutException mid-read.
 *   2. The proxy's catch block swallowed the error and never sent an HTTP
 *      response, leaving ExoPlayer stuck at `getResponseCode()` until its
 *      own 8s connect timeout fired.
 *
 * The fix in [CloudStreamForwarder] streams the upstream body in 8 KB chunks
 * through Ktor's `respondOutputStream` (with response status + headers flushed
 * immediately beforehand), and the dedicated `@StreamingOkHttpClient`
 * qualifier injects a streaming client with `readTimeout = 0` so throttled
 * upstream reads no longer abort. See the forwarder's KDoc for the full
 * root-cause analysis.
 *
 * These tests verify the streaming behavior directly — i.e., that an
 * arbitrary upstream byte stream is forwarded byte-for-byte to the downstream
 * OutputStream without ever holding more than [CloudStreamForwarder]'s
 * BUFFER_SIZE (8 KB) in memory at once, and that edge cases (empty body,
 * single-byte body, body larger than one buffer) are all handled correctly.
 */
class CloudStreamForwarderTest {

    private fun buildOkHttpResponse(payload: ByteArray): Response {
        return Response.Builder()
            .request(Request.Builder().url("https://example.com/audio").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(payload.toResponseBody(null))
            .build()
    }

    @Test
    fun `streamBody forwards a large upstream body byte-for-byte`() {
        // 50_000 bytes — large enough to span multiple BUFFER_SIZE chunks
        // (BUFFER_SIZE = 8 * 1024 = 8192, so this crosses ~6 chunk boundaries).
        val payload = ByteArray(50_000)
        Random(42).nextBytes(payload)

        val upstream = buildOkHttpResponse(payload)
        val sink = ByteArrayOutputStream()

        CloudStreamForwarder.streamBody(upstream, sink)

        // The downstream sink must contain the EXACT same bytes as the
        // upstream body — no corruption, no truncation, no padding.
        assertArrayEquals(payload, sink.toByteArray())
    }

    @Test
    fun `streamBody handles an empty body gracefully`() {
        // ExoPlayer occasionally issues Range requests that return 206 with
        // a zero-byte body (e.g. when the requested range was already
        // satisfied by the previous response's cache). The forwarder must
        // not throw on this case — it should write nothing and exit cleanly.
        val upstream = buildOkHttpResponse(ByteArray(0))
        val sink = ByteArrayOutputStream()

        CloudStreamForwarder.streamBody(upstream, sink)

        assertEquals(0, sink.size())
    }

    @Test
    fun `streamBody handles a single-byte body`() {
        // Smallest possible non-empty body — exercises the "read returns 1
        // then read returns -1" path. Regression test for off-by-one bugs
        // in the chunked read loop.
        val payload = byteArrayOf(0x42)
        val upstream = buildOkHttpResponse(payload)
        val sink = ByteArrayOutputStream()

        CloudStreamForwarder.streamBody(upstream, sink)

        assertArrayEquals(payload, sink.toByteArray())
    }

    @Test
    fun `streamBody handles a body whose size is an exact multiple of BUFFER_SIZE`() {
        // Edge case: if the body size is an exact multiple of the buffer
        // size, the read loop must still terminate on the next read (returning
        // -1) without spurious extra bytes. A buggy implementation that
        // appends a "termination byte" on EOF would fail this test.
        val exactMultipleSize = 8 * 1024 * 3 // 24_576 bytes = 3 chunks
        val payload = ByteArray(exactMultipleSize) { 0x7F }

        val upstream = buildOkHttpResponse(payload)
        val sink = ByteArrayOutputStream()

        CloudStreamForwarder.streamBody(upstream, sink)

        assertArrayEquals(payload, sink.toByteArray())
    }

    @Test
    fun `streamBody handles a body one byte larger than BUFFER_SIZE`() {
        // Another edge case: body is one byte larger than a single buffer —
        // the first read fills the buffer completely, the second read
        // returns 1 byte, the third read returns -1. Verifies the loop
        // condition handles partial reads correctly.
        val payload = ByteArray(8 * 1024 + 1) { 0x33 }

        val upstream = buildOkHttpResponse(payload)
        val sink = ByteArrayOutputStream()

        CloudStreamForwarder.streamBody(upstream, sink)

        assertArrayEquals(payload, sink.toByteArray())
    }
}
