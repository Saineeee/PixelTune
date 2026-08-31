package com.theveloper.pixeltune.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * FIX(online-filter-chips): one page of playable tracks extracted from a
 * [CloudPlaylist] or [CloudArtist] by the cloud detail screen.
 *
 * Returned by the repositories' catalog functions and consumed by
 * [com.theveloper.pixeltune.presentation.viewmodel.CloudCatalogViewModel]:
 *  - [songs] are fully-built, immediately-playable [Song]s (their
 *    contentUriString points at the current session's stream proxy, exactly
 *    like online search results);
 *  - [refreshedTitle] / [refreshedTrackCount] carry the authoritative
 *    name / count the extractor reported (search-page metadata can lag);
 *  - [continuation] is an OPAQUE pagination token (the NewPipe `Page`
 *    object). The UI never inspects it — it just hands the whole page back
 *    to the SAME repository function via its "load more" overload.
 */
class CloudTracksPage(
    val songs: List<Song>,
    val refreshedTitle: String? = null,
    val refreshedTrackCount: Long? = null,
    val refreshedSubtitle: String? = null,
    val hasMore: Boolean = false,
    val continuation: Any? = null
)

/**
 * FIX(online-filter-chips): the streaming provider a cloud catalog entry
 * (playlist / artist) was found on.
 *
 * Drives which repository extracts the entry's tracks and which stream
 * proxy builds playable URLs for them — see
 * [com.theveloper.pixeltune.presentation.viewmodel.CloudCatalogViewModel].
 */
@Immutable
enum class CloudStreamProvider {
    YOUTUBE,
    SOUNDCLOUD
}

/**
 * FIX(online-filter-chips): a playlist or album found on an ONLINE search
 * (YouTube Music / SoundCloud).
 *
 * The online search filter chips previously squeezed these results into the
 * LOCAL-library [Playlist] model, which cannot carry artwork, uploader or
 * track count — so every row rendered a placeholder cover with "0 songs",
 * and tapping it opened the LOCAL PlaylistDetail screen (which can never
 * find a cloud playlist). [CloudPlaylist] keeps the metadata NewPipe already
 * extracted (title, uploader, stream count, high-res artwork URL) plus the
 * canonical [url] the detail screen re-extracts the tracks from.
 */
@Immutable
@Serializable
data class CloudPlaylist(
    /** Stable identity for list keys / dedupe (URL hashcode string). */
    val id: String,
    /** Canonical extractor-ready playlist URL (never encoded). */
    val url: String,
    val name: String,
    val uploaderName: String? = null,
    /** Track count as reported by the provider; -1 when unknown. */
    val trackCount: Long = -1L,
    /** Highest-resolution artwork URL (upgraded by CloudArtworkHelper). */
    val artworkUrl: String? = null,
    /** True when the entry came from the albums index (YT Music albums). */
    val isAlbum: Boolean = false,
    val provider: CloudStreamProvider
)

/**
 * FIX(online-filter-chips): an artist / channel found on an ONLINE search
 * (YouTube Music / SoundCloud users).
 *
 * Same rationale as [CloudPlaylist]: the previous mapping into the LOCAL
 * [Artist] model dropped the channel avatar NewPipe extracted and showed
 * the subscriber count under an "X Songs" label, and taps opened the LOCAL
 * ArtistDetail screen with a hashcode id that does not exist in MediaStore.
 */
@Immutable
@Serializable
data class CloudArtist(
    /** Stable identity for list keys / dedupe (URL hashcode string). */
    val id: String,
    /** Canonical extractor-ready channel URL (never encoded). */
    val url: String,
    val name: String,
    /** Follower/subscriber count as reported by the provider; -1 unknown. */
    val subscriberCount: Long = -1L,
    /** Highest-resolution avatar URL (upgraded by CloudArtworkHelper). */
    val artworkUrl: String? = null,
    val isVerified: Boolean = false,
    val provider: CloudStreamProvider
)
