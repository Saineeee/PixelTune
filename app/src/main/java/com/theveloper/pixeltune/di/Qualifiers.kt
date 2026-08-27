package com.theveloper.pixeltune.di

import javax.inject.Qualifier

/**
 * Qualifier for Deezer Retrofit instance.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DeezerRetrofit

/**
 * Qualifier for Fast OkHttpClient (Short timeouts).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class FastOkHttpClient

/**
 * Qualifier for the OkHttpClient used by the cloud-streaming proxies
 * (YouTube, Netease, SoundCloud, GDrive).
 *
 * This client MUST NOT use the app-wide 8s readTimeout — that timeout is
 * the root cause of the "YouTube playback stuck at 00:00" bug. YouTube
 * throttles audio streams adaptively; reading a 5 MB body via OkHttp's
 * `bytes()` (or any per-chunk read) can stall for >8s between bytes,
 * which throws `SocketTimeoutException`. The proxy then has nothing to
 * send to ExoPlayer, ExoPlayer's own connect timeout fires, and the
 * progress bar stays frozen at 00:00 with no error surfaced to the UI.
 *
 * The streaming client below uses:
 *   - connectTimeout = 30s (generous, accommodates slow DNS / TLS)
 *   - readTimeout    = 0  (infinite — needed for throttled upstreams)
 *   - writeTimeout   = 30s
 *   - callTimeout     = 0  (infinite — the call ends when the body is consumed)
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class StreamingOkHttpClient

/**
 * Qualifier for the dedicated OkHttpClient used by NewPipeDownloader
 * (all YouTube / YouTube Music / SoundCloud extractor requests).
 *
 * FIX(streaming-performance): NewPipe previously ran on the app-wide default
 * client, which attaches an HttpLoggingInterceptor at Level.BODY in DEBUG
 * builds. The CI ships debug APKs, so EVERY extractor response body —
 * search JSON (100s of KB), 4-5 sequential /player + /next JSON responses per
 * playback (100s of KB each), sw.js / base.js (1-2+ MB) — was being piped
 * through android.util.Log in 4 KB chunks. That logcat I/O plus the string
 * allocations/GC it triggers added whole seconds to every search and every
 * playback start ("incredibly slow" search results / playback / artwork in
 * the user report).
 *
 * This client NEVER logs bodies: BASIC (request line + headers) in debug for
 * diagnosability, NONE in release. It also uses a read timeout sized for
 * multi-megabyte extractor pages (sw.js, base.js, watch/search HTML) on slow
 * networks, which the default 8s read timeout occasionally tripped.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class NewPipeOkHttpClient

/**
 * Qualifier for Gson instance configured for backup serialization.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BackupGson

/**
 * Qualifier for Netease Cloud Music Retrofit instance.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class NeteaseRetrofit

