package com.theveloper.pixeltune.data.preferences

import kotlinx.serialization.Serializable

/**
 * IMPROVE(playback-restore): serializable snapshot of the last playback
 * session, persisted to DataStore so the app can bring back the song that
 * was playing after the app is closed and re-opened.
 *
 * After re-open, the mini player shows the restored song in a paused state;
 * tapping play loads it back into ExoPlayer and resumes from
 * [LastPlaybackSnapshot.positionMs] — the exact point the user left off.
 */
@Serializable
data class LastPlaybackSnapshot(
    /** The song that was current when the session ended. */
    val current: LastPlaybackSongSnapshot,
    /** Playback position (ms) of [current] when the session ended. */
    val positionMs: Long = 0L,
    /** Display name of the queue the song was played from ("Your Mix", …). */
    val queueName: String = "None",
    /**
     * Bounded window of the queue around [current] (current song included at
     * [queueIndex]). Only the current song plus the next up-to-15 songs are
     * kept so the persisted JSON stays small even for 5000-song queues.
     */
    val queue: List<LastPlaybackSongSnapshot> = emptyList(),
    /** Index of [current] inside [queue]. */
    val queueIndex: Int = 0
)

/**
 * Minimal per-song snapshot used by [LastPlaybackSnapshot].
 *
 * For local (MediaStore) songs only the [id] matters on restore — the song is
 * re-resolved fresh from the library. The remaining fields are a metadata
 * fallback used when the id can NOT be resolved from the library, which is
 * the normal case for cloud-streamed songs (YouTube / SoundCloud) because
 * they never exist in the MediaStore-backed songs table.
 */
@Serializable
data class LastPlaybackSongSnapshot(
    val id: String,
    val title: String = "",
    val artist: String = "",
    val album: String? = null,
    val albumArtUri: String? = null,
    /**
     * PLAYBACK uri in its restart-safe form. Session proxy URLs
     * (`http://127.0.0.1:<port>/youtube/<id>`) are normalized to the scheme
     * form (`youtube://<id>`, `soundcloud://<encoded>`) before saving because
     * the proxy rebinds to a different port on every launch.
     */
    val contentUri: String = "",
    val durationMs: Long = 0L,
    val youtubeId: String? = null
)
