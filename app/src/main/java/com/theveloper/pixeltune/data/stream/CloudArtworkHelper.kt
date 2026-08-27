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
     * safe: returns null for an empty list).
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
        return bestUrl ?: thumbnails.firstOrNull()?.url
    }
}
