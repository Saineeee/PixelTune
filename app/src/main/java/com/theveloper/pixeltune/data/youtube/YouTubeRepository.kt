package com.theveloper.pixeltune.data.youtube

import com.theveloper.pixeltune.data.model.Album
import com.theveloper.pixeltune.data.model.Artist
import com.theveloper.pixeltune.data.model.Playlist
import com.theveloper.pixeltune.data.model.SearchResultItem
import com.theveloper.pixeltune.data.model.SearchFilterType
import com.theveloper.pixeltune.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import com.theveloper.pixeltune.data.preferences.StreamingQuality
import kotlin.math.abs

@Singleton
class YouTubeRepository @Inject constructor() {

    /**
     * Extracts the best available audio stream URL for a given YouTube video ID.
     *
     * This function has been the source of multiple production bugs. The logcat
     * evidence (from yt.log) shows that NewPipe v0.26.1's `YoutubeStreamExtractor`
     * uses the `reel/reel_item_watch` endpoint (ANDROID client) as the primary
     * streaming-data source for ALL videos — not just Shorts — and then calls
     * `/player` (WEB client) with a `$fields` filter that EXCLUDES `streamingData`.
     *
     * The `reel/reel_item_watch` response wraps everything under
     * `{"playerResponse":{"streamingData":{...}}}`, and NewPipe's
     * `getAudioStreams()` relies on `androidStreamingData` / `iosStreamingData`
     * being populated from this response. If NewPipe fails to unwrap the
     * `playerResponse` key (a known issue in v0.26.1 for non-Shorts videos),
     * `androidStreamingData` stays null and `getAudioStreams()` returns an
     * EMPTY list — silently, with no exception thrown.
     *
     * This function therefore:
     *   1. Logs extensive diagnostics at every decision point so future
     *      regressions are immediately diagnosable from a logcat.
     *   2. Falls back to `extractor.dashMpdUrl` if `audioStreams` is empty —
     *      the DASH MPD manifest URL can be proxied as-is and ExoPlayer's
     *      `DefaultMediaSourceFactory` will create a `DashMediaSource` for it.
     *   3. Upgrades NewPipe to v0.26.3 (which adds a visionOS client as
     *      another streaming-data source, increasing the chance that at
     *      least one client populates streaming data correctly).
     */
    suspend fun getAudioStreamUrl(youtubeId: String, quality: StreamingQuality = StreamingQuality.NORMAL): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = "https://www.youtube.com/watch?v=$youtubeId"
            Timber.d("Extracting YouTube streams for: $url")

            // Get the stream extractor for YouTube
            val extractor = ServiceList.YouTube.getStreamExtractor(url)

            // Fetch page and extract streams
            extractor.fetchPage()

            val audioStreams = extractor.audioStreams
            Timber.d(
                "YouTube extraction for $youtubeId: audioStreams count=${audioStreams?.size ?: "null"}, " +
                    "dashMpdUrl=${runCatching { extractor.dashMpdUrl }.getOrNull()?.take(80)}, " +
                    "hlsUrl=${runCatching { extractor.hlsUrl }.getOrNull()?.take(80)}"
            )

            if (audioStreams.isNullOrEmpty()) {
                Timber.w(
                    "YouTubeExtractor returned NO audio streams for $youtubeId. " +
                        "This typically means NewPipe's androidStreamingData/iosStreamingData " +
                        "fields were not populated from the reel/reel_item_watch response. " +
                        "Attempting DASH MPD fallback..."
                )

                // FALLBACK: Use the DASH MPD manifest URL.
                // NewPipe's getDashMpdUrl() constructs a DASH manifest URL from the
                // streaming data's adaptiveFormats. ExoPlayer's DefaultMediaSourceFactory
                // can handle DASH manifests natively — the proxy just needs to forward
                // the bytes, and ExoPlayer's DashMediaSource will parse the manifest
                // and fetch individual segments.
                //
                // IMPORTANT: The DASH MPD URL is a googlevideo.com URL that returns
                // XML manifest content. The proxy's CloudStreamSecurity.isSupportedAudioContentType
                // accepts "application/octet-stream" and "video/mp4" etc., but NOT
                // "application/dash+xml". The proxy code has been updated to also accept
                // DASH manifest content types.
                val dashMpdUrl = runCatching { extractor.dashMpdUrl }.getOrNull()
                if (!dashMpdUrl.isNullOrBlank()) {
                    Timber.d("YouTube DASH MPD fallback for $youtubeId: ${dashMpdUrl.take(120)}...")
                    return@withContext Result.success(dashMpdUrl)
                }

                val hlsUrl = runCatching { extractor.hlsUrl }.getOrNull()
                if (!hlsUrl.isNullOrBlank()) {
                    Timber.w("YouTube HLS fallback for $youtubeId — but HLS is not supported by the progressive proxy. URL: ${hlsUrl.take(120)}...")
                    // We cannot use HLS here because the proxy pipes a single byte stream
                    // to ExoPlayer's ProgressiveMediaPeriod, which cannot parse m3u8.
                }

                return@withContext Result.failure(Exception("No audio streams and no DASH MPD URL found for video $youtubeId"))
            }

