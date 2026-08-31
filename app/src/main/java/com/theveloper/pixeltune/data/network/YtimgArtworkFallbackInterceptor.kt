package com.theveloper.pixeltune.data.network

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * FIX(youtube-art-quality): 404-fallback chain for YouTube video thumbnails
 * on the album-artwork HTTP client.
 *
 * [com.theveloper.pixeltune.data.stream.CloudArtworkHelper.upgradeToHighRes]
 * rewrites YouTube thumbnail URLs to the deterministic
 * `https://i.ytimg.com/vi/<id>/maxresdefault.jpg` (1280x720) because the
 * variants NewPipe exposes are tiny (60x60/120x120 covers, 168-336px crops).
 * `maxresdefault` exists for essentially every official music video, but it
 * is NOT guaranteed — a small minority of uploads (very old, private-turned
 * public, some user content) have no maxres rendition and the CDN answers
 * 404.
 *
 * Without a safety net that 404 would surface as a missing album-art
 * placeholder — a REGRESSION for exactly those videos, whose small thumbnail
 * rendered fine before the upgrade. This interceptor closes the gap at the
 * network layer, transparently to Coil:
 *
 *  - `maxresdefault.jpg` 404/410 -> retry the same video's `hq720.jpg`
 *    (also 1280x720 for videos that have it)
 *  - `hq720.jpg` 404/410        -> retry `hqdefault.jpg` (480x360 — exists
 *    for EVERY valid YouTube video; this is the exact quality floor the app
 *    served before the upgrade, so the worst case is never worse than
 *    before)
 *  - anything else (non-ytimg host, non-404 error, success) passes through
 *    untouched.
 *
 * Only the artwork client runs this interceptor (see
 * [com.theveloper.pixeltune.di.AppModule.provideImageLoader]) — audio
 * streaming, NewPipe extraction and every other client are unaffected, and
 * an in-flight artwork request pays at most two extra HEAD-less GET retries
 * in the rare 404 case.
 *
 * OkHttp application interceptors are allowed to call [Interceptor.Chain.proceed]
 * more than once (sequential retries are a documented pattern); every
 * discarded response is closed before the retry so no connection leaks.
 */
class YtimgArtworkFallbackInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var response = chain.proceed(request)
        var currentUrl = request.url

        // Walk the fallback chain (maxresdefault -> hq720 -> hqdefault — at
        // most two retries) until a request succeeds or the chain is
        // exhausted. A plain if/return would stop at the FIRST retry, but a
        // video can lack BOTH maxresdefault and hq720 (verified live: the
        // 2005 video jNQXAC9IVRw 404s both); the loop still reaches
        // hqdefault, which exists for every valid video.
        while (!response.isSuccessful) {
            val fallbackFileName = fallbackThumbnailFor(response.code, currentUrl)
                ?: break
            // Drop any query parameters together with the file name: the
            // search thumbnails' `sqp`/`rs` parameters request CDN-side crops
            // tied to the original file name, and the bare hq720/hqdefault
            // URLs are what the CDN serves full-size.
            response.close()
            val fallbackUrl = currentUrl.newBuilder()
                .apply {
                    setPathSegment(currentUrl.pathSegments.size - 1, fallbackFileName)
                    query(null)
                }
                .build()
            currentUrl = fallbackUrl
            response = chain.proceed(request.newBuilder().url(fallbackUrl).build())
        }
        return response
    }

    /**
     * The next file name to try for a failed `ytimg.com` thumbnail request,
     * or null when the response is successful-ish, not a 404/410, not an
     * ytimg host, or the failed file has no lower-tier fallback left.
     *
     * (Note: `HttpUrl` has no `lastPathSegment` accessor in OkHttp — the last
     * element of `pathSegments` is the file-name segment for these
     * `/vi/<id>/<file>` URLs.)
     */
    private fun fallbackThumbnailFor(code: Int, url: HttpUrl): String? {
        if (code != 404 && code != 410) return null
        if (!url.host.endsWith("ytimg.com")) return null
        return when (url.pathSegments.lastOrNull()) {
            MAXRES_FILE_NAME -> HQ720_FILE_NAME
            HQ720_FILE_NAME -> HQDEFAULT_FILE_NAME
            else -> null
        }
    }

    private companion object {
        private const val MAXRES_FILE_NAME = "maxresdefault.jpg"
        private const val HQ720_FILE_NAME = "hq720.jpg"
        private const val HQDEFAULT_FILE_NAME = "hqdefault.jpg"
    }
}
