package com.theveloper.pixeltune.data.playlist

import com.theveloper.pixeltune.data.database.AlbumEntity
import com.theveloper.pixeltune.data.database.ArtistEntity
import com.theveloper.pixeltune.data.database.MusicDao
import com.theveloper.pixeltune.data.database.SongEntity
import com.theveloper.pixeltune.data.model.CloudPlaylist
import com.theveloper.pixeltune.data.model.CloudStreamProvider
import com.theveloper.pixeltune.data.model.CloudTracksPage
import com.theveloper.pixeltune.data.model.Song
import com.theveloper.pixeltune.data.preferences.UserPreferencesRepository
import com.theveloper.pixeltune.data.soundcloud.SoundCloudRepository
import com.theveloper.pixeltune.data.youtube.YouTubeRepository
import com.theveloper.pixeltune.utils.CloudUriUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * IMPROVE(cloud-playlist-import): imports an ONLINE-search playlist (YouTube
 * Music / SoundCloud — a [CloudPlaylist] from the search results or the cloud
 * catalog detail screen) into the app's own library Playlists tab.
 *
 * The flow, triggered by the new "Add to your playlist" buttons:
 *
 *  1. **Extract every track** of the playlist through the SAME repository
 *     pagination the cloud catalog detail screen uses
 *     ([YouTubeRepository.getCloudPlaylistTracks] /
 *     [SoundCloudRepository.getCloudPlaylistTracks] + their "load more"
 *     overloads), capped at [MAX_IMPORT_TRACKS] tracks.
 *  2. **Persist each track as a songs-table row** so the playlist's songIds
 *     resolve like any local playlist's — in the RESTART-SAFE scheme-URI form
 *     (`youtube://<videoId>` / `soundcloud://<encoded>`), exactly the form
 *     favoriting a cloud song persists (see
 *     [MusicRepositoryImpl.ensureCloudSongRow] and
 *     [CloudUriUtils.normalizeCloudUriForStorage]). DualPlayerEngine resolves
 *     those URIs back to the CURRENT session's stream proxy at play time, so
 *     imported playlists keep playing across app restarts, and the MediaStore
 *     sync's deletion phase explicitly excludes scheme-URI rows.
 *  3. **Create the library playlist** with a deterministic `customId`
 *     (`cloud_youtube_<urlHash>` / `cloud_soundcloud_<urlHash>`) — re-tapping
 *     "Add" on an already-imported playlist is detected instead of creating a
 *     duplicate — a `source` tag ("YOUTUBE" / "SOUNDCLOUD") the Library's
 *     playlist rows badge and filter on, and the provider's own playlist
 *     artwork as the cover.
 *
 * Artist/album placeholder rows follow the same conventions
 * [MusicRepositoryImpl.ensureCloudSongRow] uses: artists are merged with an
 * existing local artist by NAME when possible (else a synthetic negative id
 * that can never collide with MediaStore ids), and all tracks of one imported
 * playlist are grouped under a single album named after the playlist so the
 * Library stays tidy.
 */