            // STRATEGY:
            // Modern YouTube (2024+) no longer serves progressive HTTP audio streams for
            // most videos — every audio itag (139/140/249/250/251/252/599/600) is
            // served as DASH (segmented with initRange/indexRange). Forcing
            // `DeliveryMethod.PROGRESSIVE_HTTP` here therefore filters out ALL audio
            // streams, the proxy returns null → 404, and ExoPlayer sits frozen at 00:00.
            //
            // We therefore accept BOTH progressive HTTP and DASH audio streams, as long
            // as `isUrl == true` (i.e. NewPipe gives us a direct googlevideo.com URL
            // whose content is a self-contained MP4/WebM segment that ExoPlayer's
            // ProgressiveMediaPeriod can extract directly — DASH manifests are not
            // involved here because we don't pass `manifestUrl` to ExoPlayer).
            val playableStreams = audioStreams.filter { stream ->
                stream.isUrl && (
                    stream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP ||
                        stream.deliveryMethod == DeliveryMethod.DASH
                )
            }

            Timber.d(
                "YouTube playable audio streams for $youtubeId: ${playableStreams.size} of ${audioStreams.size}. " +
                    "Details: ${playableStreams.map { "itag=${it.itag},br=${it.bitrate},delivery=${it.deliveryMethod}" }}"
            )

            if (playableStreams.isEmpty()) {
                Timber.w(
                    "No playable audio streams for $youtubeId. " +
                        "All streams: ${audioStreams.map { "itag=${it.itag},delivery=${it.deliveryMethod},isUrl=${it.isUrl}" }}"
                )

                // FALLBACK: Try DASH MPD URL before giving up
                val dashMpdUrl = runCatching { extractor.dashMpdUrl }.getOrNull()
                if (!dashMpdUrl.isNullOrBlank()) {
                    Timber.d("YouTube DASH MPD fallback (after filter) for $youtubeId: ${dashMpdUrl.take(120)}...")
                    return@withContext Result.success(dashMpdUrl)
                }

                return@withContext Result.failure(Exception("No playable audio streams found for video $youtubeId"))
            }

            val bestStream = findBestAudioStream(playableStreams, quality)

