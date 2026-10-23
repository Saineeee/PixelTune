package com.saine.pixeltune.data.surf

import androidx.compose.runtime.Immutable

/**
 * SURF: one "Fresh finds" card, provider-neutral — YouTube's trending
 * playlist cards ("Today's fresh & popular") and SoundCloud's fresh station
 * cards ("<song> radio" seeded from freshly-uploaded chart tracks) share
 * the carousel's shape: a browse id the see-all list routes on (and the
 * feed cache keys on), a title, a subtitle and an artwork URL. The feed
 * therefore composes ONE section regardless of the streaming provider —
 * each provider just fills it with what its own discovery surface serves.
 */
@Immutable
data class SurfExploreCard(
    /** The provider-scoped id the see-all list opens (VL id / scstation id). */
    val browseId: String,
    val title: String,
    /** The card subtitle — the playlist's headline artists / the seed artist. */
    val subtitle: String,
    val artworkUrl: String?
)

/** YouTube trending playlist card -> the neutral card shape. */
fun YoutubeTrendingFeedParser.TrendingPlaylist.toExploreCard(): SurfExploreCard =
    SurfExploreCard(
        browseId = browseId,
        title = title,
        subtitle = artists,
        artworkUrl = artworkUrl
    )

/** SoundCloud fresh station card -> the neutral card shape. */
fun SoundCloudChartsParser.StationCard.toExploreCard(): SurfExploreCard =
    SurfExploreCard(
        browseId = stationBrowseId,
        title = title,
        subtitle = artistName,
        artworkUrl = artworkUrl
    )
