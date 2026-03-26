package com.theveloper.pixeltune.data.soundcloud

import com.theveloper.pixeltune.data.model.Album
import com.theveloper.pixeltune.data.model.Artist
import com.theveloper.pixeltune.data.model.Playlist
import com.theveloper.pixeltune.data.model.SearchResultItem
import com.theveloper.pixeltune.data.model.SearchFilterType
import com.theveloper.pixeltune.data.model.Song
import com.theveloper.pixeltune.data.preferences.StreamingQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.stream.AudioStream
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

            // Pick the best stream by bitrate based on quality
            val bestStream = when (quality) {
                StreamingQuality.HIGH_RES -> audioStreams.maxByOrNull { it.bitrate }
                StreamingQuality.DATA_SAVER -> audioStreams.minByOrNull { it.bitrate }
                StreamingQuality.NORMAL -> {
                    val sortedStreams = audioStreams.sortedBy { it.bitrate }
                    sortedStreams.minByOrNull { kotlin.math.abs(it.bitrate - 128) } ?: sortedStreams.getOrNull(sortedStreams.size / 2)
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
                                albumArtUriString = item.thumbnails.firstOrNull()?.url,
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
