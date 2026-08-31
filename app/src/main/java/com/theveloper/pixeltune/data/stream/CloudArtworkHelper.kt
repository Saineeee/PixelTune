package com.theveloper.pixeltune.data.stream

import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * FIX(album-art-quality): picks the HIGHEST-resolution artwork URL from a
 * NewPipe item's thumbnail list.
 *
 * NewPipeExtractor v0.26.3 returns thumbnail lists ordered SMALLEST-FIRST
 * (it preserves the order of the provider's JSON array / generates its own
 * ascending-size list):
 *
 *  - SoundCloud items generate the full variant list ascending:
 *    mini(16x16), t20x20, small(32), badge(47), t50x50, t60x60, t67x67,
 *    t80x80, large(100), t120x120, t200x200, t240x240, t250x250, t300x300,
 *    t500x500 — see SoundcloudParsingHelper.ALBUMS_AND_ARTWORKS_IMAGE_SUFFIXES.
 *    Taking `thumbnails.firstOrNull()` (what the repositories did before)
 *    picked the **16x16 "mini"** artwork — the "very low quality and blurry"
 *    album art reported on SoundCloud playback.
 *
 *  - YouTube Music search items carry `musicThumbnailRenderer` variants,
 *    also smallest-first (typically 60x60, 120x120, 226x226, 544x544) —
 *    `firstOrNull()` picked the 60x60 one.
 *
 *  - Generic YouTube search items usually expose a SINGLE thumbnail entry
 *    (i.ytimg.com hqdefault) whose dimensions are UNKNOWN in the JSON
 *    ([Image.WIDTH_UNKNOWN] / [Image.HEIGHT_UNKNOWN]) — so an area-based
 *    ranking must fall back to the first entry when no image reports a size.
 *
 * The ranking below scores every image by its pixel area (unknown dimensions
 * score 0) and returns the URL of the largest one; when all entries are
 * unsized it keeps the old behavior (first entry) so generic YouTube results
 * are unaffected.
 *
 * FIX(youtube-art-quality): picking the LARGEST reported variant is still not
 * enough for YouTube — NewPipe's lists cap far below what the CDNs actually
 * serve:
 *
 *  - **YouTube Music song items** (the app's PRIMARY search tier, filter
 *    MUSIC_SONGS) expose only 60x60 + 120x120 square artwork URLs on
 *    `yt3.googleusercontent.com` (verified live with a JVM harness running
 *    the real extractor v0.26.3). The player screen then upscales a 120px
 *    bitmap up to ~9x — the "low resolution album art" reported when
 *    playing YouTube-sourced songs.
 *  - **Radio-mix / generic-search items** expose cropped 168-336px (and
 *    720x404) `i.ytimg.com` thumbnails, while the same CDN serves the
 *    deterministic `maxresdefault.jpg` at 1280x720 for the very same video.
 *
 * [upgradeToHighRes] rewrites both families to their high-resolution
 * equivalents BEFORE the URL is stored on the Song — so the crisp variant is
 * what gets rendered everywhere (player, queue, widgets, notification,
 * history) and what gets persisted (Room favorites, playback snapshot,
 * downloads index).
 */
object CloudArtworkHelper {

    /**
     * Returns the highest-resolution artwork URL NewPipe exposed for [item],
     * or null when the item has no thumbnails at all.
     */
    fun bestArtworkUrl(item: StreamInfoItem): String? {
        val thumbnails = runCatching { item.thumbnails }.getOrNull()
            ?: return null
        return bestArtworkUrl(thumbnails)
    }

    /**
     * Returns the highest-resolution URL of [thumbnails] (never empty-input
     * safe: returns null for an empty list), rewritten to the
     * highest-resolution equivalent when the URL is from a CDN whose
     * resolution we can upgrade deterministically (see [upgradeToHighRes]).
     */
    fun bestArtworkUrl(thumbnails: List<Image>): String? {
        if (thumbnails.isEmpty()) return null

        var bestUrl: String? = null
        var bestScore = -1L
        for (image in thumbnails) {
            val url = image.url ?: continue
            // Unknown width/height arrive as Image.WIDTH_UNKNOWN (-1) — treat
            // anything <= 0 as "unknown" and score it 0.
            val width = if (image.width > 0) image.width.toLong() else 0L
            val height = if (image.height > 0) image.height.toLong() else 0L
            val score = width * height

            // Strictly greater: on ties (including the all-unknown case, where
            // every score is 0) the FIRST entry wins — preserving the previous
            // behaviour for extractors that expose a single unsized thumbnail.
            if (score > bestScore) {
                bestScore = score
                bestUrl = url
            }
        }
        // All entries had a null URL — fall back to the first entry's URL.
        val chosen = bestUrl ?: thumbnails.firstOrNull()?.url ?: return null
        return upgradeToHighRes(chosen)
    }

