package com.theveloper.pixeltune.data.media

import android.content.Context
import androidx.media3.common.MediaItem
import com.theveloper.pixeltune.R
import com.theveloper.pixeltune.data.model.Song
import com.theveloper.pixeltune.utils.MediaItemBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Helper to map MediaItem to Song.
 * Note: This does NOT have access to the full song library master list,
 * so it should be used for strictly metadata-based mapping or fallback.
 * The ViewModel should try lookup by ID first.
 */
@Singleton
class MediaMapper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun resolveSongFromMediaItem(mediaItem: MediaItem): Song? {
        val metadata = mediaItem.mediaMetadata
        val extras = metadata.extras
        // extras are lazily populated in some cases, or we rely on localConfiguration
        val contentUri = extras?.getString(MediaItemBuilder.EXTERNAL_EXTRA_CONTENT_URI)
            ?: mediaItem.localConfiguration?.uri?.toString()
            ?: return null

        val title = metadata.title?.toString()?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.unknown_song_title)
        val artist = metadata.artist?.toString()?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.unknown_artist)
        val album = extras?.getString(MediaItemBuilder.EXTERNAL_EXTRA_ALBUM)?.takeIf { it.isNotBlank() }
            ?: metadata.albumTitle?.toString()?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.unknown_album)
        val albumId = -1L
        val duration = extras?.getLong(MediaItemBuilder.EXTERNAL_EXTRA_DURATION) ?: 0L
        val dateAdded = extras?.getLong(MediaItemBuilder.EXTERNAL_EXTRA_DATE_ADDED) ?: System.currentTimeMillis()
        val id = mediaItem.mediaId

        // Note: This creates a partial Song object.
        // Some fields like path, genre, year might be missing if not in extras.
        return Song(
            id = id,
            title = title,
            artist = artist,
            artistId = -1L, // unknown from just MediaItem typically
            album = album,
            albumId = albumId,
            path = "", // local path unknown from URI usually
            contentUriString = contentUri,
            albumArtUriString = metadata.artworkUri?.toString(),
            duration = duration,
            dateAdded = dateAdded,
            mimeType = null,
            bitrate = null,
            sampleRate = null
        )
    }

    /**
     * Lightweight fallback that extracts whatever song metadata is available
     * directly from a [MediaItem]'s public [androidx.media3.common.MediaMetadata]
     * fields, without requiring the EXTERNAL_EXTRA_* extras Bundle.
     *
     * Used as a last-resort fallback in [com.theveloper.pixeltune.presentation.viewmodel.PlayerViewModel.resolveSongFromMediaItem]
     * when:
     *  1. The song ID is not in the local Room DB (e.g. cloud-streamed YouTube /
     *     SoundCloud autoplay recommendations that were appended at runtime by
     *     MusicService and never persisted to the in-memory playback queue yet).
     *  2. The richer [resolveSongFromMediaItem] fallback returns null because
     *     the MediaItem was built without the EXTERNAL_EXTRA_CONTENT_URI extra
     *     (e.g. by old callers using an inline [androidx.media3.common.MediaMetadata.Builder]
     *     instead of [com.theveloper.pixeltune.utils.MediaItemBuilder.build]).
     *
     * Without this fallback, [com.theveloper.pixeltune.presentation.viewmodel.PlaybackStateHolder]
     * would set currentSong = null after a cloud-song transition, causing the
     * full-player UI to render blank title / artist / artwork until the in-memory
     * playback queue updated asynchronously — the exact symptom reported in the
     * "cloud metadata doesn't show up" bug.
     */
    fun resolveSongFromMediaItemMetadataOnly(mediaItem: MediaItem): Song? {
        val metadata = mediaItem.mediaMetadata
        val title = metadata.title?.toString()?.takeIf { it.isNotBlank() }
            ?: return null
        val artist = metadata.artist?.toString()?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.unknown_artist)
        val album = metadata.albumTitle?.toString()?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.unknown_album)

        // The MediaItem's localConfiguration.uri is set whenever the MediaItem
        // was built via MediaItem.Builder.setUri(...). For cloud songs this is
        // the proxy URL (or scheme URI for favorited cloud songs after restart).
        val contentUri = mediaItem.localConfiguration?.uri?.toString()
            ?: return null

        return Song(
            id = mediaItem.mediaId,
            title = title,
            artist = artist,
            artistId = -1L,
            album = album,
            albumId = -1L,
            path = "",
            contentUriString = contentUri,
            albumArtUriString = metadata.artworkUri?.toString(),
            duration = 0L,
            dateAdded = System.currentTimeMillis(),
            mimeType = null,
            bitrate = null,
            sampleRate = null
        )
    }
}
