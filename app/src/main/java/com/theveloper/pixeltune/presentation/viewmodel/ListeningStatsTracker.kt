package com.theveloper.pixeltune.presentation.viewmodel

import android.os.SystemClock
import androidx.media3.common.C
import com.theveloper.pixeltune.data.DailyMixManager
import com.theveloper.pixeltune.data.model.Song
import com.theveloper.pixeltune.data.stats.PlaybackStatsRepository
import com.theveloper.pixeltune.utils.CloudUriUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

import com.theveloper.pixeltune.data.database.EngagementDao

/**
 * Tracks listening statistics for songs.
 * Extracted from PlayerViewModel to reduce its size and improve modularity.
 *
 * Responsibilities:
 * - Track active listening sessions
 * - Record play statistics when session ends
 * - Handle voluntary vs automatic plays
 * - Maintain the in-memory playback history shown by the Listening History
 *   page and the "Recently Played" surfaces.
 *
 * FIX(listening-history-cloud): every history entry now carries a metadata
 * snapshot (title / artist / album / artwork / normalized content URI) taken
 * from the Song when playback starts. Previously entries stored only songId,
 * and cloud-streamed songs (YouTube / SoundCloud) could not be resolved from
 * the local MediaStore-backed library — they were looked up in a session-only
 * in-memory registry that is empty after an app restart, so cloud songs
 * silently vanished from the history. With the snapshot, cloud songs render
 * correctly in history even after restarts.
 */
