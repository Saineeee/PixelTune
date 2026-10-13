package com.saine.pixeltune.data.surf

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/**
 * SURF: pure parser for the YouTube Music discovery responses the Surf tab
 * fetches with the raw WEB_REMIX innertube client.
 *
 * The charts kiosk NewPipe exposes ("trending_music") resolves to
 * charts.youtube.com, which now answers third-party requests with HTTP 429
 * ("Resource has been exhausted") — the whole kiosk family is unusable from
 * the app. The replacement walks YouTube Music's own home feed instead
 * (music.youtube.com/youtubei/v1/browse with browseId=FEmusic_home, the
 * same WEB_REMIX POST the artists search already uses): its "Today's fresh
 * & popular" carousel shelf lists the trending charts playlists
 * (VL-prefixed radio-playlist ids), and browsing one of those playlists
 * returns its tracks with durations, artists and artwork.
 *
 * Shelf detection is STRUCTURAL, never title-based: YouTube Music localizes
 * shelf titles ("Today's fresh & popular" / "Heute frisch & beliebt" / …),
 * so the parser only looks at the item renderers — a shelf of
 * musicTwoRowItemRenderer cards navigating to VL browse ids is the trending
 * playlists shelf; musicResponsiveListItemRenderer rows carrying
 * playlistItemData.videoId are song shelves. Parsing lives in this pure
 * object (org.json over the exact response shape, no network, no Android
 * types) so the walk is unit-testable, mirroring how SurfSectionsBuilder
 * keeps the feed ranking pure.
 */
object YoutubeTrendingFeedParser {

    /**
     * SURF: one trending playlist card from the YT Music home feed — the
     * "Today's fresh & popular" shelf's entry. [browseId] keeps its VL
     * prefix; it only resolves through the raw WEB_REMIX browse endpoint
     * (NewPipe's playlist extractor rejects VL ids), which is why the
     * see-all list fetches through the repository's own browse call.
     */
    @Immutable
    data class TrendingPlaylist(
        val browseId: String,
        val title: String,
        /** The card subtitle — the playlist's headline artists. */
        val artists: String,
        val artworkUrl: String?
    )

    /**
     * SURF: one track of a trending playlist — every field the app's [Song]
     * mapping needs (title, artist, duration, video id, artwork).
     */
    @Immutable
    data class PlaylistTrack(
        val videoId: String,
        val title: String,
        val artist: String,
        val durationSeconds: Long,
        val artworkUrl: String?
    )

    /**
     * Walks a FEmusic_home browse response and returns its trending
     * playlists (the VL carousel shelf) plus the song-shelf entries
     * ("Quick picks") — the songs are used as a fallback trending source
     * when the shelf lists no playlist cards.
     */
    fun parseHomeFeed(homeResponse: JSONObject): Pair<List<TrendingPlaylist>, List<PlaylistTrack>> {
        val shelves = collectShelves(homeResponse.optJSONObject("contents"), "musicCarouselShelfRenderer")

        val playlists = ArrayList<TrendingPlaylist>(shelves.size)
        val songs = ArrayList<PlaylistTrack>()
        for (shelf in shelves) {
            val contents = shelf.optJSONArray("contents") ?: continue
            for (i in 0 until contents.length()) {
                val item = contents.optJSONObject(i) ?: continue
                item.optJSONObject("musicTwoRowItemRenderer")?.let { twoRow ->
                    trendingPlaylistFrom(twoRow)?.let(playlists::add)
                } ?: item.optJSONObject("musicResponsiveListItemRenderer")?.let { songItem ->
                    songFrom(songItem)?.let(songs::add)
                }
            }
        }
        return playlists to songs
    }

    /**
     * Walks a VL playlist browse response and returns its tracks — the
     * musicPlaylistShelfRenderer rows with title/artist flex columns, the
     * duration fixed column and playlistItemData.videoId.
     */
    fun parsePlaylistTracks(playlistResponse: JSONObject): List<PlaylistTrack> {
        val shelves = collectShelves(playlistResponse.optJSONObject("contents"), "musicPlaylistShelfRenderer")
        val tracks = ArrayList<PlaylistTrack>()
        for (shelf in shelves) {
            val contents = shelf.optJSONArray("contents") ?: continue
            for (i in 0 until contents.length()) {
                val item = contents.optJSONObject(i) ?: continue
                val row = item.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                songFrom(row)?.let(tracks::add)
            }
        }
        return tracks
    }

