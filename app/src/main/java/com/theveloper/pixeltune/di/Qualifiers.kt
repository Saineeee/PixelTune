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