@Singleton
class CloudPlaylistImportManager @Inject constructor(
    private val youTubeRepository: YouTubeRepository,
    private val soundCloudRepository: SoundCloudRepository,
    private val musicDao: MusicDao,
    private val userPreferencesRepository: UserPreferencesRepository
) {

    /** Outcome of an import attempt — drives the UI's confirmation / error toast. */
    sealed class ImportOutcome {
        data class Success(val playlistName: String, val trackCount: Int) : ImportOutcome()
        data class AlreadyImported(val playlistName: String) : ImportOutcome()
        data class Failure(val message: String) : ImportOutcome()
    }

    companion object {
        /** Deterministic id prefix of a library playlist created from a cloud playlist. */
        const val CLOUD_PLAYLIST_ID_PREFIX = "cloud_"

        /** [Playlist.source] tags for cloud-imported playlists. */
        const val SOURCE_YOUTUBE = "YOUTUBE"
        const val SOURCE_SOUNDCLOUD = "SOUNDCLOUD"

        /**
         * Hard cap on imported tracks — protects against pathological
         * playlists (thousands of continuations) turning one tap into an
         * unbounded extraction storm.
         */
        const val MAX_IMPORT_TRACKS = 300

        /** The deterministic library id of a cloud playlist (dedupe key). */
        fun playlistIdFor(playlist: CloudPlaylist): String {
            val providerKey = if (playlist.provider == CloudStreamProvider.YOUTUBE) "youtube" else "soundcloud"
            val urlHash = runCatching { Math.abs(playlist.url.hashCode().toLong()) }.getOrDefault(0L)
            return "$CLOUD_PLAYLIST_ID_PREFIX${providerKey}_$urlHash"
        }

        /** The [Playlist.source] tag for a cloud playlist's provider. */
        fun sourceTagFor(provider: CloudStreamProvider): String =
            if (provider == CloudStreamProvider.YOUTUBE) SOURCE_YOUTUBE else SOURCE_SOUNDCLOUD
    }

    suspend fun importCloudPlaylist(playlist: CloudPlaylist): ImportOutcome = withContext(Dispatchers.IO) {
        val customId = playlistIdFor(playlist)

        // 0. Duplicate check — the deterministic customId makes re-imports a
        // no-op with a friendly message instead of a duplicated playlist.
        val existing = runCatching {
            userPreferencesRepository.userPlaylistsFlow.first().find { it.id == customId }
        }.getOrNull()
        if (existing != null) {
            return@withContext ImportOutcome.AlreadyImported(existing.name)
        }

        // 1. Extract the playlist's tracks (ALL pages, capped).
        val songs = runCatching { fetchAllPlaylistTracks(playlist) }
            .onFailure { e ->
                Timber.e(e, "Cloud playlist track extraction failed for %s", playlist.url)
            }
            .getOrDefault(emptyList())
        if (songs.isEmpty()) {
            return@withContext ImportOutcome.Failure(
                "No playable tracks found for \"${playlist.name}\". Check your connection and try again."
            )
        }

        // 2. Persist the songs (idempotent) + placeholder artist/album rows.
        val songLongIds = runCatching { persistCloudSongs(songs, playlist) }
            .onFailure { e ->
                Timber.e(e, "Cloud playlist song persistence failed for %s", playlist.url)
            }
            .getOrDefault(emptyList())
        if (songLongIds.isEmpty()) {
            return@withContext ImportOutcome.Failure("Could not save \"${playlist.name}\" to your library.")
        }

        // 3. Create the library playlist.
        val playlistName = playlist.name.ifBlank { "Imported Playlist" }
        return@withContext try {
            userPreferencesRepository.createPlaylist(
                name = playlistName,
                songIds = songLongIds.map { it.toString() },
                coverImageUri = playlist.artworkUrl,
                source = sourceTagFor(playlist.provider),
                customId = customId
            )
            Timber.i(
                "Imported cloud playlist '%s' (%s) with %d tracks",
                playlistName, playlist.provider, songLongIds.size
            )
            ImportOutcome.Success(playlistName, songLongIds.size)
        } catch (e: Exception) {
            Timber.e(e, "Cloud playlist creation failed for %s", playlist.url)
            ImportOutcome.Failure("Could not add \"${playlistName}\" to your library.")
        }
    }

    /**
     * IMPROVE(cloud-playlist-import): extracts ALL pages of the playlist's
     * tracks through the repositories' own pagination (the exact path the
     * cloud catalog detail screen uses), stopping at [MAX_IMPORT_TRACKS].
     *
     * The `proxyUrlProvider` handed to the repositories builds the
     * RESTART-SAFE scheme URI directly (`youtube://<id>` /
     * `soundcloud://<encoded>`) — the form persisted for liked cloud songs —
     * so no dependency on this session's (possibly not-yet-started) stream
     * proxy, and the rows are immediately replay-safe.
     */
    private suspend fun fetchAllPlaylistTracks(playlist: CloudPlaylist): List<Song> {
        val firstPage: Result<CloudTracksPage> =
            if (playlist.provider == CloudStreamProvider.YOUTUBE) {
                youTubeRepository.getCloudPlaylistTracks(playlist) { videoId ->
                    "youtube://$videoId"
                }
            } else {
                soundCloudRepository.getCloudPlaylistTracks(playlist) { encodedUrl ->
                    "soundcloud://$encodedUrl"
                }
            }

        var page = firstPage.getOrElse { return emptyList() }
        val allSongs = page.songs.toMutableList()

        // Follow the provider's own pagination until the cap.
        while (allSongs.size < MAX_IMPORT_TRACKS && page.hasMore) {
            val next: Result<CloudTracksPage> =
                if (playlist.provider == CloudStreamProvider.YOUTUBE) {
                    youTubeRepository.getMoreCloudPlaylistTracks(playlist, page) { videoId ->
                        "youtube://$videoId"
                    }
                } else {
                    soundCloudRepository.getMoreCloudPlaylistTracks(playlist, page) { encodedUrl ->
                        "soundcloud://$encodedUrl"
                    }
                }
            // Kotlin 2.1: `getOrElse { break }` (break inside an inline lambda)
            // is a 2.2 feature — resolve the nullable value instead and break
            // from the loop body itself.
            page = next.getOrNull() ?: break
            if (page.songs.isEmpty()) break
            // Dedupe by song id across pages (providers repeat boundary items).
            val seen = allSongs.mapTo(HashSet(allSongs.size)) { it.id }
            allSongs += page.songs.filter { seen.add(it.id) }
        }

        // Normalize any non-scheme URI into the storage form (defensive — the
        // providers above already produce scheme URIs).
        return allSongs
            .take(MAX_IMPORT_TRACKS)
            .map { song ->
                if (song.contentUriString.isNotBlank()) {
                    song.copy(
                        contentUriString = CloudUriUtils.normalizeCloudUriForStorage(
                            song.contentUriString
                        )
                    )
                } else {
                    song
                }
            }
    }

    /**
     * Persists the playlist's [songs] as songs-table rows and returns the Long
     * row ids (the values stored in the playlist's songIds). Idempotent:
     * IGNORE-conflict inserts make re-imports (or overlaps with already-liked
     * cloud songs) safe.
     */
    private suspend fun persistCloudSongs(songs: List<Song>, playlist: CloudPlaylist): List<Long> {
        if (songs.isEmpty()) return emptyList()

        val albumName = playlist.name.ifBlank { "Online Favorites" }
        val albumId = CloudUriUtils.stableSyntheticIdFromName("${albumName}_cloud_playlist")

        // Resolve/insert placeholder ARTIST rows (merge with an existing local
        // artist by name, else a synthetic negative id) — the same convention
        // MusicRepositoryImpl.ensureCloudSongRow uses for liked cloud songs.
        val artistNames = songs.map { it.artist.trim().ifBlank { "Unknown Artist" } }.distinct()
        val existingArtists = runCatching { musicDao.getAllArtistsListRaw() }.getOrDefault(emptyList())
        val artistIdByName = HashMap<String, Long>(artistNames.size)
        val newArtists = mutableListOf<ArtistEntity>()
        for (name in artistNames) {
            val existing = existingArtists.firstOrNull {
                it.name.trim().equals(name, ignoreCase = true)
            }
            val id = existing?.id ?: CloudUriUtils.stableSyntheticIdFromName(name)
            if (existing == null && !artistIdByName.containsKey(name)) {
                newArtists += ArtistEntity(id = id, name = name, trackCount = 0)
            }
            artistIdByName[name] = id
        }
        if (newArtists.isNotEmpty()) {
            runCatching { musicDao.insertArtistsIgnoreConflicts(newArtists.distinctBy { it.id }) }
        }

        // One placeholder ALBUM row groups all tracks of this playlist.
        runCatching {
            musicDao.insertAlbumsIgnoreConflicts(
                listOf(
                    AlbumEntity(
                        id = albumId,
                        title = albumName,
                        artistName = playlist.uploaderName?.takeIf { it.isNotBlank() } ?: "Online",
                        artistId = CloudUriUtils.stableSyntheticIdFromName(
                            playlist.uploaderName?.takeIf { it.isNotBlank() } ?: "Online"
                        ),
                        albumArtUriString = playlist.artworkUrl,
                        songCount = 0,
                        year = 0
                    )
                )
            )
        }

        val entities = songs.mapIndexedNotNull { _, song ->
            val longId = CloudUriUtils.stableLongIdFromString(song.id)
            val artistName = song.artist.trim().ifBlank { "Unknown Artist" }
            SongEntity(
                id = longId,
                title = song.title.ifBlank { "Unknown" },
                artistName = artistName,
                artistId = artistIdByName[artistName]
                    ?: CloudUriUtils.stableSyntheticIdFromName(artistName),
                albumName = albumName,
                albumId = albumId,
                contentUriString = song.contentUriString,
                albumArtUriString = song.albumArtUriString,
                duration = song.duration,
                genre = song.genre,
                filePath = song.contentUriString,
                parentDirectoryPath = "imported_playlists",
                isFavorite = false,
                dateAdded = System.currentTimeMillis()
            )
        }.distinctBy { it.id }

        runCatching { musicDao.insertSongsIgnoreConflicts(entities) }
            .onFailure { e ->
                Timber.e(e, "Failed to persist cloud playlist song rows for %s", playlist.url)
                throw e
            }

        return entities.map { it.id }
    }
}