class ListeningStatsTracker @Inject constructor(
    private val dailyMixManager: DailyMixManager,
    private val playbackStatsRepository: PlaybackStatsRepository,
    private val engagementDao: EngagementDao
) {
    private var currentSession: ActiveSession? = null
    private var pendingVoluntarySongId: String? = null
    private var scope: CoroutineScope? = null
    private val _playbackHistory = MutableStateFlow<List<PlaybackStatsRepository.PlaybackHistoryEntry>>(emptyList())
    val playbackHistory: StateFlow<List<PlaybackStatsRepository.PlaybackHistoryEntry>> = _playbackHistory.asStateFlow()

    /**
     * Must be called to set the coroutine scope for async operations.
     */
    fun initialize(coroutineScope: CoroutineScope) {
        scope = coroutineScope
        scope?.launch(Dispatchers.IO) {
            _playbackHistory.value = playbackStatsRepository.loadPlaybackHistory(
                limit = MAX_INTERNAL_PLAYBACK_HISTORY_ITEMS
            )
        }
    }

    fun onVoluntarySelection(songId: String) {
        pendingVoluntarySongId = songId
    }

    fun onSongChanged(
        song: Song?,
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean
    ) {
        finalizeCurrentSession()
        if (song == null) {
            return
        }

        val nowRealtime = SystemClock.elapsedRealtime()
        val nowEpoch = System.currentTimeMillis()
        val normalizedDuration = when {
            durationMs > 0 && durationMs != C.TIME_UNSET -> durationMs
            song.duration > 0 -> song.duration
            else -> 0L
        }

        // Capture the previous history timestamp for this song (if any) so a
        // rollback below can RESTORE it instead of wiping it — e.g. the user
        // replays a song for a fraction of a second and skips: the song's
        // earlier history entry must survive.
        val previousTimestamp = _playbackHistory.value
            .firstOrNull { it.songId == song.id }?.timestamp

        val metadataSnapshot = SongMetadataSnapshot.from(song)
        currentSession = ActiveSession(
            songId = song.id,
            totalDurationMs = normalizedDuration,
            startedAtEpochMs = nowEpoch,
            lastKnownPositionMs = positionMs.coerceAtLeast(0L),
            accumulatedListeningMs = 0L,
            lastRealtimeMs = nowRealtime,
            lastUpdateEpochMs = nowEpoch,
            isPlaying = isPlaying,
            isVoluntary = pendingVoluntarySongId == song.id,
            metadata = metadataSnapshot,
            previousHistoryTimestamp = previousTimestamp
        )
        if (pendingVoluntarySongId == song.id) {
            pendingVoluntarySongId = null
        }

        // FIX(listening-history-liveness): record a live history entry for the
        // song THE MOMENT it starts, not after MIN_SESSION_LISTEN_MS of
        // listening. The user expectation is explicit: right after tapping a
        // song (online streaming OR local), it must already appear in the
        // Listening History. If the session ends below the minimum threshold,
        // finalizeCurrentSession() rolls the entry back (restoring any earlier
        // timestamp for the same song, or removing it entirely).
        upsertPlaybackHistory(
            songId = song.id,
            timestamp = nowEpoch,
            metadata = metadataSnapshot
        )
        currentSession?.liveEntryRecorded = true
    }

    fun onPlayStateChanged(isPlaying: Boolean, positionMs: Long) {
        val session = currentSession ?: return
        val nowRealtime = SystemClock.elapsedRealtime()
        if (session.isPlaying) {
            session.accumulatedListeningMs += (nowRealtime - session.lastRealtimeMs).coerceAtLeast(0L)
        }
        session.isPlaying = isPlaying
        session.lastRealtimeMs = nowRealtime
        session.lastKnownPositionMs = positionMs.coerceAtLeast(0L)
        session.lastUpdateEpochMs = System.currentTimeMillis()
    }

    fun onProgress(positionMs: Long, isPlaying: Boolean) {
        val session = currentSession ?: return
        val nowRealtime = SystemClock.elapsedRealtime()
        if (session.isPlaying) {
            val delta = (nowRealtime - session.lastRealtimeMs).coerceAtLeast(0L)
            if (delta > 0) {
                session.accumulatedListeningMs += delta
            }
        }
        session.isPlaying = isPlaying
        session.lastRealtimeMs = nowRealtime
        session.lastKnownPositionMs = positionMs.coerceAtLeast(0L)
        session.lastUpdateEpochMs = System.currentTimeMillis()

        // Keep the live entry's timestamp fresh while the song keeps playing so
        // the history stays ordered by "most recently listened". The entry
        // itself was already created in onSongChanged (see
        // FIX(listening-history-liveness) there).
        val totalCap = if (session.totalDurationMs > 0) session.totalDurationMs else Long.MAX_VALUE
        val listened = session.accumulatedListeningMs
            .coerceAtMost(totalCap).coerceAtLeast(0L)
        if (listened >= MIN_SESSION_LISTEN_MS) {
            upsertPlaybackHistory(
                songId = session.songId,
                timestamp = session.lastUpdateEpochMs
                    .coerceAtLeast(session.startedAtEpochMs.coerceAtLeast(0L))
                    .coerceAtMost(System.currentTimeMillis()),
                metadata = session.metadata
            )
            session.liveEntryRecorded = true
        }
    }

    fun ensureSession(
        song: Song?,
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean
    ) {
        if (song == null) {
            finalizeCurrentSession()
            return
        }
        val existing = currentSession
        if (existing?.songId == song.id) {
            updateDuration(durationMs)
            val nowRealtime = SystemClock.elapsedRealtime()
            if (existing.isPlaying) {
                existing.accumulatedListeningMs += (nowRealtime - existing.lastRealtimeMs).coerceAtLeast(0L)
            }
            existing.isPlaying = isPlaying
            existing.lastRealtimeMs = nowRealtime
            existing.lastKnownPositionMs = positionMs.coerceAtLeast(0L)
            existing.lastUpdateEpochMs = System.currentTimeMillis()
            return
        }
        onSongChanged(song, positionMs, durationMs, isPlaying)
    }

    fun updateDuration(durationMs: Long) {
        val session = currentSession ?: return
        if (durationMs > 0 && durationMs != C.TIME_UNSET) {
            session.totalDurationMs = durationMs
        }
    }

    fun finalizeCurrentSession() {
        val session = currentSession ?: return
        val nowRealtime = SystemClock.elapsedRealtime()
        if (session.isPlaying) {
            session.accumulatedListeningMs += (nowRealtime - session.lastRealtimeMs).coerceAtLeast(0L)
        }
        val totalCap = if (session.totalDurationMs > 0) session.totalDurationMs else Long.MAX_VALUE
        val listened = session.accumulatedListeningMs.coerceAtMost(totalCap).coerceAtLeast(0L)
        if (listened >= MIN_SESSION_LISTEN_MS) {
            val rawEndTimestamp = session.lastUpdateEpochMs.takeIf { it > 0L }
                ?: (session.startedAtEpochMs + listened)
            val timestamp = rawEndTimestamp
                .coerceAtLeast(session.startedAtEpochMs.coerceAtLeast(0L))
                .coerceAtMost(System.currentTimeMillis())
            val songId = session.songId
            // Upsert (not prepend) so we don't end up with both a live entry
            // from onSongChanged/onProgress AND a finalized entry for the same
            // song. The finalized entry carries the more accurate end-of-session
            // timestamp.
            upsertPlaybackHistory(
                songId = songId,
                timestamp = timestamp,
                metadata = session.metadata
            )
            scope?.launch(Dispatchers.IO) {
                dailyMixManager.recordPlay(
                    songId = songId,
                    songDurationMs = listened,
                    timestamp = timestamp
                )
                playbackStatsRepository.recordPlayback(
                    songId = songId,
                    durationMs = listened,
                    timestamp = timestamp,
                    title = session.metadata.title,
                    artist = session.metadata.artist,
                    album = session.metadata.album,
                    albumArtUri = session.metadata.albumArtUri,
                    contentUri = session.metadata.contentUri,
                    songDurationMs = session.metadata.songDurationMs
                )
            }
        } else if (session.liveEntryRecorded) {
            // The session was shorter than the minimum listening threshold (e.g.
            // the user skipped almost immediately). Undo the live entry written
            // in onSongChanged — but RESTORE any earlier history entry the song
            // had before this session instead of dropping it entirely.
            val previousTimestamp = session.previousHistoryTimestamp
            if (previousTimestamp != null && previousTimestamp > 0L) {
                upsertPlaybackHistory(
                    songId = session.songId,
                    timestamp = previousTimestamp,
                    metadata = session.metadata
                )
            } else {
                removePlaybackHistory(session.songId)
            }
        }
        currentSession = null
        if (pendingVoluntarySongId == session.songId) {
            pendingVoluntarySongId = null
        }
    }

    /**
     * Upserts a [PlaybackStatsRepository.PlaybackHistoryEntry] into the
     * in-memory `_playbackHistory` StateFlow: removes any existing entry with
     * the same [songId], then prepends the new entry. Capped at
     * [MAX_INTERNAL_PLAYBACK_HISTORY_ITEMS] (30 songs, per the Listening
     * History requirement).
     *
     * Used by [onSongChanged] (immediate live recording), [onProgress] (live
     * timestamp refresh) and [finalizeCurrentSession] (final timestamp).
     */
    private fun upsertPlaybackHistory(
        songId: String,
        timestamp: Long,
        metadata: SongMetadataSnapshot? = null
    ) {
        val historyEntry = PlaybackStatsRepository.PlaybackHistoryEntry(
            songId = songId,
            timestamp = timestamp,
            title = metadata?.title?.takeIf { it.isNotBlank() },
            artist = metadata?.artist?.takeIf { it.isNotBlank() },
            album = metadata?.album?.takeIf { it.isNotBlank() },
            albumArtUri = metadata?.albumArtUri?.takeIf { it.isNotBlank() },
            contentUri = metadata?.contentUri?.takeIf { it.isNotBlank() },
            songDurationMs = metadata?.songDurationMs?.takeIf { it > 0L }
        )
        _playbackHistory.update { current ->
            val withoutExisting = current.filterNot { it.songId == songId }
            (listOf(historyEntry) + withoutExisting).take(MAX_INTERNAL_PLAYBACK_HISTORY_ITEMS)
        }
    }

    /**
     * Removes all entries with the given [songId] from the in-memory
     * `_playbackHistory`. Used by [finalizeCurrentSession] when a session that
     * had a live entry ends up below [MIN_SESSION_LISTEN_MS] and the song had
     * no earlier history entry (e.g. user skipped almost immediately).
     */
    private fun removePlaybackHistory(songId: String) {
        _playbackHistory.update { current ->
            current.filterNot { it.songId == songId }
        }
    }

    fun onPlaybackStopped() {
        finalizeCurrentSession()
    }

    fun clearHistory() {
        _playbackHistory.value = emptyList()
        scope?.launch(Dispatchers.IO) {
            playbackStatsRepository.clearHistory()
            engagementDao.clearAllEngagements()
        }
    }

    /**
     * IMPROVE(history-remove): removes a single song from the Listening
     * History (both the in-memory StateFlow that drives the UI and the
     * persisted events file), leaving all other songs untouched.
     *
     * If the song happens to be the one in the active listening session, the
     * session is detached first so a later finalize can't re-add the entry
     * the user just removed.
     */
    fun removeFromHistory(songId: String) {
        if (songId.isEmpty()) return
        if (currentSession?.songId == songId) {
            // Detach the active session without finalizing it (finalizing
            // would upsert a fresh history entry for this very song).
            currentSession = null
            if (pendingVoluntarySongId == songId) {
                pendingVoluntarySongId = null
            }
        }
        removePlaybackHistory(songId)
        scope?.launch(Dispatchers.IO) {
            playbackStatsRepository.removeHistoryEntriesForSong(songId)
        }
    }

    fun onCleared() {
        finalizeCurrentSession()
        scope = null
    }

    companion object {
        // Minimum listening time before a session counts as "really listened"
        // (shorter sessions get their live history entry rolled back).
        private val MIN_SESSION_LISTEN_MS = TimeUnit.SECONDS.toMillis(1)

        // FIX(listening-history-cap): the Listening History records a total of
        // 30 songs. Older entries fall off the end as new songs are played.
        private const val MAX_INTERNAL_PLAYBACK_HISTORY_ITEMS = 30
    }
}

