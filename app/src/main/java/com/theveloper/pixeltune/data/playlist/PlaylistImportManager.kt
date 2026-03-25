package com.theveloper.pixeltune.data.playlist

import com.theveloper.pixeltune.data.database.AlbumEntity
import com.theveloper.pixeltune.data.database.ArtistEntity
import com.theveloper.pixeltune.data.database.MusicDao
import com.theveloper.pixeltune.data.database.SongEntity
import com.theveloper.pixeltune.data.model.SearchFilterType
import com.theveloper.pixeltune.data.model.SearchResultItem
import com.theveloper.pixeltune.data.preferences.UserPreferencesRepository
import com.theveloper.pixeltune.data.youtube.YouTubeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.json.JSONObject
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistImportManager @Inject constructor(
    private val client: OkHttpClient,
    private val youtubeRepository: YouTubeRepository,
    private val musicDao: MusicDao,
    private val userPreferencesRepository: UserPreferencesRepository
) {

    data class ScrapedTrack(val title: String, val artist: String)

    suspend fun importPlaylist(url: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val (playlistName, tracks) = when {
                url.contains("youtube.com") || url.contains("youtu.be") -> {
                    extractYouTubePlaylist(url)
                }
                url.contains("spotify.com") -> scrapeSpotifyPlaylist(url)
                url.contains("music.apple.com") -> scrapeAppleMusicPlaylist(url)
                else -> throw IllegalArgumentException("Unsupported URL format: Must be YouTube, Spotify, or Apple Music.")
            }

            if (tracks.isEmpty()) {
                throw Exception("No tracks found in the playlist.")
            }

            val songEntities = mutableListOf<SongEntity>()
            for (track in tracks) {
                // Background text matching using YouTubeRepository
                val query = "${track.title} ${track.artist}"
                val results = youtubeRepository.searchYouTube(query, SearchFilterType.SONGS) { id -> "youtube://$id" }
                val bestMatch = results.firstOrNull() as? SearchResultItem.SongItem
                
                if (bestMatch != null) {
                    val proxyUrl = bestMatch.song.contentUriString
                    val songId = bestMatch.song.youtubeId?.hashCode()?.toLong() 
                        ?: bestMatch.song.id.hashCode().toLong()
                        
                    val entity = SongEntity(
                        id = songId,
                        title = bestMatch.song.title,
                        artistName = bestMatch.song.artist,
                        artistId = bestMatch.song.artistId,
                        albumName = bestMatch.song.album,
                        albumId = bestMatch.song.albumId,
                        contentUriString = proxyUrl, // Proxy ID mapping
                        albumArtUriString = bestMatch.song.albumArtUriString,
                        duration = bestMatch.song.duration,
                        genre = bestMatch.song.genre,
                        filePath = proxyUrl,
                        parentDirectoryPath = "imported_playlists", // helps filter it conceptually
                        isFavorite = false,
                        dateAdded = System.currentTimeMillis()
                    )
                    // We only add distinct matches
                    if (songEntities.none { it.id == songId }) {
                        songEntities.add(entity)
                    }
                }
            }

            if (songEntities.isEmpty()) {
                throw Exception("Could not match any tracks to playable streams.")
            }

            // Insert parent Artist and Album entities first to satisfy Foreign Key constraints
            val newArtists = songEntities.map {
                ArtistEntity(
                    id = it.artistId,
                    name = it.artistName,
                    trackCount = 0
                )
            }.distinctBy { it.id }

            val newAlbums = songEntities.map {
                AlbumEntity(
                    id = it.albumId,
                    title = it.albumName,
                    artistName = it.artistName,
                    artistId = it.artistId,
                    albumArtUriString = it.albumArtUriString,
                    songCount = 0,
                    year = 0
                )
            }.distinctBy { it.id }

            musicDao.insertArtistsIgnoreConflicts(newArtists)
            musicDao.insertAlbumsIgnoreConflicts(newAlbums)

            // Now proceed with saving the songs
            musicDao.insertSongsIgnoreConflicts(songEntities)
            
            // Playlist Creation
            val newSongIds = songEntities.map { it.id.toString() }
            val finalName = playlistName.ifBlank { "Imported Playlist" }
            userPreferencesRepository.createPlaylist(finalName, newSongIds)
            
            Result.success("Imported $finalName with ${newSongIds.size} matching tracks!")
        } catch (e: Exception) {
            Timber.e(e, "Playlist import failed for url: $url")
            Result.failure(e)
        }
    }

    private suspend fun extractYouTubePlaylist(url: String): Pair<String, List<ScrapedTrack>> {
        // Normalize YouTube Music URLs to standard YouTube URLs for the extractor
        val normalizedUrl = url.replace("music.youtube.com", "www.youtube.com")

        val extractor = ServiceList.YouTube.getPlaylistExtractor(normalizedUrl)
        extractor.fetchPage()
        val playlistName = extractor.name ?: "YouTube Playlist"
        val tracks = extractor.initialPage.items.mapNotNull { item ->
            // Cast to StreamInfoItem to access the uploader/artist name
            if (item is org.schabi.newpipe.extractor.stream.StreamInfoItem) {
                val title = item.name ?: "Unknown Track"
                val artist = item.uploaderName ?: ""
                ScrapedTrack(title, artist)
            } else {
                null // Skip non-stream items like nested playlists or channels
            }
        }
        return Pair(playlistName, tracks)
    }

    private fun scrapeSpotifyPlaylist(url: String): Pair<String, List<ScrapedTrack>> {
        // Extract playlist ID from any Spotify playlist URL format
        val playlistId = Regex("playlist[/:]([a-zA-Z0-9]+)").find(url)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Could not extract Spotify playlist ID from URL")

        val embedUrl = "https://open.spotify.com/embed/playlist/$playlistId"
        val request = Request.Builder()
            .url(embedUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .build()

        val response = client.newCall(request).execute()
        val html = response.body?.string() ?: throw Exception("Failed to fetch Spotify embed HTML")

        // Extract the JSON payload from the __NEXT_DATA__ script tag
        val jsonMatch = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""")
            .find(html)
        val jsonString = jsonMatch?.groupValues?.get(1)
            ?: throw Exception("Could not find __NEXT_DATA__ JSON in Spotify embed page")

        val rootJson = JSONObject(jsonString)
        val props = rootJson.getJSONObject("props").getJSONObject("pageProps")

        // Extract playlist name
        val playlistName = try {
            props.getJSONObject("state").getJSONObject("data").getJSONObject("entity")
                .getString("name")
        } catch (e: Exception) {
            "Spotify Playlist"
        }

        // Extract tracks from the entity's trackList
        val tracks = mutableListOf<ScrapedTrack>()
        try {
            val trackList = props.getJSONObject("state").getJSONObject("data")
                .getJSONObject("entity").getJSONArray("trackList")

            for (i in 0 until trackList.length()) {
                val trackObj = trackList.getJSONObject(i)
                val trackName = trackObj.optString("title", "").ifBlank {
                    trackObj.optString("name", "")
                }
                if (trackName.isBlank()) continue

                val artistName = try {
                    trackObj.optString("subtitle", "").ifBlank {
                        val artists = trackObj.optJSONArray("artists")
                        if (artists != null && artists.length() > 0) {
                            artists.getJSONObject(0).optString("name", "Unknown")
                        } else "Unknown"
                    }
                } catch (e: Exception) { "Unknown" }

                if (tracks.none { it.title == trackName }) {
                    tracks.add(ScrapedTrack(trackName, artistName))
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse Spotify embed trackList, trying fallback")
            // Fallback: try to find tracks in the broader JSON structure
            val dataStr = props.toString()
            val fallbackRegex = Regex(""""title":"(.*?)","(subtitle|artist)":"(.*?)"""",""")
            for (match in fallbackRegex.findAll(dataStr)) {
                val trackName = match.groupValues[1]
                val artistName = match.groupValues[3].ifBlank { "Unknown" }
                if (trackName.isNotBlank() && tracks.none { it.title == trackName }) {
                    tracks.add(ScrapedTrack(trackName, artistName))
                }
            }
        }

        return Pair(playlistName, tracks)
    }

    private fun scrapeAppleMusicPlaylist(url: String): Pair<String, List<ScrapedTrack>> {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .build()

        val response = client.newCall(request).execute()
        val html = response.body?.string() ?: throw Exception("Failed to fetch Apple Music HTML")

        // Extract the JSON-LD block from <script type="application/ld+json">...</script>
        val ldJsonRegex = Regex("""<script type="application/ld\+json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
        val ldJsonMatch = ldJsonRegex.find(html)
            ?: throw Exception("Could not find ld+json script tag in Apple Music page")
        val ldJsonString = ldJsonMatch.groupValues[1].trim()

        val rootJson = JSONObject(ldJsonString)

        // Extract playlist name from the JSON-LD object, falling back to <title> tag
        val playlistName = rootJson.optString("name", "").ifBlank {
            val titleMatch = Regex("<title>(.*?)</title>").find(html)
            titleMatch?.groupValues?.get(1)?.substringBefore(" - Apple") ?: "Apple Music Playlist"
        }

        val tracks = mutableListOf<ScrapedTrack>()

        // The "track" key holds an array of MusicRecording objects
        val trackArray = rootJson.optJSONArray("track")
        if (trackArray != null) {
            for (i in 0 until trackArray.length()) {
                val trackObj = trackArray.getJSONObject(i)
                val trackName = trackObj.optString("name", "").ifBlank { continue }

                // byArtist can be a single object or an array of objects
                val artistName = try {
                    val byArtist = trackObj.opt("byArtist")
                    when (byArtist) {
                        is org.json.JSONArray -> {
                            if (byArtist.length() > 0) byArtist.getJSONObject(0).optString("name", "") else ""
                        }
                        is org.json.JSONObject -> byArtist.optString("name", "")
                        else -> ""
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Failed to parse byArtist for track: $trackName")
                    ""
                }

                if (tracks.none { it.title == trackName }) {
                    tracks.add(ScrapedTrack(trackName, artistName))
                }
            }
        }

        if (tracks.isEmpty()) {
            Timber.w("ld+json track array was empty or missing, no tracks parsed for: $url")
        }

        return Pair(playlistName, tracks)
    }
}
