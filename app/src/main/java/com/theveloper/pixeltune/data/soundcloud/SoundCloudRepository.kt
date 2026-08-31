package com.theveloper.pixeltune.data.soundcloud

import com.theveloper.pixeltune.data.model.CloudArtist
import com.theveloper.pixeltune.data.model.CloudPlaylist
import com.theveloper.pixeltune.data.model.CloudStreamProvider
import com.theveloper.pixeltune.data.model.CloudTracksPage
import com.theveloper.pixeltune.data.model.SearchResultItem
import com.theveloper.pixeltune.data.model.SearchFilterType
import com.theveloper.pixeltune.data.model.Song
import com.theveloper.pixeltune.data.preferences.StreamingQuality
import com.theveloper.pixeltune.data.stream.CloudArtworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import timber.log.Timber
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SoundCloudRepository @Inject constructor() {

    /**
     * Extracts the best available audio stream URL for a given SoundCloud track URL.
     */
    suspend fun getAudioStreamUrl(soundCloudUrl: String, quality: StreamingQuality = StreamingQuality.NORMAL): Result<String> = withContext(Dispatchers.IO) {
        try {
            Timber.d("Extracting SoundCloud streams for: $soundCloudUrl")

            // Get the stream extractor for SoundCloud
            val extractor = ServiceList.SoundCloud.getStreamExtractor(soundCloudUrl)

            // Fetch page and extract streams
            extractor.fetchPage()

            val audioStreams = extractor.audioStreams
            if (audioStreams.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("No audio streams found for URL $soundCloudUrl"))
            }

            // STRATEGY:
            // SoundCloud serves each track as multiple "transcodings" — typically a mix
            // of HLS (`.m3u8` playlists) and progressive (direct MP3). NewPipe surfaces
            // all of them as AudioStreams, with `deliveryMethod` distinguishing the two.
            //
            // We MUST pick only PROGRESSIVE_HTTP streams here: PixelTune's proxy hands
            // the chosen URL directly to ExoPlayer as a single byte stream, and
            // ExoPlayer's ProgressiveMediaPeriod tries to extract audio frames from
            // whatever bytes come back. If we hand it an HLS `.m3u8` playlist URL,
            // the proxy fetches the playlist text (HTTP 200, content-type audio/mpegurl)
            // and pipes it to ExoPlayer — which fails with
            // `UnrecognizedInputFormatException: None of the available extractors ...
            // could read the stream. sniff failures: [NoDeclaredBrand, NoDeclaredBrand]`,
            // leaving the player frozen at 00:00.
            //
            // The SoundCloud API response observed in production logcats shows that
            // every public track exposes at least one progressive MP3 transcoding
            // (preset `mp3_1_0`, mime_type `audio/mpeg`, protocol `progressive`), so
            // filtering to PROGRESSIVE_HTTP always yields a playable URL.
            val progressiveStreams = audioStreams.filter { stream ->
                stream.isUrl && stream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
            }

            if (progressiveStreams.isEmpty()) {
                Timber.w(
                    "No progressive audio streams for $soundCloudUrl. " +
                        "DeliveryMethods: ${audioStreams.map { it.deliveryMethod }}"
                )
                return@withContext Result.failure(
                    Exception("No progressive audio streams found for URL $soundCloudUrl")
                )
            }

            // Pick the best stream by bitrate based on quality
            val bestStream = when (quality) {
                StreamingQuality.HIGH_RES -> progressiveStreams.maxByOrNull { it.bitrate }
                StreamingQuality.DATA_SAVER -> progressiveStreams.minByOrNull { it.bitrate }
                StreamingQuality.NORMAL -> {
                    val sortedStreams = progressiveStreams.sortedBy { it.bitrate }
                    sortedStreams.minByOrNull { kotlin.math.abs(it.bitrate - 128) }
                        ?: sortedStreams.getOrNull(sortedStreams.size / 2)
                }
            }

            if (bestStream != null) {
                Result.success(bestStream.content)
            } else {
                Result.failure(Exception("No suitable audio stream format found for URL $soundCloudUrl"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Error extracting SoundCloud stream for $soundCloudUrl")
            Result.failure(e)
        }
    }

    /**
     * IMPROVE(endless-radio): fetches up to [limit] tracks related to
     * [currentSong] from SoundCloud itself (the "Related tracks" list on the
     * track page), so a SoundCloud queue can keep growing forever like a
     * radio instead of stalling when the last queued song plays.
     *
     * Mirrors [com.theveloper.pixeltune.data.youtube.YouTubeRepository
     * .getMultipleAutoplayRecommendations]:
     *  - the upstream track URL is taken from [Song.path] (fresh search
     *    results) or reconstructed from the restart-safe `soundcloud://`
     *    content URI (favorited songs / restored queue items);
     *  - items whose derived id is in [excludeIds] (already queued or
     *    recently played) are skipped;
     *  - returns [Song]s built exactly like search results, so they are
     *    immediately playable and persist correctly when liked.
     */
    suspend fun getRelatedSongs(
        currentSong: Song,
        excludeIds: Set<String>,
        proxyUrlProvider: (String) -> String,
        limit: Int = 5
    ): List<Song> = withContext(Dispatchers.IO) {
        if (limit <= 0) return@withContext emptyList()
        val safeLimit = limit.coerceAtMost(20)
        try {
            val trackUrl = currentSong.path.takeIf {
                it.startsWith("http", ignoreCase = true) &&
                    it.contains("soundcloud.com", ignoreCase = true)
            } ?: currentSong.contentUriString
                .takeIf { it.startsWith("soundcloud://", ignoreCase = true) }
                ?.substringAfter("soundcloud://")
                ?.let { encoded ->
                    // FIX(soundcloud-playback-restore): the persisted payload may
                    // be the URL-ENCODED token (single opaque segment — the
                    // documented scheme form) or the DECODED track URL that
                    // CloudUriUtils.normalizeCloudUriForStorage writes (it
                    // decodes via Uri.pathSegments). A decoded payload always
                    // contains the literal "://" of its inner https URL, an
                    // encoded token never does — decode only when needed so a
                    // restored seed is never double-decoded (which could mangle
                    // percent sequences) nor left encoded (which NewPipe cannot
                    // fetch).
                    if (encoded.contains("://")) encoded
                    else runCatching { java.net.URLDecoder.decode(encoded, "UTF-8") }.getOrNull()
                }
                ?: return@withContext emptyList()

            val extractor = ServiceList.SoundCloud.getStreamExtractor(trackUrl)
            extractor.fetchPage()
            val relatedItems = extractor.relatedItems?.items
                ?.filterIsInstance<StreamInfoItem>()
                ?: emptyList()

            val picked = ArrayList<Song>(safeLimit)
            for (item in relatedItems) {
                if (picked.size >= safeLimit) break
                val itemUrl = item.url ?: continue
                val encodedUrl = URLEncoder.encode(itemUrl, "UTF-8")
                val songId = encodedUrl.hashCode().toString()
                if (songId in excludeIds) continue

                val durationMs = if (item.duration > 0) item.duration * 1000L else 0L
                picked += Song(
                    id = songId,
                    title = item.name ?: "Unknown",
                    artist = item.uploaderName ?: "Unknown",
                    artistId = -1L,
                    artists = emptyList(),
                    album = "",
                    albumId = -1L,
                    albumArtist = null,
                    path = itemUrl,
                    contentUriString = proxyUrlProvider(encodedUrl),
                    // FIX(album-art-quality): pick the LARGEST artwork variant
                    // (t500x500) — NewPipe's list is ordered ascending, so the
                    // old firstOrNull() grabbed the 16x16 "mini" artwork and the
                    // album art rendered very low quality and blurry.
                    albumArtUriString = CloudArtworkHelper.bestArtworkUrl(item),
                    duration = durationMs,
                    genre = null,
                    lyrics = null,
                    isFavorite = false,
                    trackNumber = 0,
                    year = 0,
                    dateAdded = 0,
                    dateModified = 0,
                    mimeType = "audio/mpeg",
                    bitrate = 0,
                    sampleRate = 0,
                    telegramFileId = null,
                    telegramChatId = null,
                    neteaseId = null,
                    gdriveFileId = null,
                    youtubeId = null
                )
            }
            picked
        } catch (e: Exception) {
            Timber.e(e, "Error getting SoundCloud related songs")
            emptyList()
        }
    }

    suspend fun searchSoundCloud(query: String, filter: SearchFilterType = SearchFilterType.ALL, proxyUrlProvider: (String) -> String): List<SearchResultItem> = withContext(Dispatchers.IO) {
        try {
            val searchFilter = when (filter) {
                SearchFilterType.ALL -> ""
                SearchFilterType.SONGS -> "tracks"
                SearchFilterType.ALBUMS -> "playlists"
                SearchFilterType.ARTISTS -> "users"
                SearchFilterType.PLAYLISTS -> "playlists"
            }

            val extractor: SearchExtractor = if (searchFilter.isNotEmpty()) {
                ServiceList.SoundCloud.getSearchExtractor(query, listOf(searchFilter), "")
            } else {
                ServiceList.SoundCloud.getSearchExtractor(query)
            }

            extractor.fetchPage()

            val results = mutableListOf<SearchResultItem>()

            extractor.initialPage.items.forEach { item ->
                when (item) {
                    is StreamInfoItem -> {
                        if (filter == SearchFilterType.ALL || filter == SearchFilterType.SONGS) {
                            val trackUrl = item.url
                            val encodedUrl = URLEncoder.encode(trackUrl, "UTF-8")
                            val durationMs = if (item.duration > 0) item.duration * 1000L else 0L

                            val song = Song(
                                id = encodedUrl.hashCode().toString(),
                                title = item.name ?: "Unknown",
                                artist = item.uploaderName ?: "Unknown",
                                artistId = -1L,
                                artists = emptyList(),
                                album = "",
                                albumId = -1L,
                                albumArtist = null,
                                path = item.url,
                                contentUriString = proxyUrlProvider(encodedUrl),
                                // FIX(album-art-quality): pick the LARGEST
                                // artwork variant (t500x500) instead of the
                                // 16x16 "mini" one NewPipe lists first.
                                albumArtUriString = CloudArtworkHelper.bestArtworkUrl(item),
                                duration = durationMs,
                                genre = null,
                                lyrics = null,
                                isFavorite = false,
                                trackNumber = 0,
                                year = 0,
                                dateAdded = 0,
                                dateModified = 0,
                                mimeType = "audio/mpeg", // typical for soundcloud
                                bitrate = 0,
                                sampleRate = 0,
                                telegramFileId = null,
                                telegramChatId = null,
                                neteaseId = null,
                                gdriveFileId = null,
                                youtubeId = null
                            )
                            results.add(SearchResultItem.SongItem(song))
                        }
                    }
                    is PlaylistInfoItem -> {
                        if (filter == SearchFilterType.ALL || filter == SearchFilterType.PLAYLISTS || filter == SearchFilterType.ALBUMS) {
                            // FIX(online-filter-chips): keep the provider's own
                            // metadata (uploader, track count, artwork) on a
                            // dedicated cloud model instead of squeezing the
                            // item into the LOCAL Playlist shape (which always
                            // rendered "0 songs" with a placeholder cover and
                            // opened the local PlaylistDetail screen that can
                            // never resolve it). SoundCloud search surfaces both
                            // playlists and albums under the "playlists"
                            // content filter — they are marked playlists here
                            // and all play identically.
                            val playlistUrl = item.url ?: ""
                            results.add(
                                SearchResultItem.CloudPlaylistItem(
                                    CloudPlaylist(
                                        id = playlistUrl.hashCode().toString(),
                                        url = playlistUrl,
                                        name = item.name ?: "Unknown Playlist",
                                        uploaderName = runCatching { item.uploaderName }.getOrNull(),
                                        trackCount = runCatching { item.streamCount }.getOrDefault(-1L),
                                        artworkUrl = CloudArtworkHelper.bestArtworkUrl(
                                            runCatching { item.thumbnails }.getOrDefault(emptyList())
                                        ),
                                        isAlbum = false,
                                        provider = CloudStreamProvider.SOUNDCLOUD
                                    )
                                )
                            )
                        }
                    }
                    is ChannelInfoItem -> {
                        if (filter == SearchFilterType.ALL || filter == SearchFilterType.ARTISTS) {
                            // FIX(online-filter-chips): same treatment for
                            // artists — keep the avatar + follower count NewPipe
                            // extracted (the LOCAL Artist mapping dropped the
                            // avatar and mislabeled followers as "X Songs"), and
                            // keep the profile URL the detail screen extracts
                            // the artist's tracks from.
                            results.add(
                                SearchResultItem.CloudArtistItem(
                                    CloudArtist(
                                        id = (item.url ?: "").hashCode().toString(),
                                        url = item.url ?: "",
                                        name = item.name ?: "Unknown Artist",
                                        subscriberCount = runCatching { item.subscriberCount }.getOrDefault(-1L),
                                        artworkUrl = CloudArtworkHelper.bestArtworkUrl(
                                            runCatching { item.thumbnails }.getOrDefault(emptyList())
                                        ),
                                        isVerified = runCatching { item.isVerified }.getOrDefault(false),
                                        provider = CloudStreamProvider.SOUNDCLOUD
                                    )
                                )
                            )
                        }
                    }
                }
            }
            results
        } catch (e: Exception) {
            Timber.e(e, "Error searching SoundCloud for query: $query")
            emptyList()
        }
    }

    /**
     * FIX(online-filter-chips): extracts the FIRST page of playable tracks of
     * a SoundCloud playlist or album found by the online search
     * (`soundcloud.com/<user>/sets/<set>`).
     *
     * Every returned [Song] is built exactly like an online search result
     * (id = URL-encoded track URL hashcode, proxy URL over the encoded URL,
     * t500x500 artwork via CloudArtworkHelper), so it is immediately
     * playable and behaves like any other SoundCloud song in the app
     * (queue refill, favorites, downloads).
     */
    suspend fun getCloudPlaylistTracks(
        playlist: CloudPlaylist,
        proxyUrlProvider: (String) -> String
    ): Result<CloudTracksPage> = withContext(Dispatchers.IO) {
        try {
            val extractor = ServiceList.SoundCloud.getPlaylistExtractor(playlist.url)
            extractor.fetchPage()
            val page = extractor.initialPage
            val songs = streamItemsToSongs(
                items = page.items.filterIsInstance<StreamInfoItem>(),
                proxyUrlProvider = proxyUrlProvider,
                contextTitle = playlist.name
            )
            val uploader = runCatching { extractor.uploaderName }.getOrNull()
            val trackCount = runCatching { extractor.streamCount }.getOrDefault(-1L)
            Result.success(
                CloudTracksPage(
                    songs = songs,
                    refreshedTitle = runCatching { extractor.name }.getOrNull(),
                    refreshedTrackCount = trackCount.takeIf { it >= 0 },
                    refreshedSubtitle = uploader?.takeIf { it.isNotBlank() },
                    hasMore = page.nextPage != null,
                    continuation = page.nextPage
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "Error extracting SoundCloud playlist tracks for %s", playlist.url)
            Result.failure(e)
        }
    }

    /**
     * FIX(online-filter-chips): continues a [getCloudPlaylistTracks] listing
     * (the "Load more" action of the cloud playlist detail screen). The
     * [page] parameter is the previous result — its continuation token is the
     * opaque NewPipe `Page` this repository produced.
     */
    suspend fun getMoreCloudPlaylistTracks(
        playlist: CloudPlaylist,
        page: CloudTracksPage,
        proxyUrlProvider: (String) -> String
    ): Result<CloudTracksPage> = withContext(Dispatchers.IO) {
        val continuation = page.continuation as? org.schabi.newpipe.extractor.Page
            ?: return@withContext Result.failure(
                IllegalArgumentException("No continuation for SoundCloud playlist ${playlist.url}")
            )
        try {
            val extractor = ServiceList.SoundCloud.getPlaylistExtractor(playlist.url)
            extractor.fetchPage()
            val next = extractor.getPage(continuation)
            val songs = streamItemsToSongs(
                items = next.items.filterIsInstance<StreamInfoItem>(),
                proxyUrlProvider = proxyUrlProvider,
                contextTitle = playlist.name
            )
            Result.success(
                CloudTracksPage(
                    songs = songs,
                    hasMore = next.nextPage != null,
                    continuation = next.nextPage
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "Error paging SoundCloud playlist tracks for %s", playlist.url)
            Result.failure(e)
        }
    }

    /**
     * FIX(online-filter-chips): extracts the FIRST page of a SoundCloud
     * artist's tracks — the user's Tracks tab
     * (`soundcloud.com/<user>/tracks`), resolved from the profile URL the
     * search returned.
     */
    suspend fun getCloudArtistTracks(
        artist: CloudArtist,
        proxyUrlProvider: (String) -> String
    ): Result<CloudTracksPage> = withContext(Dispatchers.IO) {
        try {
            val channel = ServiceList.SoundCloud.getChannelExtractor(artist.url)
            channel.fetchPage()
            val tracksTab = channel.tabs.firstOrNull { tab ->
                tab.contentFilters.contains(ChannelTabs.TRACKS)
            } ?: return@withContext Result.failure(
                IllegalStateException("User ${artist.url} exposes no tracks tab")
            )
            val tabInfo = ChannelTabInfo.getInfo(ServiceList.SoundCloud, tracksTab)
            val songs = streamItemsToSongs(
                items = tabInfo.relatedItems.filterIsInstance<StreamInfoItem>(),
                proxyUrlProvider = proxyUrlProvider,
                contextTitle = artist.name
            )
            Result.success(
                CloudTracksPage(
                    songs = songs,
                    refreshedTitle = runCatching { channel.name }.getOrNull(),
                    refreshedSubtitle = formatFollowerCount(
                        runCatching { channel.subscriberCount }.getOrDefault(-1L)
                    ),
                    hasMore = tabInfo.nextPage != null,
                    continuation = tabInfo.nextPage
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "Error extracting SoundCloud artist tracks for %s", artist.url)
            Result.failure(e)
        }
    }

    /**
     * FIX(online-filter-chips): continues a [getCloudArtistTracks] listing
     * (the "Load more" action of the cloud artist detail screen).
     */
    suspend fun getMoreCloudArtistTracks(
        artist: CloudArtist,
        page: CloudTracksPage,
        proxyUrlProvider: (String) -> String
    ): Result<CloudTracksPage> = withContext(Dispatchers.IO) {
        val continuation = page.continuation as? org.schabi.newpipe.extractor.Page
            ?: return@withContext Result.failure(
                IllegalArgumentException("No continuation for SoundCloud artist ${artist.url}")
            )
        try {
            val channel = ServiceList.SoundCloud.getChannelExtractor(artist.url)
            channel.fetchPage()
            val tracksTab = channel.tabs.firstOrNull { tab ->
                tab.contentFilters.contains(ChannelTabs.TRACKS)
            } ?: return@withContext Result.failure(
                IllegalStateException("User ${artist.url} exposes no tracks tab")
            )
            val next = ChannelTabInfo.getMoreItems(ServiceList.SoundCloud, tracksTab, continuation)
            val songs = streamItemsToSongs(
                items = next.items.filterIsInstance<StreamInfoItem>(),
                proxyUrlProvider = proxyUrlProvider,
                contextTitle = artist.name
            )
            Result.success(
                CloudTracksPage(
                    songs = songs,
                    hasMore = next.nextPage != null,
                    continuation = next.nextPage
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "Error paging SoundCloud artist tracks for %s", artist.url)
            Result.failure(e)
        }
    }

    /**
     * FIX(online-filter-chips): "1.2M followers" style subtitle for the cloud
     * artist header. Returns null when the count is unknown.
     */
    private fun formatFollowerCount(count: Long): String? {
        if (count < 0) return null
        return when {
            count >= 1_000_000L -> {
                val v = count / 1_000_000L
                val frac = (count % 1_000_000L) / 100_000L
                if (frac > 0) "$v.${frac}M followers" else "$v M followers"
            }
            count >= 1_000L -> "${count / 1_000L}K followers"
            else -> "$count followers"
        }
    }

    /**
     * FIX(online-filter-chips): maps playlist/channel-tab [StreamInfoItem]s to
     * immediately-playable [Song]s — identical construction to the online
     * search results (id = encoded URL hashcode, proxy URL over the encoded
     * URL, t500x500 artwork), so the queue, favorites and downloads treat
     * them uniformly.
     *
     * Unplayable entries (missing URLs) are skipped instead of aborting the
     * whole listing — a playlist with one unavailable track still lists the
     * rest.
     */
    private fun streamItemsToSongs(
        items: List<StreamInfoItem>,
        proxyUrlProvider: (String) -> String,
        contextTitle: String
    ): List<Song> {
        val songs = ArrayList<Song>(items.size)
        for (item in items) {
            val trackUrl = item.url ?: continue
            val encodedUrl = URLEncoder.encode(trackUrl, "UTF-8")
            val durationMs = if (item.duration > 0) item.duration * 1000L else 0L
            songs += Song.emptySong().copy(
                id = encodedUrl.hashCode().toString(),
                title = item.name ?: "Unknown",
                artist = item.uploaderName ?: "Unknown",
                artistId = -1L,
                album = contextTitle,
                albumId = -1L,
                path = trackUrl,
                contentUriString = proxyUrlProvider(encodedUrl),
                albumArtUriString = CloudArtworkHelper.bestArtworkUrl(item),
                duration = durationMs,
                mimeType = "audio/mpeg"
            )
        }
        return songs
    }
}