    /**
     * Parses a duration label into seconds — YouTube Music renders playlist
     * durations as "m:ss" (or "h:mm:ss" for long uploads). Anything malformed
     * yields 0 so the song still lists (and the player fills the real length
     * on play), exactly like NewPipe's -1 durations are treated elsewhere.
     */
    fun parseDurationSeconds(label: String?): Long {
        if (label.isNullOrEmpty()) return 0L
        val parts = label.split(":")
        if (parts.size !in 2..3) return 0L
        var seconds = 0L
        for (part in parts) {
            val value = part.toLongOrNull() ?: return 0L
            if (value < 0) return 0L
            seconds = seconds * 60 + value
        }
        return seconds
    }

    /**
     * Collects every renderer of [rendererKey] under [root] — the browse
     * responses nest the shelves behind tab/section spines whose exact
     * intermediate names vary, so a depth-first walk keyed on the renderer
     * name is the stable way to find them.
     */
    private fun collectShelves(root: JSONObject?, rendererKey: String): List<JSONObject> {
        val found = ArrayList<JSONObject>()
        walkFor(root, rendererKey, found)
        return found
    }

    private fun walkFor(node: Any?, rendererKey: String, found: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject(rendererKey)?.let(found::add)
                for (key in node.keys()) {
                    walkFor(node.opt(key), rendererKey, found)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    walkFor(node.opt(i), rendererKey, found)
                }
            }
        }
    }

    /** The shared row mapping of both song sources (quick picks + playlist tracks). */
    private fun songFrom(item: JSONObject): PlaylistTrack? {
        val videoId = item.optJSONObject("playlistItemData")?.optString("videoId")
        if (videoId.isNullOrEmpty()) return null

        val flexColumns = item.optJSONArray("flexColumns")
        val title = flexColumnText(flexColumns, 0)
        if (title.isBlank()) return null

        val fixedColumns = item.optJSONArray("fixedColumns")
        val durationLabel = fixedColumns?.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")
            ?.optJSONObject("text")
            ?.let(::runsText)

        return PlaylistTrack(
            videoId = videoId,
            title = title,
            artist = flexColumnText(flexColumns, 1),
            durationSeconds = parseDurationSeconds(durationLabel),
            artworkUrl = bestThumbnailUrl(
                item.optJSONObject("thumbnail")
                    ?.optJSONObject("musicThumbnailRenderer")
                    ?.optJSONObject("thumbnail")
                    ?.optJSONArray("thumbnails")
            )
        )
    }

    /** A trending playlist card — only VL browse ids resolve to charts playlists. */
    private fun trendingPlaylistFrom(twoRow: JSONObject): TrendingPlaylist? {
        val browseId = twoRow.optJSONObject("navigationEndpoint")
            ?.optJSONObject("browseEndpoint")
            ?.optString("browseId")
        if (browseId.isNullOrEmpty() || !browseId.startsWith(VL_PLAYLIST_PREFIX)) return null

        val title = runsText(twoRow.optJSONObject("title"))
        if (title.isBlank()) return null

        return TrendingPlaylist(
            browseId = browseId,
            title = title,
            artists = runsText(twoRow.optJSONObject("subtitle")),
            artworkUrl = bestThumbnailUrl(
                twoRow.optJSONObject("thumbnailRenderer")
                    ?.optJSONObject("musicThumbnailRenderer")
                    ?.optJSONObject("thumbnail")
                    ?.optJSONArray("thumbnails")
            )
        )
    }

    /** flexColumns[i]'s text runs joined — the title (0) and artist (1) columns. */
    private fun flexColumnText(flexColumns: JSONArray?, index: Int): String {
        val column = flexColumns?.optJSONObject(index)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?: return ""
        return runsText(column.optJSONObject("text"))
    }

    private fun runsText(text: JSONObject?): String {
        text ?: return ""
        val runs = text.optJSONArray("runs") ?: return ""
        val builder = StringBuilder()
        for (i in 0 until runs.length()) {
            builder.append(runs.optJSONObject(i)?.optString("text").orEmpty())
        }
        return builder.toString().trim()
    }

    /** The largest thumbnail (last in the array) — the artwork upgrade path picks hi-res. */
    private fun bestThumbnailUrl(thumbnails: JSONArray?): String? {
        if (thumbnails == null || thumbnails.length() == 0) return null
        return thumbnails.optJSONObject(thumbnails.length() - 1)?.optString("url")?.takeIf { it.isNotBlank() }
    }

    private const val VL_PLAYLIST_PREFIX = "VL"
}