    /**
     * FIX(youtube-art-quality): rewrites a provider artwork URL to its
     * highest-resolution equivalent when the serving CDN makes that possible
     * WITHOUT extra requests (pure URL transformation). URLs that don't match
     * a known upgradable pattern — SoundCloud `sndcdn.com` artwork, Deezer,
     * local file/content URIs, … — are returned unchanged.
     *
     * Two families are upgraded (both verified live against the real CDNs):
     *
     *  1. **Google image CDN** (`yt3.googleusercontent.com` / `*.ggpht.com`)
     *     — this is where YouTube Music song/album artwork lives. The image
     *     size is encoded in the trailing URL "options" token (`=w60-h60-l90-rj`,
     *     `=s120`, …) and the CDN serves ANY size the client asks for, up to
     *     the original asset resolution (a YT Music cover commonly originates
     *     at 1425x1425 — requesting 1080 is a genuine upscale-free render).
     *     The size token is rewritten to [HIGH_RES_EDGE_PX]; flags after the
     *     size (crop/rotate markers like `-l90-rj`) are preserved. Tokens that
     *     already request >= [HIGH_RES_EDGE_PX], and `=s0` ("original size"),
     *     are left untouched.
     *
     *  2. **YouTube video thumbnails** (`i.ytimg.com/vi/<id>/<name>.jpg`)
     *     — search and radio items only expose cropped 168..720px variants,
     *     but the deterministic `maxresdefault.jpg` (1280x720) exists for
     *     essentially every official music video. The URL is rewritten to
     *     `maxresdefault.jpg` (dropping the crop-query parameters). For the
     *     rare video without maxres artwork, the artwork HTTP client's
     *     [com.theveloper.pixeltune.data.network.YtimgArtworkFallbackInterceptor]
     *     falls back to `hq720.jpg` and then `hqdefault.jpg` (the guaranteed
     *     floor every YouTube video has) — so the worst case is exactly the
     *     quality the app served before this upgrade, never a missing image.
     */
    fun upgradeToHighRes(url: String): String {
        if (url.isEmpty()) return url

        // 1. Google image CDN — rewrite the trailing size token.
        if (GOOGLE_IMAGE_CDN_HOSTS.any { url.contains(it) }) {
            val optionStart = url.lastIndexOf('=')
            if (optionStart >= 0 && optionStart < url.length - 1) {
                val upgraded = upgradeGoogleImageSizeToken(url.substring(optionStart + 1))
                if (upgraded != null) {
                    return url.substring(0, optionStart + 1) + upgraded
                }
            }
            return url
        }

        // 2. YouTube video thumbnails — deterministic maxresdefault.jpg.
        val ytimgMatch = YTIMG_VIDEO_THUMB_REGEX.find(url)
        if (ytimgMatch != null) {
            val videoId = ytimgMatch.groupValues[1]
            if (videoId.isNotEmpty()) {
                return "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
            }
        }

        return url
    }

    /**
     * Rewrites a Google image CDN options token ("w60-h60-l90-rj", "s120", …)
     * so the leading size part requests [HIGH_RES_EDGE_PX], keeping any
     * trailing flags intact. Returns null when the token is absent, already
     * large enough, or means "original size" (`s0`) — i.e. when nothing
     * should change.
     */
    private fun upgradeGoogleImageSizeToken(options: String): String? {
        val match = GOOGLE_IMAGE_SIZE_TOKEN_REGEX.find(options) ?: return null
        val s = match.groupValues[2].toIntOrNull()
        val w = match.groupValues[3].toIntOrNull()
        val h = match.groupValues[4].toIntOrNull()
        val trailingOptions = options.substring(match.value.length)
        return when {
            // s0 == "original size" — already the maximum; never shrink it.
            s != null && s > 0 && s < HIGH_RES_EDGE_PX ->
                "s$HIGH_RES_EDGE_PX$trailingOptions"
            w != null && h != null && w > 0 && h > 0 &&
                minOf(w, h) < HIGH_RES_EDGE_PX ->
                "w$HIGH_RES_EDGE_PX-h$HIGH_RES_EDGE_PX$trailingOptions"
            else -> null
        }
    }

    /**
     * Pixel edge high-resolution artwork is upgraded to. 1080 covers the
     * full player's "Original" quality tier on ~400dpi screens while staying
     * below the source resolution of typical YT Music artwork (>= 1425px).
     */
    private const val HIGH_RES_EDGE_PX = 1080

    /**
     * Hosts of Google's image CDN family that serve YouTube Music artwork
     * (square song/album covers) and accept arbitrary `=wW-hH` / `=sN` sizes.
     */
    private val GOOGLE_IMAGE_CDN_HOSTS = listOf("googleusercontent.com", "ggpht.com")

    /**
     * `=w60-h60-l90-rj`, `=s120`, `=w120-h120`, … — a leading size token
     * followed by optional flags. Group 2: `s` value, groups 3/4: `w`/`h`.
     */
    private val GOOGLE_IMAGE_SIZE_TOKEN_REGEX = Regex("^(s(\\d+)|w(\\d+)-h(\\d+))")

    /**
     * YouTube video thumbnail URLs: `https://i.ytimg.com/vi/<11-char id>/<file>.jpg?sqp=…`.
     * YouTube video ids are exactly 11 chars ([A-Za-z0-9_-]).
     */
    private val YTIMG_VIDEO_THUMB_REGEX =
        Regex("https?://[^/]*ytimg\\.com/vi/([a-zA-Z0-9_-]{11})/[^/?#]+")
}
