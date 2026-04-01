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
            if (audioStreams.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("No audio streams found for video $youtubeId"))
            }

            // Only use progressive HTTP streams that provide a direct URL.
            // DASH streams return manifest XML in getContent(), which cannot be
            // proxied as a simple byte stream to ExoPlayer.
            val progressiveStreams = audioStreams.filter { stream ->
                (stream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP || stream.deliveryMethod == DeliveryMethod.DASH) && stream.isUrl
            }

            if (progressiveStreams.isEmpty()) {
                Timber.w("No progressive audio streams for $youtubeId. DeliveryMethods: ${audioStreams.map { it.deliveryMethod }}")
                return@withContext Result.failure(Exception("No progressive audio streams found for video $youtubeId"))
            }

            val bestStream = findBestAudioStream(progressiveStreams, quality)

            if (bestStream != null) {
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
                ServiceList.YouTube.getSearchExtractor(query, listOf(searchFilter), null)
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
        try {
            val validItem = if (currentSong.youtubeId != null) {
                val url = "https://www.youtube.com/watch?v=${currentSong.youtubeId}"
                val extractor = ServiceList.YouTube.getStreamExtractor(url)
                extractor.fetchPage()
                extractor.relatedItems?.items?.filterIsInstance<StreamInfoItem>()?.firstOrNull { item ->
                    val videoId = extractVideoId(item.url)
                    videoId != null && !currentQueueIds.contains(videoId)
                }
            } else {
                val query = "${currentSong.artist} ${currentSong.title} mix"
                val extractor = ServiceList.YouTube.getSearchExtractor(query)
                extractor.fetchPage()
                extractor.initialPage.items.filterIsInstance<StreamInfoItem>().firstOrNull { item ->
                    val videoId = extractVideoId(item.url)
                    videoId != null && !currentQueueIds.contains(videoId)
                }
            }

            if (validItem != null) {
                val youtubeId = extractVideoId(validItem.url)!!
                val durationMs = if (validItem.duration > 0) validItem.duration * 1000L else 0L
                val song = Song.emptySong().copy(
                    id = youtubeId,
                    title = validItem.name ?: "Unknown",
                    artist = validItem.uploaderName ?: "Unknown",
                    artistId = -1L,
                    album = "",
                    albumId = -1L,
                    path = validItem.url,
                    contentUriString = proxyUrlProvider(youtubeId),
                    albumArtUriString = validItem.thumbnails.firstOrNull()?.url,
                    duration = durationMs,
                    mimeType = "audio/mp4",
                    youtubeId = youtubeId
                )
                Result.success(song)
            } else {
                Result.failure(Exception("No suitable autoplay recommendation found."))
            }
        } catch (e: Exception) {
            Timber.e(e, "Error getting autoplay recommendation")
            Result.failure(e)
        }
    }
}
