package com.saine.pixeltune.data.surf

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test

/**
 * SURF: the [SoundCloudChartsParser] walk, pinned to fixtures captured from
 * REAL api-v2.soundcloud.com responses (the "New & hot" chart with
 * kind=trending, and a track station's system-playlist with its lazy id
 * stubs) — trimmed to the fields the parser reads. Chart collection walking,
 * the fresh-upload picking and the station stub resolution are all covered.
 */
class SoundCloudChartsParserTest {

    // A fixed "now" (2026-10-24T00:00:00Z) so the freshness window is
    // deterministic: 2026-10-02 is ~21 days old (fresh), 2026-05-14 is ~162
    // days old (stale).
    private val nowMs = 1_792_800_000_000L

    // ------------------------------------------------------------------
    // Fixtures — trimmed from live charts / system-playlist responses.
    // ------------------------------------------------------------------

    private fun chartFixture(): JSONObject = JSONObject(
        """
        {
          "genre": "soundcloud:genres:all-music",
          "kind": "trending",
          "last_updated": "2026-10-23T10:04:09Z",
          "collection": [
            {
              "track": {
                "id": 2320209185,
                "kind": "track",
                "title": "GOOSE COAT",
                "created_at": "2026-05-14T02:26:46Z",
                "permalink_url": "https://soundcloud.com/user-10135919/surf7-goose-coat",
                "artwork_url": "https://i1.sndcdn.com/artworks-ybOpDoNK578b-0-large.jpg",
                "duration": 30000,
                "full_duration": 170403,
                "genre": "Hip Hop/Rap",
                "user": { "username": "Surf7", "id": 10135919, "kind": "user" }
              }
            },
            {
              "track": {
                "id": 2312226140,
                "kind": "track",
                "title": "Wasting Time",
                "created_at": "2026-09-30T18:20:00Z",
                "permalink_url": "https://soundcloud.com/eric404/wasting-time",
                "artwork_url": null,
                "full_duration": 260625,
                "genre": "Electronic",
                "user": { "username": "Eric404", "id": 2210, "kind": "user" }
              }
            },
            {
              "track": {
                "id": 2250427187,
                "kind": "track",
                "title": "What's Luv?",
                "created_at": "2026-10-02T09:11:00Z",
                "permalink_url": "https://soundcloud.com/iskey/whats-luv",
                "artwork_url": "https://i1.sndcdn.com/artworks-xyz-0-large.jpg",
                "full_duration": 233509,
                "user": { "username": "iskey", "kind": "user" }
              }
            },
            {
              "track": {
                "id": 999,
                "kind": "track",
                "title": "",
                "created_at": "2026-10-01T00:00:00Z",
                "permalink_url": "https://soundcloud.com/a/b",
                "user": { "username": "NoTitle" }
              }
            },
            {
              "track": {
                "id": 888,
                "kind": "track",
                "title": "No Permalink",
                "created_at": "2026-10-01T00:00:00Z",
                "user": { "username": "NoPermalink" }
              }
            }
          ]
        }
        """
    )

    private fun stationFixture(): JSONObject = JSONObject(
        """
        {
          "urn": "soundcloud:system-playlists:track-stations:2320209185",
          "kind": "system-playlist",
          "title": "GOOSE COAT",
          "description": "Based on Surf7 - GOOSE COAT",
          "permalink": "track-stations:2320209185",
          "tracks": [
            { "id": 2320209185, "kind": "track", "monetization_model": "NOT_APPLICABLE", "policy": "SNIP" },
            { "id": 1866544854, "kind": "track", "monetization_model": "NOT_APPLICABLE", "policy": "SNIP" },
            { "id": 1928455292, "kind": "track", "monetization_model": "NOT_APPLICABLE", "policy": "MONETIZABLE" },
            { "id": 1799874961, "kind": "track", "monetization_model": "NOT_APPLICABLE", "policy": "SNIP" }
          ]
        }
        """
    )

    // ------------------------------------------------------------------
    // Chart walking
    // ------------------------------------------------------------------

    @Test
    fun `chart tracks carry the fields the station cards need`() {
        val tracks = SoundCloudChartsParser.parseChartTracks(chartFixture())

        // The blank-title and no-permalink entries are skipped.
        assertThat(tracks.map { it.trackId }).containsExactly(
            2320209185L, 2312226140L, 2250427187L
        ).inOrder()
        val gooseCoat = tracks.first()
        assertThat(gooseCoat.title).isEqualTo("GOOSE COAT")
        assertThat(gooseCoat.artistName).isEqualTo("Surf7")
        assertThat(gooseCoat.artworkUrl).isEqualTo("https://i1.sndcdn.com/artworks-ybOpDoNK578b-0-large.jpg")
        assertThat(gooseCoat.trackUrl).isEqualTo("https://soundcloud.com/user-10135919/surf7-goose-coat")
        assertThat(gooseCoat.createdAtEpochMs).isEqualTo(1_778_725_606_000L)
    }