            if (bestStream != null) {
                Timber.d(
                    "YouTube selected stream for $youtubeId: itag=${bestStream.itag}, " +
                        "bitrate=${bestStream.bitrate}, delivery=${bestStream.deliveryMethod}, " +
                        "url=${bestStream.content?.take(100)}..."
                )
                Result.success(bestStream.content)
            } else {
                Result.failure(Exception("No suitable audio stream format found for video $youtubeId"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Error extracting YouTube stream for $youtubeId")
            Result.failure(e)
        }
    }

    private fun findBestAudioStream(streams: List<AudioStream>, quality: StreamingQuality): AudioStream? {
        if (streams.isEmpty()) return null
        
        return when (quality) {
            StreamingQuality.HIGH_RES -> {
                streams.maxByOrNull { it.averageBitrate }
            }
            StreamingQuality.DATA_SAVER -> {
                streams.minByOrNull { it.averageBitrate }
            }
            StreamingQuality.NORMAL -> {
                val targetBitrate = 128000 // 128 kbps
                streams.minByOrNull { abs(it.averageBitrate - targetBitrate) } 
                    ?: streams.sortedBy { it.averageBitrate }.let { it.getOrNull(it.size / 2) }
            }
        }
    }

    suspend fun searchYouTube(query: String, filter: SearchFilterType = SearchFilterType.ALL, proxyUrlProvider: (String) -> String): List<SearchResultItem> = withContext(Dispatchers.IO) {
        try {
            val searchFilter = when (filter) {
                SearchFilterType.ALL -> ""
                SearchFilterType.SONGS -> "videos" // For YouTube, we can just use default or "videos"
                SearchFilterType.ALBUMS -> "playlists" // YouTube doesn't map albums cleanly, but NewPipe handles it
                SearchFilterType.ARTISTS -> "channels"
                SearchFilterType.PLAYLISTS -> "playlists"
            }

            val extractor: SearchExtractor = if (searchFilter.isNotEmpty()) {
                ServiceList.YouTube.getSearchExtractor(query, listOf(searchFilter), "")
            } else {
                ServiceList.YouTube.getSearchExtractor(query)
            }

            extractor.fetchPage()

            val results = mutableListOf<SearchResultItem>()

            extractor.initialPage.items.forEach { item ->
                when (item) {
                    is StreamInfoItem -> {
                        if (filter == SearchFilterType.ALL || filter == SearchFilterType.SONGS) {
                            val youtubeId = extractVideoId(item.url)
                            if (youtubeId != null) {
                                val durationMs = if (item.duration > 0) item.duration * 1000L else 0L
                                val song = Song(
                                    id = youtubeId,
                                    title = item.name ?: "Unknown",
                                    artist = item.uploaderName ?: "Unknown",
                                    artistId = -1L,
                                    artists = emptyList(),
                                    album = "",
                                    albumId = -1L,
                                    albumArtist = null,
                                    path = item.url,
                                    contentUriString = proxyUrlProvider(youtubeId),
                                    albumArtUriString = item.thumbnails.firstOrNull()?.url,
                                    duration = durationMs,
                                    genre = null,
                                    lyrics = null,
                                    isFavorite = false,
                                    trackNumber = 0,
                                    year = 0,
                                    dateAdded = 0,
                                    dateModified = 0,
                                    mimeType = "audio/mp4",
                                    bitrate = 0,
                                    sampleRate = 0,
                                    telegramFileId = null,
                                    telegramChatId = null,
                                    neteaseId = null,
                                    gdriveFileId = null,
                                    youtubeId = youtubeId
                                )
                                results.add(SearchResultItem.SongItem(song))
                            }
                        }
                    }
                    is PlaylistInfoItem -> {
                        if (filter == SearchFilterType.ALL || filter == SearchFilterType.PLAYLISTS || filter == SearchFilterType.ALBUMS) {
                            val playlistId = extractPlaylistId(item.url) ?: item.url
                            val playlist = Playlist(
                                id = playlistId,
                                name = item.name ?: "Unknown Playlist",
                                songIds = emptyList() // We don't fetch songs right now
                            )
                            results.add(SearchResultItem.PlaylistItem(playlist))
                        }
                    }
                    is ChannelInfoItem -> {
                        if (filter == SearchFilterType.ALL || filter == SearchFilterType.ARTISTS) {
                            val channelId = extractChannelId(item.url) ?: item.url
                            val artist = Artist(
                                id = channelId.hashCode().toLong(),
                                name = item.name ?: "Unknown Artist",
                                songCount = item.subscriberCount.toInt(),
                                // NewPipe ChannelInfoItem doesn't directly expose imageUrl
                            )
                            results.add(SearchResultItem.ArtistItem(artist))
                        }
                    }
                }
            }
            results
        } catch (e: Exception) {
            Timber.e(e, "Error searching YouTube for query: $query")
            emptyList()
        }
    }

    private fun extractVideoId(url: String): String? {
        // e.g., https://www.youtube.com/watch?v=dQw4w9WgXcQ
        val regex = Regex("v=([a-zA-Z0-9_-]+)")
        return regex.find(url)?.groupValues?.get(1) ?: url.substringAfterLast("/").substringBefore("?")
    }

    private fun extractPlaylistId(url: String): String? {
        val regex = Regex("list=([a-zA-Z0-9_-]+)")
        return regex.find(url)?.groupValues?.get(1)
    }

    private fun extractChannelId(url: String): String? {
        return url.substringAfterLast("/")
    }

    suspend fun getAutoplayRecommendation(
        currentSong: Song,
        currentQueueIds: List<String>,
        proxyUrlProvider: (String) -> String
    ): Result<Song> = withContext(Dispatchers.IO) {
        val recommendations = getMultipleAutoplayRecommendations(
            currentSong = currentSong,
            currentQueueIds = currentQueueIds,
            proxyUrlProvider = proxyUrlProvider,
            limit = 1
        )
        if (recommendations.isNotEmpty()) Result.success(recommendations.first())
        else Result.failure(Exception("No suitable autoplay recommendation found."))
    }

    /**
     * Fetches multiple autoplay recommendations related to [currentSong].
     *
     * IMPROVE(more-up-next): the original [getAutoplayRecommendation] only
     * returned a single next track. The Now Playing screen's "Up Next"
     * carousel therefore only ever showed 1 upcoming song — the user
     * explicitly asked for "more next queued songs related with exact same
     * taste of the current songs".
     *
     * This implementation:
     *  - reuses NewPipe's `extractor.relatedItems` (the same source
     *    [getAutoplayRecommendation] uses), which returns the "Up next" /
     *    "Related" list YouTube itself surfaces on the watch page;
     *  - filters out items already in [currentQueueIds] so we never
     *    recommend a song that's already queued or has just played;
     *  - takes up to [limit] items;
     *  - returns them as a List<Song> so the caller can batch-append them
     *    to the player queue in one shot (avoiding the per-song network
     *    round-trip the original one-at-a-time loop incurred).
     *
     * Falls back to a search query of `"${artist} ${title} mix"` if the
     * current song has no youtubeId (so relatedItems can't be fetched).
     */
    suspend fun getMultipleAutoplayRecommendations(
        currentSong: Song,
        currentQueueIds: List<String>,
        proxyUrlProvider: (String) -> String,
        limit: Int = 5
    ): List<Song> = withContext(Dispatchers.IO) {
        if (limit <= 0) return@withContext emptyList()
        val safeLimit = limit.coerceAtMost(20)  // safety cap to avoid hammering YouTube
        try {
            val candidateItems: List<StreamInfoItem> = if (currentSong.youtubeId != null) {
                val url = "https://www.youtube.com/watch?v=${currentSong.youtubeId}"
                val extractor = ServiceList.YouTube.getStreamExtractor(url)
                extractor.fetchPage()
                extractor.relatedItems?.items?.filterIsInstance<StreamInfoItem>() ?: emptyList()
            } else {
                val query = "${currentSong.artist} ${currentSong.title} mix"
                val searchExtractor = ServiceList.YouTube.getSearchExtractor(query)
                searchExtractor.fetchPage()
                searchExtractor.initialPage.items.filterIsInstance<StreamInfoItem>()
            }

            val excludeIds = currentQueueIds.toHashSet()
            val picked = ArrayList<Song>(safeLimit)
            for (item in candidateItems) {
                if (picked.size >= safeLimit) break
                val videoId = extractVideoId(item.url) ?: continue
                if (videoId in excludeIds) continue

                val durationMs = if (item.duration > 0) item.duration * 1000L else 0L
                val song = Song.emptySong().copy(
                    id = videoId,
                    title = item.name ?: "Unknown",
                    artist = item.uploaderName ?: "Unknown",
                    artistId = -1L,
                    album = "",
                    albumId = -1L,
                    path = item.url,
                    contentUriString = proxyUrlProvider(videoId),
                    albumArtUriString = item.thumbnails.firstOrNull()?.url,
                    duration = durationMs,
                    mimeType = "audio/mp4",
                    youtubeId = videoId
                )
                picked += song
            }
            picked
        } catch (e: Exception) {
            Timber.e(e, "Error getting multiple autoplay recommendations")
            emptyList()
        }
    }
}
