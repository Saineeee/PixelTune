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
import com.theveloper.pixeltune.data.soundcloud.SoundCloudStreamProxy
import com.theveloper.pixeltune.data.youtube.YouTubeRepository
import com.theveloper.pixeltune.data.youtube.YouTubeStreamProxy
import com.theveloper.pixeltune.utils.CloudUriUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * IMPROVE(add-cloud-playlist-to-library): imports an ONLINE-search playlist
 * (YouTube / YouTube Music / SoundCloud) into the app's own library so the
 * user can keep, replay and manage it from the Library's Playlists tab.
 *
 * The flow mirrors the app's established cross-service import
 * ([PlaylistImportManager]):
 *  1. fetches EVERY page of the playlist's tracks through the same
 *     repositories + stream proxies the online search / CloudCatalog screen
 *     use (capped by [MAX_IMPORT_TRACKS] / [MAX_IMPORT_PAGES] so a runaway
 *     playlist can't import forever);
 *  2. persists the tracks as [SongEntity] rows in Room with their
 *     restart-safe cloud scheme URIs (`youtube://<id>` / `soundcloud://<id>`)
 *     — exactly the storage convention the URL-import flow, the favorites
 *     and the offline downloads already use, so every imported track is
 *     immediately playable from the library, survives restarts (the proxy
 *     port changes between launches; the scheme URI is re-resolved at
 *     playback time by DualPlayerEngine) and behaves like any other song;
 *  3. creates (or refreshes, on re-import) a DataStore playlist tagged with
 *     the provider ([source] = "YOUTUBE" / "SOUNDCLOUD") so the Playlists tab
 *     can badge it and the new source filter can find it.
 *
 * Re-importing the same playlist is idempotent: a stable custom playlist id
 * (`cloud:<provider>:<playlist id>`) is matched first and new track ids are
 * merged into the existing playlist instead of creating a duplicate.
 */
@Singleton
class CloudPlaylistImportManager @Inject constructor(
    private val youTubeRepository: YouTubeRepository,
    private val soundCloudRepository: SoundCloudRepository,
    private val youTubeStreamProxy: YouTubeStreamProxy,
    private val soundCloudStreamProxy: SoundCloudStreamProxy,
    private val musicDao: MusicDao,
    private val userPreferencesRepository: UserPreferencesRepository
) {

    /** Result of a library import — drives the UI feedback (toast + button state). */
    data class ImportOutcome(
        val playlistName: String,
        val trackCount: Int,
        /** True when the playlist was already in the library and was refreshed instead of created. */
        val alreadyInLibrary: Boolean
    )

    /**
     * Imports [playlist] (and ALL of its pages) into the library's Playlists
     * tab. Runs on IO; safe to call repeatedly.
     */
    suspend fun importCloudPlaylist(playlist: CloudPlaylist): Result<ImportOutcome> =
        withContext(Dispatchers.IO) {
            try {
                val provider = playlist.provider
                val tracks = fetchAllTracks(playlist, provider)
                if (tracks.isEmpty()) {
                    return@withContext Result.failure(
                        IllegalStateException("No playable tracks were found for \"${playlist.name}\".")
                    )
                }

                // 1. Persist the songs (and their synthetic artist/album rows)
                //    exactly like the URL-import flow, so playback, favorites,
                //    search and stats treat them like any other cloud song.
                val songEntities = tracks.mapNotNull { song -> song.toImportEntity(provider) }
                    .distinctBy { it.id }
                if (songEntities.isEmpty()) {
                    return@withContext Result.failure(
                        IllegalStateException("No playable tracks could be persisted for \"${playlist.name}\".")
                    )
                }

                val newArtists = songEntities
                    .map { ArtistEntity(id = it.artistId, name = it.artistName, trackCount = 0) }
                    .distinctBy { it.id }
                val newAlbums = songEntities
                    .map {
                        AlbumEntity(
                            id = it.albumId,
                            title = it.albumName,
                            artistName = it.artistName,
                            artistId = it.artistId,
                            albumArtUriString = it.albumArtUriString,
                            songCount = 0,
                            year = 0
                        )
                    }
                    .distinctBy { it.id }

                // Parents first: the songs table has foreign keys to artists/albums.
                musicDao.insertArtistsIgnoreConflicts(newArtists)
                musicDao.insertAlbumsIgnoreConflicts(newAlbums)
                musicDao.insertSongsIgnoreConflicts(songEntities)

                // 2. Create (or refresh) the DataStore playlist, tagged with the
                //    provider so the Playlists tab badges it and the new
                //    Local/Cloud source filter can surface it.
                val sourceTag = if (provider == CloudStreamProvider.YOUTUBE) SOURCE_YOUTUBE else SOURCE_SOUNDCLOUD
                val stablePlaylistId = "cloud_${sourceTag.lowercase()}_${playlist.id}"
                val existing = userPreferencesRepository.userPlaylistsFlow
                    .firstOrNull { list -> list.any { it.id == stablePlaylistId } }

                val songIds = songEntities.map { it.id.toString() }
                if (existing != null) {
                    userPreferencesRepository.addSongsToPlaylist(stablePlaylistId, songIds)
                    Result.success(
                        ImportOutcome(
                            playlistName = playlist.name,
                            trackCount = songIds.size,
                            alreadyInLibrary = true
                        )
                    )
                } else {
                    userPreferencesRepository.createPlaylist(
                        name = playlist.name,
                        songIds = songIds,
                        coverImageUri = playlist.artworkUrl,
                        customId = stablePlaylistId,
                        source = sourceTag
                    )
                    Result.success(
                        ImportOutcome(
                            playlistName = playlist.name,
                            trackCount = songIds.size,
                            alreadyInLibrary = false
                        )
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Cloud playlist import failed for %s", playlist.url)
                Result.failure(e)
            }
        }

    /**
     * Fetches every page of the playlist's tracks: first page through the
     * same repository call the CloudCatalog screen uses, then the provider
     * continuation until it runs out (or the safety caps kick in).
     */
    private suspend fun fetchAllTracks(
        playlist: CloudPlaylist,
        provider: CloudStreamProvider
    ): List<Song> {
        val collected = mutableListOf<Song>()
        val seenIds = HashSet<String>()

        var page: CloudTracksPage? = when (provider) {
            CloudStreamProvider.YOUTUBE ->
                youTubeRepository.getCloudPlaylistTracks(playlist) { id ->
                    youTubeStreamProxy.getProxyUrl(id)
                }.getOrNull()
            else ->
                soundCloudRepository.getCloudPlaylistTracks(playlist) { encoded ->
                    soundCloudStreamProxy.getProxyUrl(encoded)
                }.getOrNull()
        } ?: return emptyList()

        var pagesFetched = 0
        while (page != null &&
            collected.size < MAX_IMPORT_TRACKS &&
            pagesFetched < MAX_IMPORT_PAGES
        ) {
            pagesFetched++
            page.songs.forEach { song ->
                if (song.id.isNotBlank() && seenIds.add(song.id)) {
                    collected.add(song)
                }
            }
            if (!page.hasMore || collected.size >= MAX_IMPORT_TRACKS) break

            val continuation = page
            page = when (provider) {
                CloudStreamProvider.YOUTUBE ->
                    youTubeRepository.getMoreCloudPlaylistTracks(playlist, continuation) { id ->
                        youTubeStreamProxy.getProxyUrl(id)
                    }.getOrNull()
                else ->
                    soundCloudRepository.getMoreCloudPlaylistTracks(playlist, continuation) { encoded ->
                        soundCloudStreamProxy.getProxyUrl(encoded)
                    }.getOrNull()
            }
        }
        return collected
    }

    /**
     * Maps a cloud [Song] to a persistable [SongEntity]:
     *  - `id` — the stable non-negative Long derived from the provider track
     *    id (YouTube video id / SoundCloud track id), so the same track maps
     *    to the same Room row everywhere in the app (favorites, playlists,
     *    stats);
     *  - `artistId` / `albumId` — stable NEGATIVE synthetic ids derived from
     *    the names (the documented Telegram/Netease convention) so imported
     *    artists/albums never collide with real MediaStore rows and each
     *    artist stays separate in the library;
     *  - `contentUriString` — the restart-safe cloud scheme URI
     *    (`youtube://<id>` / `soundcloud://<id>`), re-resolved to the live
     *    proxy at playback time.
     */
    private fun Song.toImportEntity(provider: CloudStreamProvider): SongEntity? {
        val providerTrackId = when (provider) {
            CloudStreamProvider.YOUTUBE -> youtubeId ?: id
            else -> id
        }
        if (providerTrackId.isBlank()) return null

        val stableSongId = CloudUriUtils.stableLongIdFromString(providerTrackId)
        if (stableSongId <= 0L) return null

        val artistName = artist.takeIf { it.isNotBlank() } ?: "Unknown Artist"
        val albumName = album.takeIf { it.isNotBlank() } ?: UNKNOWN_ALBUM_NAME
        val artistId = CloudUriUtils.stableSyntheticIdFromName(artistName)
        val albumId = CloudUriUtils.stableSyntheticIdFromName(albumName)

        val storageUri = CloudUriUtils.normalizeCloudUriForStorage(contentUriString)

        return SongEntity(
            id = stableSongId,
            title = title.takeIf { it.isNotBlank() } ?: "Unknown Title",
            artistName = artistName,
            artistId = artistId,
            albumArtist = null,
            albumName = albumName,
            albumId = albumId,
            contentUriString = storageUri,
            albumArtUriString = albumArtUriString?.takeIf { it.isNotBlank() },
            duration = duration.coerceAtLeast(0L),
            genre = null,
            filePath = storageUri,
            parentDirectoryPath = IMPORTED_PARENT_DIRECTORY,
            isFavorite = false,
            dateAdded = System.currentTimeMillis()
        )
    }

    companion object {
        const val SOURCE_YOUTUBE = "YOUTUBE"
        const val SOURCE_SOUNDCLOUD = "SOUNDCLOUD"

        /** Parent directory tag stored on imported cloud songs (matches the URL-import flow). */
        const val IMPORTED_PARENT_DIRECTORY = "imported_playlists"

        /**
         * Stable playlist id used to de-duplicate re-imports of the same cloud
         * playlist. Pure function on the companion so UI layers can compute a
         * playlist's library id without an instance.
         */
        fun stableLibraryIdFor(playlist: CloudPlaylist): String {
            val sourceTag =
                if (playlist.provider == CloudStreamProvider.YOUTUBE) SOURCE_YOUTUBE else SOURCE_SOUNDCLOUD
            return "cloud_${sourceTag.lowercase()}_${playlist.id}"
        }

        /** Synthetic album name for tracks whose provider metadata has none. */
        private const val UNKNOWN_ALBUM_NAME = "Unknown Album"

        /** Safety caps so importing a huge playlist can never run unbounded. */
        private const val MAX_IMPORT_TRACKS = 500
        private const val MAX_IMPORT_PAGES = 30
    }
}
