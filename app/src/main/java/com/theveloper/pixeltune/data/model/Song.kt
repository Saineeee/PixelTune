package com.theveloper.pixeltune.data.model

import android.os.Parcelable
import androidx.compose.runtime.Immutable
import com.theveloper.pixeltune.utils.splitArtistsByDelimiters
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize

@Immutable
@Parcelize
data class Song(
    val id: String,
    val title: String,
    /**
     * Legacy artist display string.
     * - With multi-artist parsing enabled by default, this typically contains only the primary artist for backward compatibility.
     * For accurate display of all artists, use the [artists] list and [displayArtist] property.
     */
    val artist: String,
    val artistId: Long, // Primary artist ID for backward compatibility
    val artists: List<ArtistRef> = emptyList(), // All artists for multi-artist support
    val album: String,
    val albumId: Long,
    val albumArtist: String? = null, // Album artist from metadata
    val path: String, // Added for direct file system access
    val contentUriString: String,
    val albumArtUriString: String?,
    val duration: Long,
    val genre: String? = null,
    val lyrics: String? = null,
    val isFavorite: Boolean = false,
    val trackNumber: Int = 0,
    val year: Int = 0,
    val dateAdded: Long = 0,
    val dateModified: Long = 0,
    val mimeType: String?,
    val bitrate: Int?,
    val sampleRate: Int?,
    val telegramFileId: Int? = null, // ID of the file in Telegram
    val telegramChatId: Long? = null, // ID of the chat where the file is located
    val neteaseId: Long? = null, // Netease Cloud Music song ID
    val gdriveFileId: String? = null, // Google Drive file ID
    val youtubeId: String? = null // YouTube video ID
) : Parcelable {
    @IgnoredOnParcel
    private val defaultArtistDelimiters = listOf("/", ";", ",", "+", "&")

    // PERF: displayArtist is read during composition of every song row
    // (and MediaSession metadata builds). Computing it allocates a sorted list
    // + a joined String on EVERY access; during scrolling that ran per visible
    // row per recomposition. Song is immutable, so the value is memoized per
    // instance after the first read. The benign race (two threads computing
    // the same deterministic value) is fine; String's final fields make its
    // racy publication safe on the JVM.
    @IgnoredOnParcel
    private var displayArtistCache: String? = null

    /**
     * Returns the display string for artists.
     * If multiple artists exist, joins them with ", ".
     * Falls back to splitting the legacy artist string using common delimiters,
     * and finally the raw artist field if nothing else is available.
     */
    val displayArtist: String
        get() {
            displayArtistCache?.let { return it }
            return computeDisplayArtist().also { displayArtistCache = it }
        }

    private fun computeDisplayArtist(): String {
        if (artists.isNotEmpty()) {
            return artists.sortedByDescending { it.isPrimary }.joinToString(", ") { it.name }
        }
        val split = artist.splitArtistsByDelimiters(defaultArtistDelimiters)
        return if (split.isNotEmpty()) split.joinToString(", ") else artist
    }

    /**
     * Returns the primary artist from the artists list,
     * or creates one from the legacy artist field.
     */
    val primaryArtist: ArtistRef
        get() = artists.find { it.isPrimary }
            ?: artists.firstOrNull()
            ?: ArtistRef(id = artistId, name = artist, isPrimary = true)

    companion object {
        fun emptySong(): Song {
            return Song(
                id = "-1",
                title = "",
                artist = "",
                artistId = -1L,
                artists = emptyList(),
                album = "",
                albumId = -1L,
                albumArtist = null,
                path = "",
                contentUriString = "",
                albumArtUriString = null,
                duration = 0L,
                genre = null,
                lyrics = null,
                isFavorite = false,
                trackNumber = 0,
                year = 0,
                dateAdded = 0,
                dateModified = 0,
                mimeType = "-",
                bitrate = 0,
                sampleRate = 0,
                telegramFileId = null,
                telegramChatId = null,
                neteaseId = null,
                gdriveFileId = null,
                youtubeId = null
            )
        }
    }
}
