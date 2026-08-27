package com.theveloper.pixeltune.data.soundcloud

import com.theveloper.pixeltune.data.model.Album
import com.theveloper.pixeltune.data.model.Artist
import com.theveloper.pixeltune.data.model.Playlist
import com.theveloper.pixeltune.data.model.SearchResultItem
import com.theveloper.pixeltune.data.model.SearchFilterType
import com.theveloper.pixeltune.data.model.Song
import com.theveloper.pixeltune.data.preferences.StreamingQuality
import com.theveloper.pixeltune.data.stream.CloudArtworkHelper
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
                    runCatching { java.net.URLDecoder.decode(encoded, "UTF-8") }.getOrNull()
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
                            val playlist = Playlist(
                                id = item.url.hashCode().toString(),
                                name = item.name ?: "Unknown Playlist",
                                songIds = emptyList() // We don't fetch songs right now
                            )
                            results.add(SearchResultItem.PlaylistItem(playlist))
                        }
                    }
                    is ChannelInfoItem -> {
                        if (filter == SearchFilterType.ALL || filter == SearchFilterType.ARTISTS) {
                            val artist = Artist(
                                id = item.url.hashCode().toLong(),
                                name = item.name ?: "Unknown Artist",
                                songCount = item.subscriberCount.toInt(),
                            )
                            results.add(SearchResultItem.ArtistItem(artist))
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
}
