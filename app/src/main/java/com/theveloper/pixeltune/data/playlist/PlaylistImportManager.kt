package com.theveloper.pixeltune.data.playlist

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

            // Database Insertion: isOffline conceptually means path points to web, mapped correctly above
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
        val extractor = ServiceList.YouTube.getPlaylistExtractor(url)
        extractor.fetchPage()
        val playlistName = extractor.name ?: "YouTube Playlist"
        val tracks = extractor.initialPage.items.map { item ->
            // For StreamInfoItem
            val title = item.name ?: "Unknown Track"
            // Uploader name usually acts as artist
            val artist = "" // Extractor doesn't strictly provide uploader name elegantly on initialPage items without casting, we simplify
            ScrapedTrack(title, artist)
        }
        return Pair(playlistName, tracks)
    }

    private fun scrapeSpotifyPlaylist(url: String): Pair<String, List<ScrapedTrack>> {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .build()
            
        val response = client.newCall(request).execute()
        val html = response.body?.string() ?: throw Exception("Failed to fetch Spotify HTML")
        
        // Extract title
        val titleMatch = Regex("<title>(.*?)</title>").find(html)
        var playlistName = titleMatch?.groupValues?.get(1)?.replace(" | Spotify", "")?.substringBefore(" - playlist by") ?: "Spotify Playlist"
        
        // Very basic extraction by finding tracks in OpenGraph or embedded JSON
        // A naive regex to sniff track names from the raw HTML chunks
        val trackRegex = Regex("\"name\":\"(.*?)\",\"type\":\"track\".*?\"artists\":\\[(?:\\{.*?\"name\":\"(.*?)\".*?\\})?")
        val tracks = mutableListOf<ScrapedTrack>()
        val matches = trackRegex.findAll(html)
        for (match in matches) {
            val trackName = match.groupValues.getOrNull(1) ?: continue
            val artistName = match.groupValues.getOrNull(2) ?: "Unknown"
            // Skip duplicates which often appear in the JSON bloat
            if (tracks.none { it.title == trackName }) {
                tracks.add(ScrapedTrack(trackName, artistName))
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
        
        // Extract title
        val titleMatch = Regex("<title>(.*?)</title>").find(html)
        var playlistName = titleMatch?.groupValues?.get(1)?.substringBefore(" - Apple") ?: "Apple Music Playlist"
        
        val tracks = mutableListOf<ScrapedTrack>()
        
        // Simple regex strategy for apple music embedded JSON block (schema.org/MusicPlaylist)
        val trackRegex = Regex("\"@type\":\"MusicRecording\",\"url\":\"[^\"]+\",\"name\":\"([^\"]+)\"")
        val matches = trackRegex.findAll(html)
        for (match in matches) {
            val trackName = match.groupValues.getOrNull(1) ?: continue
            // We could find artist dynamically but it's hard with regex on schema, using empty string to rely on youtube's track search
            if (tracks.none { it.title == trackName }) {
                tracks.add(ScrapedTrack(trackName, ""))
            }
        }
        
        return Pair(playlistName, tracks)
    }
}