    @Test
    fun `chart tracks without artwork resolve to null`() {
        val tracks = SoundCloudChartsParser.parseChartTracks(chartFixture())

        // artwork_url: null -> null, not an empty string.
        assertThat(tracks.first { it.title == "Wasting Time" }.artworkUrl).isNull()
    }

    @Test
    fun `chart walking survives an empty or malformed collection`() {
        assertThat(SoundCloudChartsParser.parseChartTracks(JSONObject("""{"collection": []}"""))).isEmpty()
        assertThat(SoundCloudChartsParser.parseChartTracks(JSONObject("""{}"""))).isEmpty()
        assertThat(
            SoundCloudChartsParser.parseChartTracks(
                JSONObject("""{"collection": [{"noTrack": true}, {"track": {}}]}""")
            )
        ).isEmpty()
    }

    @Test
    fun `an unparsable upload date yields a zero timestamp`() {
        val chart = JSONObject(
            """
            { "collection": [ { "track": {
                "id": 1, "title": "T", "permalink_url": "https://soundcloud.com/a/t",
                "created_at": "not-a-date", "user": { "username": "U" }
            } } ] }
            """
        )

        val tracks = SoundCloudChartsParser.parseChartTracks(chart)

        assertThat(tracks.single().createdAtEpochMs).isEqualTo(0L)
    }

    // ------------------------------------------------------------------
    // Fresh-finds picking
    // ------------------------------------------------------------------

    @Test
    fun `fresh finds picks fresh uploads first, newest first`() {
        val tracks = SoundCloudChartsParser.parseChartTracks(chartFixture())

        val cards = SoundCloudChartsParser.pickFreshStationCards(
            tracks = tracks,
            maxCards = 8,
            nowEpochMs = nowMs,
            maxAgeDays = 90
        )

        // Fresh (within 90 days of 2026-10-24): What's Luv? (10-02), Wasting
        // Time (09-30). The 163-day-old GOOSE COAT only tops up once the
        // fresh list is exhausted.
        assertThat(cards.map { it.title }).containsExactly(
            "What's Luv?", "Wasting Time", "GOOSE COAT"
        ).inOrder()
    }

    @Test
    fun `fresh finds caps the card row`() {
        val tracks = SoundCloudChartsParser.parseChartTracks(chartFixture())

        val cards = SoundCloudChartsParser.pickFreshStationCards(
            tracks = tracks,
            maxCards = 2,
            nowEpochMs = nowMs,
            maxAgeDays = 90
        )

        assertThat(cards.map { it.title }).containsExactly("What's Luv?", "Wasting Time").inOrder()
    }

    @Test
    fun `fresh finds cards route on the derivable station browse id`() {
        val tracks = SoundCloudChartsParser.parseChartTracks(chartFixture())

        val cards = SoundCloudChartsParser.pickFreshStationCards(
            tracks = tracks,
            maxCards = 1,
            nowEpochMs = nowMs,
            maxAgeDays = 90
        )

        val card = cards.single()
        assertThat(card.stationBrowseId).isEqualTo("scstation:2250427187")
        assertThat(card.artistName).isEqualTo("iskey")
        assertThat(card.artworkUrl).isEqualTo("https://i1.sndcdn.com/artworks-xyz-0-large.jpg")
    }

    @Test
    fun `fresh finds handles no fresh uploads and empty input`() {
        val tracks = SoundCloudChartsParser.parseChartTracks(chartFixture())

        // A one-day window leaves only the zero-timestamp guard: every real
        // track is stale, so the newest stale one still tops the section up.
        val oneDayCards = SoundCloudChartsParser.pickFreshStationCards(
            tracks = tracks,
            maxCards = 3,
            nowEpochMs = nowMs,
            maxAgeDays = 1
        )
        assertThat(oneDayCards.map { it.title }).containsExactly(
            "What's Luv?", "Wasting Time", "GOOSE COAT"
        ).inOrder()

        assertThat(
            SoundCloudChartsParser.pickFreshStationCards(
                tracks = emptyList(),
                maxCards = 3,
                nowEpochMs = nowMs,
                maxAgeDays = 90
            )
        ).isEmpty()
    }

    // ------------------------------------------------------------------
    // Station stubs
    // ------------------------------------------------------------------

    @Test
    fun `station stubs resolve to the station's own id order`() {
        val ids = SoundCloudChartsParser.parseStationTrackIds(stationFixture())

        assertThat(ids).containsExactly(2320209185L, 1866544854L, 1928455292L, 1799874961L).inOrder()
    }

    @Test
    fun `station stub walking survives empty or malformed tracks`() {
        assertThat(SoundCloudChartsParser.parseStationTrackIds(JSONObject("""{"tracks": []}"""))).isEmpty()
        assertThat(SoundCloudChartsParser.parseStationTrackIds(JSONObject("""{}"""))).isEmpty()
        assertThat(
            SoundCloudChartsParser.parseStationTrackIds(
                JSONObject("""{"tracks": [{}, {"id": 7}, "not-an-object"]}""")
            )
        ).containsExactly(7L)
    }
}
