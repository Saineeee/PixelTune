package com.theveloper.pixeltune.data.youtube

import com.theveloper.pixeltune.data.model.CloudArtist
import com.theveloper.pixeltune.data.model.CloudPlaylist
import com.theveloper.pixeltune.data.model.CloudStreamProvider
import com.theveloper.pixeltune.data.model.CloudTracksPage
import com.theveloper.pixeltune.data.model.SearchResultItem
import com.theveloper.pixeltune.data.model.SearchFilterType
import com.theveloper.pixeltune.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import com.theveloper.pixeltune.data.preferences.StreamingQuality
import com.theveloper.pixeltune.data.stream.CloudArtworkHelper
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
        // IMPROVE(music-only): search YouTube MUSIC (the music.youtube.com
        // index) instead of generic YouTube videos. Generic results regularly
        // include vlogs, tutorials, podcasts and other non-music uploads the
        // user explicitly does not want; the YT Music songs index guarantees
        // real songs. The "All" tab maps to the same music-songs filter so
        // ONLY music shows up everywhere (search, tap-to-play, queue refill).
        //
        // FIX(online-filter-chips): the Albums / Artists / Playlists chips now
        // map to the matching YouTube MUSIC indexes (MUSIC_ALBUMS /
        // MUSIC_ARTISTS / MUSIC_PLAYLISTS) instead of the generic
        // "playlists"/"channels" filters. NewPipe's YoutubeMusicSearchExtractor
        // fully supports all of them (its params switch selects the YT Music
        // tab and commits dedicated item extractors — albums & playlists yield
        // PlaylistInfoItems, artists yield ChannelInfoItems), and every result
        // carries the rich metadata (artwork, uploader, counts) the cloud
        // result rows and the CloudCatalog detail screen render.
        val searchFilter = when (filter) {
            SearchFilterType.ALL -> YoutubeSearchQueryHandlerFactory.MUSIC_SONGS
            SearchFilterType.SONGS -> YoutubeSearchQueryHandlerFactory.MUSIC_SONGS
            SearchFilterType.ALBUMS -> YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS
            SearchFilterType.ARTISTS -> YoutubeSearchQueryHandlerFactory.MUSIC_ARTISTS
            SearchFilterType.PLAYLISTS -> YoutubeSearchQueryHandlerFactory.MUSIC_PLAYLISTS
        }

        // Tier 1: the YouTube Music songs index.
        var results = runCatching {
            performSearch(query, searchFilter, filter, proxyUrlProvider)
        }.onFailure { e ->
            Timber.e(e, "Error searching YouTube for query: $query")
        }.getOrDefault(emptyList())

        // FIX(search-reliability): the YT Music index occasionally fails or
        // returns a bot-check/HTML/short response (which NewPipe turns into an
        // exception) — the app then showed NOTHING at all for the query
        // ("sometimes it doesn't even show up when searching"). When the music
        // index yielded no song items, retry once against the generic YouTube
        // videos search and keep only music-shaped items (real duration, no
        // live streams, single-song length via [isMusicCandidate]) so the
        // music-only promise holds as closely as the generic index allows.
        // Better a relevant, duration-filtered result list than a blank screen.
        val wasMusicIndexSearch =
            filter == SearchFilterType.ALL || filter == SearchFilterType.SONGS
        if (wasMusicIndexSearch && results.none { it is SearchResultItem.SongItem }) {
            val fallbackResults = runCatching {
                performSearch(
                    query = query,
                    searchFilter = YoutubeSearchQueryHandlerFactory.VIDEOS,
                    filter = filter,
                    proxyUrlProvider = proxyUrlProvider,
                    musicOnlyFilter = true
                )
            }.onFailure { e ->
                Timber.w(e, "YouTube generic search fallback failed for query: %s", query)
            }.getOrDefault(emptyList())
            if (fallbackResults.isNotEmpty()) {
                Timber.d(
                    "YouTube music search returned no songs for '%s' — generic " +
                        "videos fallback returned %d items",
                    query, fallbackResults.size
                )
                results = fallbackResults
            }
        }
        results
    }

    /**
     * Runs one NewPipe YouTube search with [searchFilter] and maps the raw
     * items to [SearchResultItem]s (song/playlist/artist) for the requested
     * UI [filter].
     *
     * [musicOnlyFilter] is set only by the generic-videos FALLBACK tier of
     * [searchYouTube]: it drops stream items that don't look like a single
     * song (live streams, unknown durations, > 20 min uploads) so the
     * fallback can never regress the music-only search requirement.
     */
    private fun performSearch(
        query: String,
        searchFilter: String,
        filter: SearchFilterType,
        proxyUrlProvider: (String) -> String,
        musicOnlyFilter: Boolean = false
    ): List<SearchResultItem> {
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
                        if (musicOnlyFilter && !isMusicCandidate(item)) return@forEach
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
                                albumArtUriString = bestArtworkUrl(item, youtubeId),
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
                            // FIX(online-filter-chips): keep the provider's own
                            // metadata (uploader, track count, artwork) on a
                            // dedicated cloud model instead of squeezing the
                            // item into the LOCAL Playlist shape (which always
                            // rendered "0 songs" with a placeholder cover and
                            // opened the local PlaylistDetail screen that can
                            // never resolve it).
                            val playlistUrl = normalizePlaylistUrl(item.url)
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
                                        isAlbum = filter == SearchFilterType.ALBUMS,
                                        provider = com.theveloper.pixeltune.data.model.CloudStreamProvider.YOUTUBE
                                    )
                                )
                            )
                        }
                    }
                    is ChannelInfoItem -> {
                        if (filter == SearchFilterType.ALL || filter == SearchFilterType.ARTISTS) {
                            // FIX(online-filter-chips): same treatment for
                            // artists — keep the channel avatar + subscriber
                            // count NewPipe extracted (the LOCAL Artist mapping
                            // dropped the avatar and mislabeled subscribers as
                            // "X Songs"), and keep the channel URL the detail
                            // screen extracts the artist's tracks from.
                            results.add(
                                SearchResultItem.CloudArtistItem(
                                    CloudArtist(
                                        id = item.url.hashCode().toString(),
                                        url = item.url,
                                        name = item.name ?: "Unknown Artist",
                                        subscriberCount = runCatching { item.subscriberCount }.getOrDefault(-1L),
                                        artworkUrl = CloudArtworkHelper.bestArtworkUrl(
                                            runCatching { item.thumbnails }.getOrDefault(emptyList())
                                        ),
                                        isVerified = runCatching { item.isVerified }.getOrDefault(false),
                                        provider = com.theveloper.pixeltune.data.model.CloudStreamProvider.YOUTUBE
                                    )
                                )
                            )
                        }
                    }
                }
            }
        return results
    }

    private fun extractVideoId(url: String): String? {
        // e.g., https://www.youtube.com/watch?v=dQw4w9WgXcQ
        val regex = Regex("v=([a-zA-Z0-9_-]+)")
        return regex.find(url)?.groupValues?.get(1) ?: url.substringAfterLast("/").substringBefore("?")
    }

    /**
     * FIX(online-filter-chips): canonical playlist URL for extraction.
     *
     * YouTube Music search results link to `music.youtube.com/playlist?list=X`;
     * the extractor accepts both hosts (it resolves purely by the `list` id),
     * but the app's other playlist paths (import, radio mixes) normalize to
     * `www.youtube.com` — keep every stored cloud playlist URL on that same
     * canonical form so persisted entries behave identically everywhere.
     */
    private fun normalizePlaylistUrl(url: String): String {
        return url.replace("music.youtube.com", "www.youtube.com")
    }

    /**
     * FIX(album-art-quality): best available artwork URL for a YouTube item.
     *
     * NewPipe's thumbnail lists are ordered SMALLEST-FIRST, so the old
     * `thumbnails.firstOrNull()` picked a 60x60 variant for YouTube Music
     * results (blurry art everywhere) and returned null — "no artwork at
     * all" — when the item's list was empty.
     *
     *  1. Pick the highest-resolution entry from the list
     *     (see [CloudArtworkHelper.bestArtworkUrl]).
     *  2. FIX(youtube-art-quality): [CloudArtworkHelper.bestArtworkUrl] now
     *     also rewrites the chosen URL to its high-resolution equivalent
     *     (Google image CDN size token -> 1080px, i.ytimg.com ->
     *     maxresdefault.jpg) — NewPipe's YouTube Music song items only
     *     expose 60x60/120x120 covers, which the player upscaled ~9x.
     *  3. When the list is empty, synthesize the deterministic YouTube
     *     thumbnail URL for the video id and upgrade it the same way
     *     (`maxresdefault.jpg`, 1280x720). The artwork client's
     *     [com.theveloper.pixeltune.data.network.YtimgArtworkFallbackInterceptor]
     *     guarantees the chain `maxresdefault -> hq720 -> hqdefault` (the
     *     latter exists for EVERY valid video) still loads when a video has
     *     no maxres artwork, so artwork always has something to show.
     */
    private fun bestArtworkUrl(item: StreamInfoItem, videoId: String?): String? {
        CloudArtworkHelper.bestArtworkUrl(item)?.let { return it }
        return videoId?.takeIf { it.isNotEmpty() }
            ?.let { CloudArtworkHelper.upgradeToHighRes("https://i.ytimg.com/vi/$it/hqdefault.jpg") }
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
     * FIX(music-only-radio): the queue suggestions used to come from the watch
     * page's `relatedItems` ("Up next"), which for YouTube is the GENERIC
     * autoplay mix — reactions, vlogs, gaming videos and other non-music
     * uploads regularly leaked into the queue because they passed the
     * duration-only music filter. The user explicitly reported exactly that
     * ("reactions, funny videos, a completely different language gaming
     * video") and asked that ONLY music related to the current song ever
     * appears.
     *
     * The candidate source is therefore strictly music-only now, in tiers:
     *
     *  1. **YouTube Music radio mix** — playlist id `RDAMVM<videoId>` is the
     *     exact "Start radio" station YouTube Music itself builds for a track:
     *     every entry is a real song chosen by YT Music's music-radio
     *     algorithm. NewPipe v0.26.3 fully supports it
     *     (`YoutubeParsingHelper.isYoutubeMusicMixId` recognises the RDAMVM
     *     prefix; `YoutubePlaylistLinkHandlerFactory.fromUrl` accepts
     *     `watch?v=<id>&list=RDAMVM<id>`; `YoutubeService.getPlaylistExtractor`
     *     routes every RD* id to `YoutubeMixPlaylistExtractor`, which pages the
     *     innertube `/next` endpoint).
     *
     *  2. **YouTube Music songs search** (`MUSIC_SONGS` — the music.youtube.com
     *     index, never generic videos) for `"<artist> <title>"`, used when the
     *     mix is unavailable (track not in YT Music's index / extractor error)
     *     or yields too few fresh songs.
     *
     * The generic watch-page `relatedItems` list is deliberately NEVER used —
     * it cannot be filtered reliably into music (a reaction video has a normal
     * duration and an ordinary uploader), so an empty result + the radio
     * retry/backoff logic in MusicService is strictly better than a queue
     * polluted with unrelated videos.
     *
     * A near-duplicate title filter also keeps the same track (official audio
     * / official video / lyric-video uploads of one song) from filling the
     * whole queue when the search tier is used.
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
            val excludeIds = currentQueueIds.toHashSet()
            val seedTitleKey = normalizeTitleForDedup(currentSong.title)
            val pickedTitleKeys = HashSet<String>()
            if (seedTitleKey.isNotEmpty()) pickedTitleKeys.add(seedTitleKey)
            val picked = ArrayList<Song>(safeLimit)

            val youtubeId = currentSong.youtubeId
            if (youtubeId != null) {
                // Tier 1: the YT Music "Start radio" mix for this exact track.
                picked += pickMusicSongs(
                    candidates = fetchMusicRadioMixItems(youtubeId),
                    excludeIds = excludeIds,
                    pickedTitleKeys = pickedTitleKeys,
                    remaining = safeLimit,
                    proxyUrlProvider = proxyUrlProvider
                )
            }
            if (picked.size < safeLimit) {
                // Tier 2: the YT Music songs index for this artist + title.
                // Also the primary source for seeds without a YouTube id
                // (local-library radio).
                picked += pickMusicSongs(
                    candidates = fetchMusicSearchItems(currentSong),
                    excludeIds = excludeIds,
                    pickedTitleKeys = pickedTitleKeys,
                    remaining = safeLimit - picked.size,
                    proxyUrlProvider = proxyUrlProvider
                )
            }
            picked
        } catch (e: Exception) {
            Timber.e(e, "Error getting multiple autoplay recommendations")
            emptyList()
        }
    }

    /**
     * FIX(music-only-radio): fetches YouTube Music's own radio mix for
     * [videoId]. Playlist id `RDAMVM<videoId>` is the exact "Start radio"
     * station YT Music builds for the track, so every entry is a real song
     * related to the seed — the same station the YouTube Music app plays.
     * Returns an empty list on ANY failure (non-music video id, extractor
     * error, network) so the caller can fall back to the music search.
     */
    private fun fetchMusicRadioMixItems(videoId: String): List<StreamInfoItem> {
        if (videoId.isEmpty()) return emptyList()
        return runCatching {
            val mixUrl = "https://www.youtube.com/watch?v=$videoId&list=RDAMVM$videoId"
            val playlistExtractor = ServiceList.YouTube.getPlaylistExtractor(mixUrl)
            playlistExtractor.fetchPage()
            playlistExtractor.initialPage.items.filterIsInstance<StreamInfoItem>()
        }.onFailure { e ->
            Timber.w(
                e,
                "YT Music radio mix unavailable for %s — falling back to music search",
                videoId
            )
        }.getOrDefault(emptyList())
    }

    /**
     * FIX(music-only-radio): searches the YouTube MUSIC songs index (never
     * generic videos) for the seed song, so every candidate is a real track.
     * Guards the query the same way the old fallback did: a blank/placeholder
     * artist ("Unknown", "-") must not poison the search.
     */
    private fun fetchMusicSearchItems(song: Song): List<StreamInfoItem> {
        val title = song.title.trim().takeIf { it.isNotEmpty() } ?: return emptyList()
        val artist = song.artist.trim()
        val usableArtist = artist.takeIf {
            it.isNotEmpty() && !it.equals("unknown", ignoreCase = true) && it != "-"
        }
        val query = if (usableArtist != null) "$usableArtist $title" else title
        return runCatching {
            val searchExtractor = ServiceList.YouTube.getSearchExtractor(
                query,
                listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS),
                ""
            )
            searchExtractor.fetchPage()
            searchExtractor.initialPage.items.filterIsInstance<StreamInfoItem>()
        }.onFailure { e ->
            Timber.w(e, "YT Music songs search failed for query: %s", query)
        }.getOrDefault(emptyList())
    }

    /**
     * Turns music candidate [candidates] into playable [Song]s:
     *  - [isMusicCandidate] still applies (no live streams, real durations,
     *    single-song length) as a sanity net even for music mixes;
     *  - items already queued / recently played ([excludeIds]) are skipped;
     *  - near-duplicate titles ([pickedTitleKeys] — the seed song's title plus
     *    everything already picked) are skipped so one track's official
     *    audio/video/lyric uploads can't fill the whole radio queue.
     */
    private fun pickMusicSongs(
        candidates: List<StreamInfoItem>,
        excludeIds: HashSet<String>,
        pickedTitleKeys: HashSet<String>,
        remaining: Int,
        proxyUrlProvider: (String) -> String
    ): List<Song> {
        if (remaining <= 0 || candidates.isEmpty()) return emptyList()
        val picked = ArrayList<Song>(remaining)
        for (item in candidates) {
            if (picked.size >= remaining) break
            if (!isMusicCandidate(item)) continue
            val videoId = extractVideoId(item.url) ?: continue
            if (videoId in excludeIds) continue

            val title = item.name ?: "Unknown"
            val titleKey = normalizeTitleForDedup(title)
            if (titleKey.isNotEmpty()) {
                if (titleKey in pickedTitleKeys) continue
                pickedTitleKeys.add(titleKey)
            }

            val durationMs = if (item.duration > 0) item.duration * 1000L else 0L
            picked += Song.emptySong().copy(
                id = videoId,
                title = title,
                artist = item.uploaderName ?: "Unknown",
                artistId = -1L,
                album = "",
                albumId = -1L,
                path = item.url,
                contentUriString = proxyUrlProvider(videoId),
                albumArtUriString = bestArtworkUrl(item, videoId),
                duration = durationMs,
                mimeType = "audio/mp4",
                youtubeId = videoId
            )
        }
        return picked
    }

    /**
     * Normalizes a song title for near-duplicate detection in the radio queue:
     * lowercase, strip the common "official video / lyrics / audio / remaster"
     * decoration words (whole words only, so "lyric" never mangles "lyrical")
     * and all punctuation, collapse whitespace. "Song", "Song (Official
     * Audio)" and "Song! - Official Video" all normalize to the same key, so a
     * radio refill never queues the same track twice while genuinely different
     * titles (covers, live versions, features) still pass.
     */
    private fun normalizeTitleForDedup(title: String): String {
        var s = title.lowercase().trim()
        for (pattern in TITLE_NOISE_PATTERNS) {
            s = pattern.replace(s, " ")
        }
        return s.replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .joinToString(" ")
    }

    /**
     * IMPROVE(music-only): heuristics that keep only actual music in the
     * endless-radio queue:
     *  - live / audio-live streams are excluded (radio shows, 24/7 streams);
     *  - unknown durations (<= 0) are excluded — real songs always report one;
     *  - very long uploads (> 20 min) are excluded — those are mixes,
     *    compilations or full-album videos rather than songs.
     */
    private fun isMusicCandidate(item: StreamInfoItem): Boolean {
        val streamType = runCatching { item.streamType }.getOrNull()
        if (streamType == StreamType.LIVE_STREAM || streamType == StreamType.AUDIO_LIVE_STREAM) {
            return false
        }
        val durationSeconds = item.duration
        if (durationSeconds <= 0) return false
        return durationSeconds <= MAX_RADIO_CANDIDATE_DURATION_SECONDS
    }

    /**
     * FIX(online-filter-chips): extracts the FIRST page of playable tracks of
     * a YouTube (Music) playlist or album found by the online search.
     *
     * The playlist extractor resolves purely by the `list` id, so albums
     * (OLAK5uy_…), YT Music playlists (RDCLAK5uy_…) and plain user playlists
     * all flow through the same path. Every returned [Song] is built exactly
     * like an online search result (same id scheme, same proxy URL, same
     * high-res artwork upgrade), so it is immediately playable, persistable
     * when liked, and identical to what the user would have gotten by finding
     * the track through search.
     */
    suspend fun getCloudPlaylistTracks(
        playlist: CloudPlaylist,
        proxyUrlProvider: (String) -> String
    ): Result<CloudTracksPage> = withContext(Dispatchers.IO) {
        try {
            val extractor = ServiceList.YouTube.getPlaylistExtractor(
                normalizePlaylistUrl(playlist.url)
            )
            extractor.fetchPage()
            val page = extractor.initialPage
            val songs = playlistStreamItemsToSongs(
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
            Timber.e(e, "Error extracting YouTube playlist tracks for %s", playlist.url)
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
                IllegalArgumentException("No continuation for YouTube playlist ${playlist.url}")
            )
        try {
            val extractor = ServiceList.YouTube.getPlaylistExtractor(
                normalizePlaylistUrl(playlist.url)
            )
            extractor.fetchPage()
            val next = extractor.getPage(continuation)
            val songs = playlistStreamItemsToSongs(
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
            Timber.e(e, "Error paging YouTube playlist tracks for %s", playlist.url)
            Result.failure(e)
        }
    }

    /**
     * FIX(online-filter-chips): extracts the FIRST page of a YouTube artist's
     * playable tracks, in two tiers (verified live against the real
     * extractor):
     *
     *  1. **Channel "videos" tab** — works for every REGULAR channel; the tab
     *     framework resolves it and pages through the uploads.
     *
     *  2. **YouTube Music songs search for the artist name** — the fallback
     *     for the "- Topic" auto-generated channels the YT Music artists index
     *     returns for essentially every music artist: those channels expose NO
     *     channel tabs at all (live-verified: `getTabs()` is empty and direct
     *     tab URLs throw), so the only reliable track source is a
     *     `MUSIC_SONGS` search. Results are kept when the song's uploader
     *     matches the artist name (live-verified: 12/12 top results matched),
     *     deduped by video id, and paginated through the search continuation.
     */
    suspend fun getCloudArtistTracks(
        artist: CloudArtist,
        proxyUrlProvider: (String) -> String
    ): Result<CloudTracksPage> = withContext(Dispatchers.IO) {
        // Tier 1: the channel's videos tab (regular channels).
        try {
            val channel = ServiceList.YouTube.getChannelExtractor(
                normalizeChannelUrl(artist.url)
            )
            channel.fetchPage()
            val videosTab = channel.tabs.firstOrNull { tab ->
                tab.contentFilters.contains(ChannelTabs.VIDEOS)
            }
            if (videosTab != null) {
                val tabInfo = ChannelTabInfo.getInfo(ServiceList.YouTube, videosTab)
                val songs = dedupeBySongId(
                    playlistStreamItemsToSongs(
                        items = tabInfo.relatedItems.filterIsInstance<StreamInfoItem>(),
                        proxyUrlProvider = proxyUrlProvider,
                        contextTitle = artist.name
                    )
                )
                return@withContext Result.success(
                    CloudTracksPage(
                        songs = songs,
                        refreshedTitle = runCatching { channel.name }.getOrNull(),
                        refreshedSubtitle = formatSubscriberCount(
                            runCatching { channel.subscriberCount }.getOrDefault(-1L)
                        ),
                        hasMore = tabInfo.nextPage != null,
                        continuation = tabInfo.nextPage?.let {
                            ChannelTabContinuation(videosTab, it)
                        }
                    )
                )
            }
            Timber.d(
                "Channel %s exposes no videos tab (likely a Topic channel) — " +
                    "falling back to the YT Music songs search for '%s'",
                artist.url, artist.name
            )
        } catch (e: Exception) {
            Timber.w(
                e,
                "Channel extraction failed for %s — falling back to the YT Music " +
                    "songs search for '%s'",
                artist.url, artist.name
            )
        }

        // Tier 2: the YT Music songs index for the artist name.
        searchArtistSongs(artist, proxyUrlProvider, null)
    }

    /**
     * FIX(online-filter-chips): continues a [getCloudArtistTracks] listing
     * (the "Load more" action of the cloud artist detail screen) — follows
     * whichever tier produced the previous page, using the tagged
     * continuation this repository stored on it.
     */
    suspend fun getMoreCloudArtistTracks(
        artist: CloudArtist,
        page: CloudTracksPage,
        proxyUrlProvider: (String) -> String
    ): Result<CloudTracksPage> = withContext(Dispatchers.IO) {
        when (val continuation = page.continuation) {
            is ChannelTabContinuation -> {
                try {
                    val next = ChannelTabInfo.getMoreItems(
                        ServiceList.YouTube, continuation.tab, continuation.page
                    )
                    val songs = dedupeBySongId(
                        playlistStreamItemsToSongs(
                            items = next.items.filterIsInstance<StreamInfoItem>(),
                            proxyUrlProvider = proxyUrlProvider,
                            contextTitle = artist.name
                        )
                    )
                    Result.success(
                        CloudTracksPage(
                            songs = songs,
                            hasMore = next.nextPage != null,
                            continuation = next.nextPage?.let {
                                ChannelTabContinuation(continuation.tab, it)
                            }
                        )
                    )
                } catch (e: Exception) {
                    Timber.e(e, "Error paging YouTube artist tracks for %s", artist.url)
                    Result.failure(e)
                }
            }
            is ArtistSearchContinuation ->
                searchArtistSongs(artist, proxyUrlProvider, continuation.page)
            else -> Result.failure(
                IllegalArgumentException("No continuation for YouTube artist ${artist.url}")
            )
        }
    }

    /**
     * FIX(online-filter-chips): one page of the Tier-2 artist listing — the
     * YT Music songs search for the artist's name, filtered to songs whose
     * uploader matches the artist (Topic-channel tracks report the plain
     * artist name), deduped by video id. [continuation] is null for the
     * first page and the previous search `Page` for "Load more".
     */
    private fun searchArtistSongs(
        artist: CloudArtist,
        proxyUrlProvider: (String) -> String,
        continuation: org.schabi.newpipe.extractor.Page?
    ): Result<CloudTracksPage> {
        return try {
            val searchExtractor = ServiceList.YouTube.getSearchExtractor(
                artist.name,
                listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS),
                ""
            )
            searchExtractor.fetchPage()
            val resultPage = if (continuation != null) {
                searchExtractor.getPage(continuation)
            } else {
                searchExtractor.initialPage
            }
            val items = resultPage.items.filterIsInstance<StreamInfoItem>()
            val artistKey = artist.name.trim().lowercase()
            val matching = items.filter { item ->
                val uploader = item.uploaderName?.trim()?.lowercase().orEmpty()
                uploader.isNotEmpty() && (uploader.contains(artistKey) || artistKey.contains(uploader))
            }
            // Keep matched songs; only when nothing matched (odd provider
            // naming) keep the raw results so the screen is never empty.
            val chosen = if (matching.isNotEmpty()) matching else items
            val songs = dedupeBySongId(
                playlistStreamItemsToSongs(
                    items = chosen,
                    proxyUrlProvider = proxyUrlProvider,
                    contextTitle = artist.name
                )
            )
            Result.success(
                CloudTracksPage(
                    songs = songs,
                    hasMore = resultPage.nextPage != null,
                    continuation = resultPage.nextPage?.let {
                        ArtistSearchContinuation(it)
                    }
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "YT Music songs search for artist '%s' failed", artist.name)
            Result.failure(e)
        }
    }

    /** Continuation tag for the channel-tab tier of the artist listing. */
    private class ChannelTabContinuation(
        val tab: ListLinkHandler,
        val page: org.schabi.newpipe.extractor.Page
    )

    /** Continuation tag for the songs-search tier of the artist listing. */
    private class ArtistSearchContinuation(
        val page: org.schabi.newpipe.extractor.Page
    )

    /**
     * FIX(online-filter-chips): drops later entries whose song id already
     * appeared (YouTube allows the same video twice in a playlist, and the
     * songs-search tier frequently repeats a track across pages).
     */
    private fun dedupeBySongId(songs: List<Song>): List<Song> {
        val seen = HashSet<String>(songs.size)
        return songs.filter { seen.add(it.id) }
    }

    /**
     * FIX(online-filter-chips): canonical channel URL for extraction.
     *
     * YT Music artist results link to `music.youtube.com/channel/UC…`; the
     * channel link handler accepts that host, but the canonical
     * `www.youtube.com` form is what the app's other channel paths use, and
     * it sidesteps any music-host edge case in future extractor versions.
     */
    private fun normalizeChannelUrl(url: String): String {
        return url.replace("music.youtube.com", "www.youtube.com")
    }

    /**
     * FIX(online-filter-chips): "1.2M subscribers" style subtitle for the
     * cloud artist header. Returns null when the count is unknown.
     */
    private fun formatSubscriberCount(count: Long): String? {
        if (count < 0) return null
        return when {
            count >= 1_000_000L -> {
                val v = count / 1_000_000L
                val frac = (count % 1_000_000L) / 100_000L
                if (frac > 0) "$v.${frac}M subscribers" else "$v M subscribers"
            }
            count >= 1_000L -> "${count / 1_000L}K subscribers"
            else -> "$count subscribers"
        }
    }

    /**
     * FIX(online-filter-chips): maps playlist/channel-tab [StreamInfoItem]s to
     * immediately-playable [Song]s — identical construction to the online
     * search results (id = video id, proxy URL per video, high-res artwork),
     * so the queue, favorites and downloads treat them uniformly.
     *
     * Unplayable entries (live streams, missing ids) are skipped instead of
     * aborting the whole listing — an album with one unavailable video still
     * lists the rest.
     */
    private fun playlistStreamItemsToSongs(
        items: List<StreamInfoItem>,
        proxyUrlProvider: (String) -> String,
        contextTitle: String
    ): List<Song> {
        val songs = ArrayList<Song>(items.size)
        for (item in items) {
            val videoId = extractVideoId(item.url) ?: continue
            val durationMs = if (item.duration > 0) item.duration * 1000L else 0L
            songs += Song.emptySong().copy(
                id = videoId,
                title = item.name ?: "Unknown",
                artist = item.uploaderName ?: "Unknown",
                artistId = -1L,
                album = contextTitle,
                albumId = -1L,
                path = item.url,
                contentUriString = proxyUrlProvider(videoId),
                albumArtUriString = bestArtworkUrl(item, videoId),
                duration = durationMs,
                mimeType = "audio/mp4",
                youtubeId = videoId
            )
        }
        return songs
    }

    companion object {
        /** 20 minutes — upper bound for what still counts as a single song. */
        private const val MAX_RADIO_CANDIDATE_DURATION_SECONDS = 20 * 60

        /**
         * Decoration words stripped (as whole words) before comparing
         * radio-candidate titles in [normalizeTitleForDedup], so the same
         * track's "Song", "Song (Official Audio)" and "Song - Official Video"
         * uploads collapse to one key. Bracket characters are stripped
         * separately, so the bare phrases cover every bracketing style.
         * Longer phrases first so "official music video" is consumed before
         * "official video" / "official audio".
         */
        private val TITLE_NOISE_TERMS = listOf(
            "official music video",
            "official lyric video",
            "official visualizer",
            "official lyrics",
            "official video",
            "official audio",
            "official mv",
            "lyrics video",
            "lyric video",
            "audio only",
            "visualizer",
            "remastered",
            "remasterizado",
            "remaster",
            "lyrics",
            "lyric",
            "m/v",
            "mv",
            "hd",
            "hq",
            "4k"
        )

        /** [TITLE_NOISE_TERMS] pre-compiled with word boundaries. */
        private val TITLE_NOISE_PATTERNS = TITLE_NOISE_TERMS.map {
            Regex("\\b" + Regex.escape(it) + "\\b")
        }
    }
}
