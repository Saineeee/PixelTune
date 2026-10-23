package com.saine.pixeltune.data.surf

import androidx.compose.runtime.Immutable
import java.time.Instant
import java.time.format.DateTimeParseException
import org.json.JSONArray
import org.json.JSONObject

/**
 * SURF: pure parser for the SoundCloud discovery responses the Surf tab
 * fetches with the raw api-v2 HTTP client.
 *
 * SoundCloud's public charts surface has shrunk to exactly ONE list —
 * `api-v2.soundcloud.com/charts?genre=soundcloud:genres:all-music&kind=trending`
 * (the "New & hot" chart; kind=top, per-genre and per-region variants all
 * answer 404 today, and the old charts.scloudhosting.net host no longer
 * resolves). The "Fresh finds" section for SoundCloud is therefore built
 * from that chart's own freshness signal: every chart entry carries its
 * upload date (`created_at`), so the freshly-uploaded tracks that are
 * already trending become the finds, each card opening the track's own
 * STATION — the system playlist `soundcloud:system-playlists:track-stations:
 * <trackId>` the SoundCloud app itself plays as "<song> radio", a ~50-track
 * related mix resolved through `/system-playlists/<urn>` + `/tracks?ids=…`.
 *
 * Parsing lives in this pure object (org.json over the exact response
 * shapes, no network, no Android types) so the walk is unit-testable,
 * mirroring how [YoutubeTrendingFeedParser] keeps the YouTube feed pure.
 */
object SoundCloudChartsParser {

    /**
     * SURF: one entry of the "New & hot" chart, trimmed to the fields the
     * Fresh-finds picker and the station card need. [trackUrl] is the
     * permalink the playback proxy URL is built from (the same URL family
     * NewPipe's chart kiosk items carry).
     */
    @Immutable
    data class ChartTrack(
        val trackId: Long,
        val title: String,
        val artistName: String,
        val artworkUrl: String?,
        /** Upload time; 0 when the field is absent or unparsable. */
        val createdAtEpochMs: Long,
        val trackUrl: String
    )

    /**
     * SURF: one "Fresh finds" card — the station of a fresh chart track.
     * [stationBrowseId] is the value the see-all list routes on (and the
     * [com.saine.pixeltune.data.surf.SurfFeedKeys.playlist] cache key is
     * derived from), identical in shape to the YouTube cards' VL browse ids.
     */
    @Immutable
    data class StationCard(
        val trackId: Long,
        val title: String,
        val artistName: String,
        val artworkUrl: String?
    ) {
        val stationBrowseId: String get() = "$STATION_BROWSE_ID_PREFIX$trackId"
    }

    /**
     * Walks the charts response's `collection` — every entry's `track`
     * object with its id, title, uploader name, artwork, upload date and
     * permalink. Entries without a usable title or permalink are skipped
     * (the app cannot play or name them).
     */
    fun parseChartTracks(chartResponse: JSONObject): List<ChartTrack> {
        val collection: JSONArray = chartResponse.optJSONArray("collection") ?: return emptyList()
        val tracks = ArrayList<ChartTrack>(collection.length())
        for (index in 0 until collection.length()) {
            val track = collection.optJSONObject(index)?.optJSONObject("track") ?: continue
            val title = track.optString("title").trim()
            val trackUrl = track.optString("permalink_url").trim()
            if (title.isEmpty() || trackUrl.isEmpty()) continue
            tracks += ChartTrack(
                trackId = track.optLong("id"),
                title = title,
                artistName = track.optJSONObject("user")
                    ?.optString("username")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: "Unknown",
                artworkUrl = track.optString("artwork_url").takeIf { it.isNotEmpty() },
                createdAtEpochMs = parseEpochMs(track.optString("created_at")),
                trackUrl = trackUrl
            )
        }
        return tracks
    }

    /**
     * Picks the Fresh-finds cards: chart tracks uploaded within
     * [maxAgeDays] of [nowEpochMs], newest uploads first, capped at
     * [maxCards]. When the chart carries fewer fresh uploads than that,
     * the newest of the remaining tracks top the section up — a sparse
     * chart still yields a full row instead of a half-empty one.
     */
    fun pickFreshStationCards(
        tracks: List<ChartTrack>,
        maxCards: Int,
        nowEpochMs: Long,
        maxAgeDays: Int
    ): List<StationCard> {
        if (maxCards <= 0 || tracks.isEmpty()) return emptyList()
        val windowMs = maxAgeDays * MILLIS_PER_DAY
        val newestFirst = tracks.sortedByDescending { it.createdAtEpochMs }
        val fresh = newestFirst.filter { track ->
            track.createdAtEpochMs > 0L && nowEpochMs - track.createdAtEpochMs <= windowMs
        }
        val freshIds = fresh.mapTo(HashSet(fresh.size)) { it.trackId }
        return (fresh + newestFirst.filterNot { it.trackId in freshIds })
            .take(maxCards)
            .map { track ->
                StationCard(
                    trackId = track.trackId,
                    title = track.title,
                    artistName = track.artistName,
                    artworkUrl = track.artworkUrl
                )
            }
    }

    /**
     * Walks a station (system-playlist) response — its `tracks` array is a
     * list of lazy id stubs (`{"id": …, "kind": "track"}`) the caller
     * resolves in bulk through `/tracks?ids=…`. Returns the ids in the
     * station's own order.
     */
    fun parseStationTrackIds(stationResponse: JSONObject): List<Long> {
        val stubs: JSONArray = stationResponse.optJSONArray("tracks") ?: return emptyList()
        val ids = ArrayList<Long>(stubs.length())
        for (index in 0 until stubs.length()) {
            val stub = stubs.optJSONObject(index) ?: continue
            val id = stub.optLong("id")
            if (id > 0L) ids += id
        }
        return ids
    }

    /** ISO-8601 "2026-05-14T02:26:46Z" -> epoch ms; 0 when unparsable. */
    private fun parseEpochMs(timestamp: String): Long {
        if (timestamp.isEmpty()) return 0L
        return try {
            Instant.parse(timestamp).toEpochMilli()
        } catch (e: DateTimeParseException) {
            0L
        }
    }

    /** Route/cache prefix of a Fresh-finds station page. */
    const val STATION_BROWSE_ID_PREFIX = "scstation:"

    private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
}