/**
 * Metadata snapshot of a [Song], captured when playback starts and stored
 * alongside playback-history entries so cloud-streamed songs (YouTube /
 * SoundCloud — which are NOT in the local MediaStore-backed library) can be
 * rendered by the history UIs even across app restarts.
 */
data class SongMetadataSnapshot(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtUri: String? = null,
    val contentUri: String? = null,
    val songDurationMs: Long? = null
) {
    companion object {
        fun from(song: Song): SongMetadataSnapshot = SongMetadataSnapshot(
            title = song.title.takeIf { it.isNotBlank() },
            artist = song.artist.takeIf { it.isNotBlank() },
            album = song.album.takeIf { it.isNotBlank() },
            albumArtUri = song.albumArtUriString?.takeIf { it.isNotBlank() },
            // Normalize cloud proxy URLs (http://127.0.0.1:<port>/youtube/<id>)
            // to their restart-safe scheme form (youtube://<id>) — the proxy
            // port changes on every app launch, so the raw URL would be stale
            // the next time the entry is used to play the song.
            contentUri = song.contentUriString.takeIf { it.isNotBlank() }
                ?.let { CloudUriUtils.normalizeCloudUriForStorage(it) },
            songDurationMs = song.duration.takeIf { it > 0L }
        )
    }
}

/**
 * Represents an active listening session for a song.
 */
data class ActiveSession(
    val songId: String,
    var totalDurationMs: Long,
    val startedAtEpochMs: Long,
    var lastKnownPositionMs: Long,
    var accumulatedListeningMs: Long,
    var lastRealtimeMs: Long,
    var lastUpdateEpochMs: Long,
    var isPlaying: Boolean,
    val isVoluntary: Boolean,
    // Metadata snapshot captured when the session started; written into every
    // history entry produced by this session (see ListeningStatsTracker docs).
    val metadata: SongMetadataSnapshot = SongMetadataSnapshot(),
    // Timestamp of the song's history entry BEFORE this session started (null
    // if it had none). Used by finalizeCurrentSession to restore the earlier
    // entry when this session ends below MIN_SESSION_LISTEN_MS.
    val previousHistoryTimestamp: Long? = null,
    // Tracks whether a live entry has been recorded in `_playbackHistory` for
    // this session. Used by [finalizeCurrentSession] to know whether to
    // upsert (replace the live entry) or rollback (remove/restore the live
    // entry) when the session ends below the MIN_SESSION_LISTEN_MS threshold.
    var liveEntryRecorded: Boolean = false
)
