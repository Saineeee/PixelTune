package com.theveloper.pixeltune.data.playlist

import com.theveloper.pixeltune.data.database.AlbumEntity
import com.theveloper.pixeltune.data.database.ArtistEntity
import com.theveloper.pixeltune.data.database.MusicDao
import com.theveloper.pixeltune.data.database.SongEntity
import com.theveloper.pixeltune.data.model.CloudPlaylist
import com.theveloper.pixeltune.data.model.CloudStreamProvider
import com.theveloper.pixeltune.data.model.SearchFilterType
import com.theveloper.pixeltune.data.model.SearchResultItem
import com.theveloper.pixeltune.data.model.Song
import com.theveloper.pixeltune.data.preferences.UserPreferencesRepository
import com.theveloper.pixeltune.data.youtube.YouTubeRepository
import com.theveloper.pixeltune.utils.CloudUriUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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

    /**
     * Result of matching a single [ScrapedTrack] against YouTube search results.
     * Used by [previewPlaylist] so the UI can show the user exactly what each
     * scraped track resolved to — letting them remove mis-matches BEFORE the
     * expensive insert-into-DB step (IMPROVE 2: import preview screen).
     */
    data class TrackMatch(
        /** 0-based index in the original scraped track list. */
        val sourceIndex: Int,
        val scrapedTitle: String,
        val scrapedArtist: String,
        /** The YouTube [Song] that best matched, or null if no match was found. */
        val matchedSong: Song?,
        /** Why this match was picked — shown to the user as a hint chip. */
        val matchReason: String,
        /** Whether the user has selected / deselected this match for import. */
        var isSelected: Boolean = matchedSong != null
    )

    /**
     * Phase 1 of the import flow: scrape the source URL, search YouTube for
     * each track, and return a list of [TrackMatch]es WITHOUT persisting
     * anything to the DB. The caller (UI) presents this list to the user, who
     * can deselect unwanted matches before calling [commitPreview].
     */
    suspend fun previewPlaylist(url: String): Result<Pair<String, List<TrackMatch>>> = withContext(Dispatchers.IO) {
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

            val matches = tracks.mapIndexed { index, track ->
                resolveTrackToMatch(index, track)
            }

            Result.success(Pair(playlistName, matches))
        } catch (e: Exception) {
            Timber.e(e, "Playlist preview failed for url: $url")
            Result.failure(e)
        }
    }

    /**
     * Phase 2 of the import flow: persist the user-curated subset of the
     * preview matches to the DB and create a playlist. Called by the import
     * preview screen after the user has deselected any unwanted matches.
     */
    suspend fun commitPreview(
        playlistName: String,
        matches: List<TrackMatch>
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val selected = matches.filter { it.isSelected && it.matchedSong != null }
            if (selected.isEmpty()) {
                throw Exception("No tracks selected for import.")
            }

            val songEntities = mutableListOf<SongEntity>()
            for (match in selected) {
                val song = match.matchedSong ?: continue
                val proxyUrl = normalizeCloudUriForStorage(song.contentUriString)
                val songId = com.theveloper.pixeltune.data.repository.MusicRepositoryImpl
                    .stableLongIdFromString(song.youtubeId ?: song.id)

                val entity = SongEntity(
                    id = songId,
                    title = song.title,
                    artistName = song.artist,
                    artistId = song.artistId,
                    albumName = song.album,
                    albumId = song.albumId,
                    contentUriString = proxyUrl,
                    albumArtUriString = song.albumArtUriString,
                    duration = song.duration,
                    genre = song.genre,
                    filePath = proxyUrl,
                    parentDirectoryPath = "imported_playlists",
                    isFavorite = false,
                    dateAdded = System.currentTimeMillis()
                )
                if (songEntities.none { it.id == songId }) {
                    songEntities.add(entity)
                }
            }

            if (songEntities.isEmpty()) {
                throw Exception("Could not match any tracks to playable streams.")
            }

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
            musicDao.insertSongsIgnoreConflicts(songEntities)

            val newSongIds = songEntities.map { it.id.toString() }
            val finalName = playlistName.ifBlank { "Imported Playlist" }
            userPreferencesRepository.createPlaylist(finalName, newSongIds)

            Result.success("Imported $finalName with ${newSongIds.size} matching tracks!")
        } catch (e: Exception) {
            Timber.e(e, "Playlist commit failed for playlistName: $playlistName")
            Result.failure(e)
        }
    }

    /**
     * Legacy single-shot import entry point — kept for callers that don't
     * want a preview step. Equivalent to calling [previewPlaylist] followed
     * by [commitPreview] with all matches selected.
     *
     * New callers should prefer the preview → commit two-phase flow so the
     * user can review and deselect mis-matched tracks (see IMPROVE 2 in the
     * product spec).
     */
    suspend fun importPlaylist(url: String): Result<String> = withContext(Dispatchers.IO) {
        val previewResult = previewPlaylist(url)
        if (previewResult.isFailure) {
            return@withContext Result.failure(previewResult.exceptionOrNull() ?: Exception("Preview failed"))
        }
        val (playlistName, matches) = previewResult.getOrNull()!!
        // Auto-select only the matches that found a song (the deselection of
        // no-match entries is already done inside [resolveTrackToMatch]).
        val selected = matches.map { it.copy(isSelected = it.matchedSong != null) }
        commitPreview(playlistName, selected)
    }

    /**
     * IMPROVE(cloud-playlist-import): outcome of a successful
     * [importCloudPlaylist] call — the library playlist id it lives under,
     * how many playable tracks were stored, and whether an earlier import of
     * the same cloud playlist was refreshed in place (re-tapping "Add to
     * library" never duplicates the playlist).
     */
    data class CloudPlaylistImportResult(
        val playlistId: String,
        val trackCount: Int,
        val wasUpdated: Boolean
    )

    /**
     * IMPROVE(cloud-playlist-import): imports a playlist found through the
     * ONLINE search (YouTube Music / SoundCloud) into the app's Library
     * "Playlists" tab so it can be opened, played, shuffled and managed
     * exactly like a local playlist — the "add to your playlist" button on
     * both the search result rows and the cloud playlist detail screen.
     *
     * Mirrors the proven persistence recipe of [commitPreview] (the M3U /
     * Spotify / Apple Music import path):
     *  - every track becomes a `SongEntity` row with the stable Long id
     *    derived from its cloud id, its restart-safe scheme URI
     *    (`youtube://…` / `soundcloud://…`, via
     *    [CloudUriUtils.normalizeCloudUriForStorage]) and the
     *    "imported_playlists" pseudo-folder, so playback works across app
     *    restarts through the service's scheme-URI translation;
     *  - artist / album rows are upserted (synthetic ids from names so every
     *    imported artist gets its own row instead of collapsing into one);
     *  - the playlist itself is stored with a DETERMINISTIC custom id
     *    ("cloudimport_<provider>_<cloud id>") — importing the same cloud
     *    playlist again refreshes its songs in place instead of duplicating
     *    it — and a `source` tag ("YOUTUBE" / "SOUNDCLOUD") the Library
     *    playlist rows and detail screen display as the provider badge.
     *
     * The provider's remote artwork URL is kept as the cover (PlaylistCover
     * renders it through Coil's AsyncImage, which loads http(s) models).
     *
     * [songs] must already carry the scheme form or the live proxy URLs of
     * the current session — both are normalized here. The caller fetches all
     * pages of the playlist (see PlaylistViewModel.importCloudPlaylistToLibrary).
     */
    suspend fun importCloudPlaylist(
        playlist: CloudPlaylist,
        songs: List<Song>
    ): Result<CloudPlaylistImportResult> = withContext(Dispatchers.IO) {
        try {
            val usableSongs = songs.filter { song ->
                song.id.isNotBlank() && song.contentUriString.isNotBlank()
            }
            if (usableSongs.isEmpty()) {
                throw Exception("This playlist has no playable tracks.")
            }

            val sourceLabel = if (playlist.provider == CloudStreamProvider.YOUTUBE) {
                CLOUD_IMPORT_SOURCE_YOUTUBE
            } else {
                CLOUD_IMPORT_SOURCE_SOUNDCLOUD
            }
            val customId = cloudImportCustomId(playlist)
            val importedAt = System.currentTimeMillis()

            val songEntities = usableSongs
                .map { song ->
                    val storageUri = CloudUriUtils.normalizeCloudUriForStorage(song.contentUriString)
                    val artistName = song.artist.ifBlank { "Unknown Artist" }
                    val albumName = song.album.ifBlank { playlist.name }
                    SongEntity(
                        id = CloudUriUtils.stableLongIdFromString(song.youtubeId ?: song.id),
                        title = song.title,
                        artistName = artistName,
                        artistId = CloudUriUtils.stableSyntheticIdFromName(artistName),
                        albumName = albumName,
                        albumId = CloudUriUtils.stableSyntheticIdFromName(albumName),
                        contentUriString = storageUri,
                        albumArtUriString = song.albumArtUriString,
                        duration = song.duration,
                        genre = song.genre,
                        filePath = storageUri,
                        parentDirectoryPath = CLOUD_IMPORT_PARENT_DIRECTORY,
                        isFavorite = false,
                        dateAdded = importedAt,
                        mimeType = song.mimeType
                    )
                }
                .distinctBy { it.id }

            if (songEntities.isEmpty()) {
                throw Exception("Could not persist any track of this playlist.")
            }

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
            musicDao.insertSongsIgnoreConflicts(songEntities)

            val songIds = songEntities.map { it.id.toString() }
            val existing = userPreferencesRepository.userPlaylistsFlow.first()
                .firstOrNull { it.id == customId }

            if (existing != null) {
                // Refresh-in-place: same cloud playlist imported again — keep
                // the library entry (and its id, so any pins/references stay
                // valid) and swap its contents for the freshly fetched tracks.
                userPreferencesRepository.updatePlaylist(
                    existing.copy(
                        name = playlist.name.ifBlank { existing.name },
                        songIds = songIds,
                        coverImageUri = existing.coverImageUri ?: playlist.artworkUrl,
                        lastModified = System.currentTimeMillis()
                    )
                )
                Result.success(
                    CloudPlaylistImportResult(existing.id, songIds.size, wasUpdated = true)
                )
            } else {
                val created = userPreferencesRepository.createPlaylist(
                    name = playlist.name.ifBlank { "Imported Playlist" },
                    songIds = songIds,
                    coverImageUri = playlist.artworkUrl,
                    customId = customId,
                    source = sourceLabel
                )
                Result.success(
                    CloudPlaylistImportResult(created.id, songIds.size, wasUpdated = false)
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Cloud playlist import failed for %s", playlist.url)
            Result.failure(e)
        }
    }

    /**
     * Picks the best YouTube search result for a single scraped track.
     *
     * The previous implementation blindly took `results.firstOrNull()`, which
     * often returned a "Topic" channel auto-generated cover, a remix, or a
     * lyrics video — producing the "gibberish titles / artist" symptom the
     * user reported (e.g. uploaderName "- Topic" → stored as artistName).
     *
     * The new implementation:
     *  1. Searches YouTube with `"${title} ${artist}"` (filtered to SONGS).
     *  2. For every result, computes a similarity score using a normalized
     *     Jaccard token-overlap on title + artist, plus a small bonus if the
     *     artist name appears in the uploader name (covers "Official Artist
     *     Channel" matches).
     *  3. Rejects any result whose similarity score is below a conservative
     *     threshold — preferring "no match" over a wrong match.
     *  4. Cleans up the matched song's title / artist fields:
     *     - strips "- Topic", "Official Audio", "(Lyrics)" suffixes that
     *       NewPipe returns as part of uploaderName / item.name.
     *     - falls back to the *original* scraped title / artist when the
     *       YouTube result's fields are clearly worse (e.g. "Various Artists"
     *       or empty).
     */
    private suspend fun resolveTrackToMatch(
        sourceIndex: Int,
        track: ScrapedTrack
    ): TrackMatch {
        val query = "${track.title} ${track.artist}".trim()
        if (query.isBlank()) {
            return TrackMatch(
                sourceIndex = sourceIndex,
                scrapedTitle = track.title,
                scrapedArtist = track.artist,
                matchedSong = null,
                matchReason = "Empty query"
            )
        }

        val results = runCatching {
            youtubeRepository.searchYouTube(query, SearchFilterType.SONGS) { id -> "youtube://$id" }
        }.getOrDefault(emptyList())

        val songItems = results.filterIsInstance<SearchResultItem.SongItem>()
        if (songItems.isEmpty()) {
            return TrackMatch(
                sourceIndex = sourceIndex,
                scrapedTitle = track.title,
                scrapedArtist = track.artist,
                matchedSong = null,
                matchReason = "No YouTube results"
            )
        }

        val targetTitleTokens = normalizeForMatching(track.title)
        val targetArtistTokens = normalizeForMatching(track.artist)

        val scored = songItems.map { item ->
            val candidateTitleTokens = normalizeForMatching(item.song.title)
            val candidateArtistTokens = normalizeForMatching(item.song.artist)

            val titleScore = jaccardSimilarity(targetTitleTokens, candidateTitleTokens)
            val artistScore = jaccardSimilarity(targetArtistTokens, candidateArtistTokens)

            // Bonus: scraped artist appears in the YouTube uploader name (e.g.
            // official artist channel) → strong signal that this is the right
            // track even if the title has minor formatting differences.
            val uploaderContainsArtist = targetArtistTokens.isNotEmpty() &&
                targetArtistTokens.any { it.length >= 3 && it in candidateArtistTokens }

            val combinedScore = (titleScore * 0.7f) + (artistScore * 0.3f) +
                (if (uploaderContainsArtist) 0.1f else 0f)

            Triple(item, combinedScore, uploaderContainsArtist)
        }.sortedByDescending { it.second }

        val (bestItem, bestScore, bestUploaderMatch) = scored.first()

        if (bestScore < MIN_MATCH_SCORE) {
            return TrackMatch(
                sourceIndex = sourceIndex,
                scrapedTitle = track.title,
                scrapedArtist = track.artist,
                matchedSong = null,
                matchReason = "Best score %.2f below threshold".format(bestScore)
            )
        }

        // Clean up the matched song's metadata so the user sees the original
        // track title / artist (from Spotify/Apple Music), not NewPipe's
        // uploaderName which frequently contains "- Topic" or similar noise.
        val cleanedTitle = pickBetterTitle(
            original = track.title,
            candidate = bestItem.song.title
        )
        val cleanedArtist = pickBetterArtist(
            original = track.artist,
            candidate = bestItem.song.artist
        )

        val reason = if (bestUploaderMatch) {
            "Official channel match (score %.2f)".format(bestScore)
        } else {
            "Title match (score %.2f)".format(bestScore)
        }

        val cleanedSong = bestItem.song.copy(
            title = cleanedTitle,
            artist = cleanedArtist
        )

        return TrackMatch(
            sourceIndex = sourceIndex,
            scrapedTitle = track.title,
            scrapedArtist = track.artist,
            matchedSong = cleanedSong,
            matchReason = reason
        )
    }

    private fun normalizeForMatching(text: String): Set<String> {
        if (text.isBlank()) return emptySet()
        // Lowercase, strip common noise tokens, split on non-alphanumeric.
        val cleaned = text.lowercase()
            .replace(Regex("[\\p{Punct}]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.isEmpty()) return emptySet()
        return cleaned.split(" ")
            .filter { it.isNotBlank() && it !in NOISE_TOKENS && it.length >= 2 }
            .toSet()
    }

    private fun jaccardSimilarity(a: Set<String>, b: Set<String>): Float {
        if (a.isEmpty() && b.isEmpty()) return 0f
        if (a.isEmpty() || b.isEmpty()) return 0f
        val intersection = a.intersect(b).size.toFloat()
        val union = a.union(b).size.toFloat()
        return if (union == 0f) 0f else intersection / union
    }

    /**
     * Pick the better of the scraped title vs. the YouTube result title.
     * We prefer the scraped (Spotify/Apple) title when they're close, because
     * the scraped source is canonical — YouTube titles often have
     * "(Official Audio)", "- Topic", etc. suffixed.
     */
    private fun pickBetterTitle(original: String, candidate: String): String {
        if (original.isBlank()) return candidate
        if (candidate.isBlank()) return original
        // Strip noise suffixes from the YouTube title first.
        val cleanedCandidate = stripTitleNoise(candidate)
        // If the cleaned candidate still differs significantly from the
        // original, prefer the original (canonical).
        val tokens1 = normalizeForMatching(original)
        val tokens2 = normalizeForMatching(cleanedCandidate)
        val sim = jaccardSimilarity(tokens1, tokens2)
        return if (sim >= 0.4f || original.length <= cleanedCandidate.length + 6) original
        else cleanedCandidate
    }

    private fun pickBetterArtist(original: String, candidate: String): String {
        if (original.isBlank()) return candidate.ifBlank { "Unknown Artist" }
        if (candidate.isBlank()) return original
        val cleanedCandidate = stripArtistNoise(candidate)
        return if (normalizeForMatching(original).intersect(normalizeForMatching(cleanedCandidate)).isNotEmpty()) {
            original
        } else cleanedCandidate.ifBlank { original }
    }

    private fun stripTitleNoise(title: String): String {
        var t = title.trim()
        // Remove trailing "- Topic" (NewPipe's auto-generated music channel suffix).
        t = t.replace(Regex("\\s*-?\\s*Topic\\s*$", RegexOption.IGNORE_CASE), "")
        // Remove "(Official Audio)", "(Official Video)", "(Lyrics)", "[Audio]" etc.
        t = t.replace(Regex("\\s*[\\[\\(](?:official\\s+(?:audio|video|music\\s+video|visualizer)|lyrics?|audio|hd|hq)[\\]\\)]", RegexOption.IGNORE_CASE), "")
        // Collapse whitespace.
        return t.trim().replace(Regex("\\s+"), " ")
    }

    private fun stripArtistNoise(artist: String): String {
        var a = artist.trim()
        a = a.replace(Regex("\\s*-?\\s*Topic\\s*$", RegexOption.IGNORE_CASE), "")
        return a.trim().replace(Regex("\\s+"), " ")
    }

    /**
     * Persistence-time URI normalizer — same logic as
     * [com.theveloper.pixeltune.data.repository.MusicRepositoryImpl.normalizeCloudUriForStorage],
     * duplicated locally because that function is internal to the repository
     * module. Converts the current session's YouTube proxy URL
     * (`http://127.0.0.1:<port>/youtube/<id>`) to the restart-safe
     * `youtube://<id>` scheme form so the persisted SongEntity doesn't break
     * after the proxy rebinds to a different port on next launch.
     */
    internal fun normalizeCloudUriForStorage(contentUriString: String): String {
        if (contentUriString.isEmpty()) return contentUriString
        val knownSchemes = setOf(
            "youtube", "soundcloud", "telegram", "netease", "gdrive",
            "content", "file"
        )
        val parsed = runCatching { android.net.Uri.parse(contentUriString) }.getOrNull()
            ?: return contentUriString
        val scheme = parsed.scheme?.lowercase()
        if (scheme != null && scheme in knownSchemes) return contentUriString
        if (scheme == "http" || scheme == "https") {
            val host = parsed.host?.lowercase()
            if (host == "127.0.0.1" || host == "localhost") {
                val pathSegments = parsed.pathSegments
                if (pathSegments.size >= 2) {
                    val provider = pathSegments[0].lowercase()
                    val payload = pathSegments.subList(1, pathSegments.size)
                        .joinToString("/") { it }
                    return when (provider) {
                        "youtube" -> "youtube://$payload"
                        "soundcloud" -> "soundcloud://$payload"
                        else -> contentUriString
                    }
                }
            }
        }
        return contentUriString
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
            val fallbackRegex = Regex("\"title\":\"(.*?)\",\"(subtitle|artist)\":\"(.*?)\"")
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
        val ldJsonRegex = Regex("<script type=\"application/ld\\+json\">(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
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
                val trackName = trackObj.optString("name", "")
                if (trackName.isBlank()) continue

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

    companion object {
        /**
         * Prefix of the deterministic custom id every cloud-imported playlist
         * gets (see [cloudImportCustomId]) — recognizable in the playlist id
         * itself, so the "already imported" state can be rebuilt after an app
         * restart without extra bookkeeping.
         */
        const val CLOUD_IMPORT_CUSTOM_ID_PREFIX = "cloudimport_"

        /**
         * [Playlist.source] tag for playlists imported from the YouTube Music
         * online search (drives the provider badge in the Library and the
         * Local/Cloud filter of the playlists sort sheet).
         */
        const val CLOUD_IMPORT_SOURCE_YOUTUBE = "YOUTUBE"

        /** [Playlist.source] tag for SoundCloud-imported playlists. */
        const val CLOUD_IMPORT_SOURCE_SOUNDCLOUD = "SOUNDCLOUD"

        /**
         * Stable identity of a cloud playlist across imports — the same cloud
         * playlist always maps to the same key, regardless of which surface
         * (search result row / detail screen) triggered the import.
         */
        fun cloudImportKey(playlist: CloudPlaylist): String {
            val provider = if (playlist.provider == CloudStreamProvider.YOUTUBE) "youtube" else "soundcloud"
            return "${provider}_${playlist.id}"
        }

        /** Deterministic library custom id for a cloud playlist import. */
        fun cloudImportCustomId(playlist: CloudPlaylist): String =
            CLOUD_IMPORT_CUSTOM_ID_PREFIX + cloudImportKey(playlist)

        /**
         * Pseudo parent directory applied to imported cloud tracks — the SAME
         * marker [commitPreview] uses, so cloud-imported and M3U-imported
         * songs behave identically in every library surface.
         */
        const val CLOUD_IMPORT_PARENT_DIRECTORY = "imported_playlists"

        /**
         * Minimum combined Jaccard similarity score for a YouTube search result
         * to be considered a "match" for a scraped Spotify/Apple track. Below
         * this threshold, [resolveTrackToMatch] prefers reporting "no match"
         * over a wrong match — the user can still see the scraped row in the
         * preview screen and either deselect it or accept that no streamable
         * equivalent was found.
         *
         * 0.35 is conservative: it requires ~35% token overlap between the
         * scraped "{title} {artist}" query and the YouTube result's
         * "{title} {uploaderName}" — enough to reject obvious mis-matches
         * (remixes, lyric videos, covers) while still accepting legitimate
         * matches where one side has slightly different formatting.
         */
        private const val MIN_MATCH_SCORE = 0.35f

        /**
         * Tokens that are too generic to discriminate between tracks — they
         * appear in nearly every pop/hip-hop track title and so contribute
         * almost no information to the Jaccard similarity. Dropping them
         * prevents "feat" / "official" / "audio" from inflating the score.
         */
        private val NOISE_TOKENS = setOf(
            "official", "audio", "video", "lyrics", "lyric", "hd", "hq",
            "feat", "ft", "the", "and", "of", "a", "an",
            "remastered", "remaster", "version", "edit",
            "topic", "music", "song", "single", "album"
        )
    }
}
