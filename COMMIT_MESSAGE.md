fix(youtube): stream audio chunk-by-chunk instead of buffering the entire body

Fixes "YouTube playback stuck at 00:00" — the player loads metadata but
the progress bar stays frozen at 00:00 and no audio is ever heard.

ROOT CAUSE
==========
All four cloud-streaming proxies (YouTubeStreamProxy, NeteaseStreamProxy,
SoundCloudStreamProxy, GDriveStreamProxy) buffered the ENTIRE upstream
response in memory via OkHttp's `ResponseBody.bytes()` before forwarding
anything to ExoPlayer via `respondBytes`. This re-introduced (and never
actually fixed) the original 00:00 freeze bug for two compounding reasons:

  1. The default app-wide OkHttpClient has `readTimeout = 8s`. YouTube
     adaptively throttles audio streams, so reading a 5 MB body via
     `bytes()` can stall for >8s between bytes, which throws
     `SocketTimeoutException` mid-read.

  2. Because `bytes()` is called BEFORE `respondBytes`, the proxy never
     sends an HTTP status line to ExoPlayer until the whole body is
     buffered. ExoPlayer's `DefaultHttpDataSource.open()` blocks at
     `HttpURLConnectionImpl.getResponseCode()` waiting for the status
     line, hits its own 8s connect timeout, throws its own
     `SocketTimeoutException`, and the player just sits at 00:00 with
     no error surfaced to the UI.

The previous "fix" comment in YouTubeStreamProxy claimed that
`respondBytesWriter + withContext(Dispatchers.IO)` had a CIO-engine
deadlock and that buffering via `respondBytes` avoided it. That
conclusion was wrong: buffering just shifts the timeout from ExoPlayer's
side to OkHttp's side. The deadlock was actually caused by calling
`respondBytesWriter` from inside `withContext(Dispatchers.IO)`, not by
streaming itself.

FIX
===
1. Add a dedicated `@StreamingOkHttpClient` Hilt qualifier + provider in
   di/ that injects a streaming OkHttpClient with:
     - connectTimeout = 30s  (generous DNS / TLS)
     - readTimeout    = 0    (infinite — REQUIRED for throttled upstreams)
     - writeTimeout   = 30s
     - callTimeout    = 0    (infinite — ends when body is consumed)
   The per-source browser User-Agent header is preserved.

2. Introduce a new shared `CloudStreamForwarder` helper (data/stream/)
   that streams an upstream HTTP response chunk-by-chunk to a Ktor
   response, NEVER buffering more than 8 KB in memory at once. The
   forwarder:
     a. Opens the upstream OkHttp connection on Dispatchers.IO.
     b. Validates status code + Content-Type + Content-Length.
     c. Sets Ktor response status + headers IMMEDIATELY (Ktor's
        `respondOutputStream` honors pre-set status/headers and
        flushes them through the CIO pipeline before invoking the
        body lambda — ExoPlayer therefore receives the HTTP status
        line within milliseconds of the upstream's response).
     d. Streams the body in 8 KB chunks through
        `ApplicationCall.respondOutputStream`, deliberately NOT
        wrapped in `withContext(Dispatchers.IO)` (that's what caused
        the legacy CIO deadlock).

3. Refactor YouTubeStreamProxy, NeteaseStreamProxy,
   SoundCloudStreamProxy, GDriveStreamProxy to use the new forwarder.
   All four proxies now inject `@StreamingOkHttpClient` on their
   constructor parameters.

4. GDriveStreamProxy uses the forwarder's `forwardOpenedStream` variant
   (rather than `forwardStream`) because it has 401-token-refresh retry
   logic that needs to keep the upstream Response open and forward it
   only after potential retries.

5. Add CloudStreamForwarderTest — unit tests pinning down the
   chunk-by-chunk streaming behavior with edge cases (empty body,
   single-byte body, body whose size is an exact multiple of BUFFER_SIZE,
   body one byte larger than BUFFER_SIZE, large multi-chunk body).

WHY THIS FIX IS COMPLETE
========================
- ExoPlayer's `getResponseCode()` no longer times out: Ktor flushes
  status + headers before any body byte is read.
- OkHttp's `bytes()` no longer throws on throttled streams: we use
  `body.byteStream().read()` with a streaming client whose
  `readTimeout = 0`.
- ExoPlayer receives the first audio bytes within milliseconds of the
  upstream's response, so playback begins immediately.
- Memory pressure is bounded: at most 8 KB in flight at any time, even
  for 50 MB lossless streams.

Files changed:
  * app/src/main/java/com/theveloper/pixeltune/di/Qualifiers.kt
    - New `@StreamingOkHttpClient` qualifier.
  * app/src/main/java/com/theveloper/pixeltune/di/AppModule.kt
    - New `provideStreamingOkHttpClient()` provider.
    - `provideYouTubeStreamProxy` and `provideSoundCloudStreamProxy`
      now inject `@StreamingOkHttpClient`.
  * app/src/main/java/com/theveloper/pixeltune/data/stream/CloudStreamForwarder.kt (NEW)
    - Shared streaming helper.
  * app/src/main/java/com/theveloper/pixeltune/data/youtube/YouTubeStreamProxy.kt
  * app/src/main/java/com/theveloper/pixeltune/data/netease/NeteaseStreamProxy.kt
  * app/src/main/java/com/theveloper/pixeltune/data/soundcloud/SoundCloudStreamProxy.kt
  * app/src/main/java/com/theveloper/pixeltune/data/gdrive/GDriveStreamProxy.kt
    - All four proxies use CloudStreamForwarder instead of `bytes()` +
      `respondBytes`, and inject `@StreamingOkHttpClient`.
  * app/src/test/java/com/theveloper/pixeltune/data/stream/CloudStreamForwarderTest.kt (NEW)
    - Unit tests for the chunk-by-chunk streaming behavior.

Note: TelegramStreamProxy was NOT modified because it streams from a
local file (RandomAccessFile) via `respondBytesWriter + writeFully` —
no buffering, no network throttling, already works.
