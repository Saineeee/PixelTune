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
        val searchFilter = when (filter) {
            SearchFilterType.ALL -> YoutubeSearchQueryHandlerFactory.MUSIC_SONGS
            SearchFilterType.SONGS -> YoutubeSearchQueryHandlerFactory.MUSIC_SONGS
            SearchFilterType.ALBUMS -> "playlists" // YouTube doesn't map albums cleanly, but NewPipe handles it
            SearchFilterType.ARTISTS -> "channels"
            SearchFilterType.PLAYLISTS -> "playlists"
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
        return results
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
