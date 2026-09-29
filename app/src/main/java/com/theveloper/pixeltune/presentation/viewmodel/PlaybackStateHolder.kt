package com.theveloper.pixeltune.presentation.viewmodel

import androidx.media3.session.MediaController
import androidx.media3.common.Player
import androidx.media3.common.C
import com.theveloper.pixeltune.data.service.player.DualPlayerEngine
import com.theveloper.pixeltune.data.preferences.UserPreferencesRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import com.theveloper.pixeltune.data.model.Song
import timber.log.Timber
import com.theveloper.pixeltune.utils.QueueUtils
import com.theveloper.pixeltune.utils.MediaItemBuilder
import kotlin.math.abs

@Singleton
class PlaybackStateHolder @Inject constructor(
    private val dualPlayerEngine: DualPlayerEngine,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val queueStateHolder: QueueStateHolder,
    private val listeningStatsTracker: ListeningStatsTracker
) {
    companion object {
        private const val TAG = "PlaybackStateHolder"
        private const val DURATION_MISMATCH_TOLERANCE_MS = 1500L
        private const val PROGRESS_TICK_MS = 250L
        /**
         * Threshold above which we skip per-item moveMediaItem calls and use
         * a single setMediaItems call instead. moveMediaItem triggers an IPC
         * round-trip for each call, which freezes the UI on large queues.
         */
        private const val BULK_REPLACE_THRESHOLD = 80
    }

    private var scope: CoroutineScope? = null
    
    // MediaController
    var mediaController: MediaController? = null
        private set

    // Player State
    private val _stablePlayerState = MutableStateFlow(StablePlayerState())
    val stablePlayerState: StateFlow<StablePlayerState> = _stablePlayerState.asStateFlow()
    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    // Internal State
    private var isSeeking = false
    private var remoteSeekUnlockJob: Job? = null

    fun initialize(coroutineScope: CoroutineScope) {
        this.scope = coroutineScope
    }

    fun setMediaController(controller: MediaController?) {
        this.mediaController = controller
    }

    /**
     * Drops the strong reference to the controller when the owning ViewModel
     * is torn down. The singleton would otherwise keep a released controller
     * reachable (and, before release, a live binder connection) until the
     * next ViewModel sets a new one.
     */
    fun clearMediaController(controller: MediaController?) {
        if (this.mediaController === controller) {
            this.mediaController = null
        }
    }
    
    fun updateStablePlayerState(update: (StablePlayerState) -> StablePlayerState) {
        _stablePlayerState.update(update)
    }

    fun setCurrentPosition(positionMs: Long) {
        _currentPosition.value = positionMs.coerceAtLeast(0L)
    }
    
    /* -------------------------------------------------------------------------- */
    /*                               Playback Controls                            */
    /* -------------------------------------------------------------------------- */

    fun playPause() {
        val controller = mediaController ?: return
        if (controller.isPlaying) {
            controller.pause()
        } else {
            controller.play()
        }
    }

    fun seekTo(position: Long) {
        remoteSeekUnlockJob?.cancel()
        mediaController?.seekTo(position)
        setCurrentPosition(position)
    }

    fun previousSong() {
        val controller = mediaController ?: return
        if (controller.currentPosition > 10000) { // 10 seconds
            controller.seekTo(0)
        } else {
            controller.seekToPrevious()
        }
    }

    fun nextSong() {
        mediaController?.seekToNext()
    }

    fun cycleRepeatMode() {
        val currentMode = _stablePlayerState.value.repeatMode
        val newMode = when (currentMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_OFF
            else -> Player.REPEAT_MODE_OFF
        }
        mediaController?.repeatMode = newMode
        scope?.launch { userPreferencesRepository.setRepeatMode(newMode) }
        _stablePlayerState.update { it.copy(repeatMode = newMode) }
    }

    fun setRepeatMode(mode: Int) {
        mediaController?.repeatMode = mode
        scope?.launch { userPreferencesRepository.setRepeatMode(mode) }
        _stablePlayerState.update { it.copy(repeatMode = mode) }
    }

    /* -------------------------------------------------------------------------- */
    /*                               Progress Updates                             */
    /* -------------------------------------------------------------------------- */
    
    private var progressJob: kotlinx.coroutines.Job? = null

    /**
     * Reconciles duration reported by the player with the current song metadata duration.
     *
     * Why:
     * - During some transitions (notably crossfade player swaps), the reported duration can lag
     *   behind the currently visible track for a short period.
     * - Relying only on one source can make progress run too slow/fast.
     */
    private fun resolveEffectiveDuration(
        reportedDurationMs: Long,
        songDurationHintMs: Long,
        currentPositionMs: Long
    ): Long {
        val reported = when {
            reportedDurationMs == C.TIME_UNSET -> 0L
            reportedDurationMs < 0L -> 0L
            else -> reportedDurationMs
        }
        val hint = songDurationHintMs.coerceAtLeast(0L)
        val position = currentPositionMs.coerceAtLeast(0L)

        if (reported <= 0L) return hint
        if (hint <= 0L) return reported

        val diff = abs(reported - hint)
        if (diff <= DURATION_MISMATCH_TOLERANCE_MS) return reported

        // If playback already passed the metadata hint, trust the reported duration to avoid clipping.
        if (position > hint + DURATION_MISMATCH_TOLERANCE_MS && reported >= position) {
            return reported
        }

        // Otherwise prefer the shorter duration to avoid stale longer values after swaps.
        val resolved = minOf(reported, hint)
        if (diff > 10_000L) {
            Timber.tag(TAG).w(
                "Duration mismatch resolved (reported=%dms, hint=%dms, pos=%dms, resolved=%dms)",
                reported, hint, position, resolved
            )
        }
        return resolved
    }

    fun resolveDurationForPlaybackState(
        reportedDurationMs: Long,
        songDurationHintMs: Long,
        currentPositionMs: Long
    ): Long = resolveEffectiveDuration(
        reportedDurationMs = reportedDurationMs,
        songDurationHintMs = songDurationHintMs,
        currentPositionMs = currentPositionMs
    )

    fun startProgressUpdates() {
        stopProgressUpdates()
        progressJob = scope?.launch {
            while (true) {
                val controller = mediaController
                // Media3: Check isPlaying or playbackState == READY/BUFFERING
                if (controller != null && controller.isPlaying && !isSeeking) {
                    val visibleSong = _stablePlayerState.value.currentSong
                    val currentMediaId = controller.currentMediaItem?.mediaId
                    val hasMediaMismatch = visibleSong?.id != null &&
                        currentMediaId != null &&
                        visibleSong.id != currentMediaId

                    if (hasMediaMismatch) {
                        Timber.tag(TAG).v(
                            "Skipping local progress tick due media mismatch (visible=%s, player=%s)",
                            visibleSong?.id,
                            currentMediaId
                        )
                        delay(PROGRESS_TICK_MS)
                        continue
                    }

                    val currentPosition = controller.currentPosition.coerceAtLeast(0L)
                    val songDurationHint = visibleSong?.duration ?: 0L
                    val duration = resolveEffectiveDuration(
                        reportedDurationMs = controller.duration,
                        songDurationHintMs = songDurationHint,
                        currentPositionMs = currentPosition
                    )

                    listeningStatsTracker.onProgress(currentPosition, true)
                    if (_currentPosition.value != currentPosition) {
                        _currentPosition.value = currentPosition
                    }

                    _stablePlayerState.update { state ->
                        if (state.totalDuration == duration) {
                            state
                        } else {
                            state.copy(totalDuration = duration)
                        }
                    }
                }
                delay(PROGRESS_TICK_MS)
            }
        }
    }

    fun stopProgressUpdates() {
        progressJob?.cancel()
        progressJob = null
    }

    /* -------------------------------------------------------------------------- */
    /*                               Shuffle & Repeat                             */
    /* -------------------------------------------------------------------------- */

    private fun reorderQueueInPlace(player: Player, desiredQueue: List<Song>): Boolean {
        if (desiredQueue.isEmpty()) return false

        val currentCount = player.mediaItemCount
        if (currentCount != desiredQueue.size) {
            Timber.tag(TAG).w(
                "Cannot reorder queue in place: size mismatch (player=%d, desired=%d)",
                currentCount,
                desiredQueue.size
            )
            return false
        }

        val currentIds = MutableList(currentCount) { index ->
            player.getMediaItemAt(index).mediaId
        }
        val desiredIds = desiredQueue.map { it.id }

        val currentCounts = currentIds.groupingBy { it }.eachCount()
        val desiredCounts = desiredIds.groupingBy { it }.eachCount()
        if (currentCounts != desiredCounts) {
            Timber.tag(TAG).w("Cannot reorder queue in place: mediaId mismatch")
            return false
        }

        for (targetIndex in desiredIds.indices) {
            val desiredId = desiredIds[targetIndex]
            if (currentIds[targetIndex] == desiredId) continue

            var fromIndex = -1
            for (searchIndex in targetIndex + 1 until currentIds.size) {
                if (currentIds[searchIndex] == desiredId) {
                    fromIndex = searchIndex
                    break
                }
            }

            if (fromIndex == -1) {
                Timber.tag(TAG).w(
                    "Cannot reorder queue in place: target mediaId '%s' not found",
                    desiredId
                )
                return false
            }

            player.moveMediaItem(fromIndex, targetIndex)
            val movedId = currentIds.removeAt(fromIndex)
            currentIds.add(targetIndex, movedId)
        }

        return true
    }

    /**
     * Replaces the player timeline with [newQueue] in a single setMediaItems call,
     * preserving the currently playing song and its position. This is O(1) IPC calls
     * versus O(n) for reorderQueueInPlace, making it suitable for large queue shuffles.
     */
    private fun replacePlayerQueue(player: Player, newQueue: List<Song>, currentSongId: String?, currentPosition: Long) {
        val wasPlaying = player.isPlaying
        val targetIndex = if (currentSongId != null) {
            newQueue.indexOfFirst { it.id == currentSongId }.takeIf { it != -1 } ?: 0
        } else 0

        dualPlayerEngine.masterPlayer.setMediaItems(
            newQueue.map { MediaItemBuilder.build(it) },
            targetIndex,
            currentPosition
        )
        if (wasPlaying && !player.isPlaying) {
            player.play()
        }
    }

    fun toggleShuffle(
        currentSongs: List<Song>,
        currentSong: Song?,
        currentQueueSourceName: String,
        updateQueueCallback: (List<Song>) -> Unit
    ) {
        scope?.launch {
            val player = mediaController ?: return@launch
            if (currentSongs.isEmpty()) return@launch

            val isCurrentlyShuffled = _stablePlayerState.value.isShuffleEnabled

            if (!isCurrentlyShuffled) {
                // Enable Shuffle
                if (!queueStateHolder.hasOriginalQueue()) {
                    queueStateHolder.setOriginalQueueOrder(currentSongs)
                    queueStateHolder.saveOriginalQueueState(currentSongs, currentQueueSourceName)
                }

                val currentMediaId = player.currentMediaItem?.mediaId ?: currentSong?.id
                val currentIndex = currentMediaId
                    ?.let { mediaId -> currentSongs.indexOfFirst { it.id == mediaId }.takeIf { it >= 0 } }
                    ?: player.currentMediaItemIndex.coerceIn(0, (currentSongs.size - 1).coerceAtLeast(0))
                val currentPosition = player.currentPosition
                val wasPlaying = player.isPlaying

                // Run heavy shuffle work off main to keep UI and playback responsive.
                val shuffledQueue = withContext(Dispatchers.Default) {
                    QueueUtils.buildAnchoredShuffleQueueSuspending(currentSongs, currentIndex)
                }

                // For large queues, use bulk replace (1 IPC call) instead of
                // per-item moveMediaItem (n IPC calls) which freezes the UI.
                if (currentSongs.size > BULK_REPLACE_THRESHOLD) {
                    replacePlayerQueue(player, shuffledQueue, currentMediaId, currentPosition)
                } else {
                    val reordered = reorderQueueInPlace(player, shuffledQueue)
                    if (!reordered) {
                        replacePlayerQueue(player, shuffledQueue, currentMediaId, currentPosition)
                    }
                }

                updateQueueCallback(shuffledQueue)
                _stablePlayerState.update { it.copy(isShuffleEnabled = true) }
                if (wasPlaying && !player.isPlaying) {
                    player.play()
                }

                scope?.launch {
                    if (userPreferencesRepository.persistentShuffleEnabledFlow.first()) {
                        userPreferencesRepository.setShuffleOn(true)
                    }
                }
            } else {
                // Disable Shuffle
                scope?.launch {
                    if (userPreferencesRepository.persistentShuffleEnabledFlow.first()) {
                        userPreferencesRepository.setShuffleOn(false)
                    }
                }

                if (!queueStateHolder.hasOriginalQueue()) {
                    _stablePlayerState.update { it.copy(isShuffleEnabled = false) }
                    return@launch
                }

                val originalQueue = queueStateHolder.originalQueueOrder
                val wasPlaying = player.isPlaying
                val currentPosition = player.currentPosition
                val currentSongId = currentSong?.id ?: player.currentMediaItem?.mediaId
                val originalIndex = originalQueue.indexOfFirst { it.id == currentSongId }.takeIf { it >= 0 }

                if (originalIndex == null) {
                    _stablePlayerState.update { it.copy(isShuffleEnabled = false) }
                    return@launch
                }

                // Use bulk replace for large queues to avoid UI freeze
                if (originalQueue.size > BULK_REPLACE_THRESHOLD) {
                    replacePlayerQueue(player, originalQueue, currentSongId, currentPosition)
                } else {
                    val reordered = reorderQueueInPlace(player, originalQueue)
                    if (!reordered) {
                        replacePlayerQueue(player, originalQueue, currentSongId, currentPosition)
                    }
                }

                updateQueueCallback(originalQueue)
                _stablePlayerState.update { it.copy(isShuffleEnabled = false) }
                if (wasPlaying && !player.isPlaying) {
                    player.play()
                }
            }
        }
    }

}
