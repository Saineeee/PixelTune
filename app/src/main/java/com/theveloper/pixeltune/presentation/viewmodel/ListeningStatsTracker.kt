package com.theveloper.pixeltune.presentation.viewmodel

import android.os.SystemClock
import androidx.media3.common.C
import com.theveloper.pixeltune.data.DailyMixManager
import com.theveloper.pixeltune.data.model.Song
import com.theveloper.pixeltune.data.stats.PlaybackStatsRepository
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

        currentSession = ActiveSession(
            songId = song.id,
            totalDurationMs = normalizedDuration,
            startedAtEpochMs = nowEpoch,
            lastKnownPositionMs = positionMs.coerceAtLeast(0L),
            accumulatedListeningMs = 0L,
            lastRealtimeMs = nowRealtime,
            lastUpdateEpochMs = nowEpoch,
            isPlaying = isPlaying,
            isVoluntary = pendingVoluntarySongId == song.id
        )
        if (pendingVoluntarySongId == song.id) {
            pendingVoluntarySongId = null
        }
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

        // FIX(recently-played-liveness): surface the currently-playing song
        // in `playbackHistory` AS SOON AS the user has listened past the
        // minimum threshold, without waiting for the session to be finalized
        // (which only happens when the song changes or playback stops).
        //
        // The previous behavior was: user plays ONE song for 30 seconds and
        // opens Recently Played — the list showed "no history yet" because the
        // session was still active and `finalizeCurrentSession` had not been
        // called. This update mutates `_playbackHistory` on every progress
        // tick once the threshold is crossed, so the entry is visible
        // immediately.
        //
        // The upsert (remove existing entries with the same songId before
        // prepending the new one) keeps the list from growing with duplicate
        // entries over time. `mapRecentlyPlayedSongs` already dedupes by
        // songId, but capping duplicates here also keeps the 500-item cap
        // from being consumed by repeat entries for the same song.
        val totalCap = if (session.totalDurationMs > 0) session.totalDurationMs else Long.MAX_VALUE
        val listened = session.accumulatedListeningMs
            .coerceAtMost(totalCap).coerceAtLeast(0L)
        if (listened >= MIN_SESSION_LISTEN_MS) {
            upsertPlaybackHistory(
                songId = session.songId,
                timestamp = session.lastUpdateEpochMs
                    .coerceAtLeast(session.startedAtEpochMs.coerceAtLeast(0L))
                    .coerceAtMost(System.currentTimeMillis())
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
            // FIX(recently-played-liveness): upsert (not prepend) so we don't
            // end up with both a live entry from `onProgress` AND a finalized
            // entry for the same song. The upsert removes any existing entry
            // with the same songId before prepending the finalized one — the
            // finalized entry has the more accurate end-of-session timestamp.
            upsertPlaybackHistory(songId = songId, timestamp = timestamp)
            scope?.launch(Dispatchers.IO) {
                dailyMixManager.recordPlay(
                    songId = songId,
                    songDurationMs = listened,
                    timestamp = timestamp
                )
                playbackStatsRepository.recordPlayback(
                    songId = songId,
                    durationMs = listened,
                    timestamp = timestamp
                )
            }
        } else if (session.liveEntryRecorded) {
            // The session crossed MIN_SESSION_LISTEN_MS at some point (a live
            // entry was recorded), but the final listened total is below the
            // threshold (e.g. user scrubbed backward). Remove the stale live
            // entry so the history reflects the actual listening pattern.
            removePlaybackHistory(session.songId)
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
     * [MAX_INTERNAL_PLAYBACK_HISTORY_ITEMS].
     *
     * Used by both [onProgress] (live in-progress recording) and
     * [finalizeCurrentSession] (final timestamp). Visible for testing.
     */
    private fun upsertPlaybackHistory(songId: String, timestamp: Long) {
        val historyEntry = PlaybackStatsRepository.PlaybackHistoryEntry(
            songId = songId,
            timestamp = timestamp
        )
        _playbackHistory.update { current ->
            val withoutExisting = current.filterNot { it.songId == songId }
            (listOf(historyEntry) + withoutExisting).take(MAX_INTERNAL_PLAYBACK_HISTORY_ITEMS)
        }
    }

    /**
     * Removes all entries with the given [songId] from the in-memory
     * `_playbackHistory`. Used by [finalizeCurrentSession] when a session that
     * had a live entry ends up below [MIN_SESSION_LISTEN_MS] (e.g. user scrubbed
     * backward) — the live entry must be rolled back so the history reflects
     * the actual listening pattern.
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

    fun onCleared() {
        finalizeCurrentSession()
        scope = null
    }

    companion object {
        // FIX(recently-played-liveness): lowered from 5s → 1s so brief plays
        // (skipping through a song, listening to a 3-second sample, etc.) also
        // surface in Recently Played. Combined with the live in-progress
        // recording in `onProgress`, this means a song appears in the history
        // after just 1 second of actual playback, not 5s + a transition.
        private val MIN_SESSION_LISTEN_MS = TimeUnit.SECONDS.toMillis(1)
        private const val MAX_INTERNAL_PLAYBACK_HISTORY_ITEMS = 500
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
    // FIX(recently-played-liveness): tracks whether `onProgress` has already
    // recorded a live in-progress entry in `_playbackHistory` for this
    // session. Used by `finalizeCurrentSession` to know whether to upsert
    // (replace the live entry) or rollback (remove the live entry) when the
    // session ends below the MIN_SESSION_LISTEN_MS threshold.
    var liveEntryRecorded: Boolean = false
)
