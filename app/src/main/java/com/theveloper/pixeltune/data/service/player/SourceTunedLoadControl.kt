package com.theveloper.pixeltune.data.service.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator

/**
 * Adaptive buffering for [DualPlayerEngine].
 *
 * Media3 1.9.x has no runtime `ExoPlayer.setLoadControl(...)`, so per-source
 * tuning is achieved by delegating to one pre-built [DefaultLoadControl] per
 * source kind, all sharing a single [DefaultAllocator] (one allocation pool
 * per player, exactly like the single-control setup it replaces).
 *
 * Lifecycle callbacks (`onPrepared`, `onStopped`, `onReleased`) are forwarded
 * to every delegate so each keeps its per-player loading state; decision
 * queries (`shouldContinueLoading`, `shouldStartPlayback`, back-buffer
 * questions) are answered by the delegate currently selected via [select].
 * The engine re-selects on every media item transition (master player) and
 * before preparing the auxiliary player.
 *
 * The decision logic itself is deliberately NOT re-implemented here — it
 * stays inside media3's battle-tested [DefaultLoadControl]; only the
 * per-source constants differ.
 */
@OptIn(UnstableApi::class)
class SourceTunedLoadControl private constructor(
    private val sharedAllocator: DefaultAllocator,
    private val onDeviceFileControl: DefaultLoadControl,
    private val cloudDriveControl: DefaultLoadControl,
    private val remoteStreamControl: DefaultLoadControl
) : LoadControl {

    /** The source categories the engine distinguishes for buffering. */
    enum class PlaybackSourceKind {
        /** Local on-device files (`file://`, `content://`, …). */
        ON_DEVICE_FILE,

        /** Cloud-drive audio proxied through the app's localhost stream proxy. */
        CLOUD_DRIVE,

        /** Remote streaming sources (YouTube, SoundCloud, plain http(s)). */
        REMOTE_STREAM
    }

    @Volatile
    private var activeKind: PlaybackSourceKind = PlaybackSourceKind.REMOTE_STREAM

    private val activeControl: DefaultLoadControl
        get() = when (activeKind) {
            PlaybackSourceKind.ON_DEVICE_FILE -> onDeviceFileControl
            PlaybackSourceKind.CLOUD_DRIVE -> cloudDriveControl
            PlaybackSourceKind.REMOTE_STREAM -> remoteStreamControl
        }

    /**
     * Switches the buffering profile. Called by the engine when the media item
     * (whose URI scheme identifies the source kind) changes. Thread-safe: the
     * kind is a volatile reference read on the playback thread.
     */
    fun select(kind: PlaybackSourceKind) {
        if (kind != activeKind) {
            activeKind = kind
        }
    }

    // ── Lifecycle: forwarded to ALL delegates so their per-player state stays warm ──

    override fun onPrepared(playerId: PlayerId) {
        onDeviceFileControl.onPrepared(playerId)
        cloudDriveControl.onPrepared(playerId)
        remoteStreamControl.onPrepared(playerId)
    }

    // NOTE: onTracksSelected is intentionally NOT overridden. Media3's
    // ExoPlayer never calls it (the interface default exists "to please the
    // compiler only"), so forwarding it would be dead code.

    override fun onStopped(playerId: PlayerId) {
        onDeviceFileControl.onStopped(playerId)
        cloudDriveControl.onStopped(playerId)
        remoteStreamControl.onStopped(playerId)
    }

    override fun onReleased(playerId: PlayerId) {
        onDeviceFileControl.onReleased(playerId)
        cloudDriveControl.onReleased(playerId)
        remoteStreamControl.onReleased(playerId)
    }

    // ── Queries: answered by the currently selected profile ──

    override fun getAllocator(playerId: PlayerId): Allocator =
        activeControl.getAllocator(playerId)

    override fun getBackBufferDurationUs(playerId: PlayerId): Long =
        activeControl.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean =
        activeControl.retainBackBufferFromKeyframe(playerId)

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean =
        activeControl.shouldContinueLoading(parameters)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean =
        activeControl.shouldStartPlayback(parameters)

    override fun shouldContinuePreloading(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        bufferedDurationUs: Long
    ): Boolean = activeControl.shouldContinuePreloading(
        playerId,
        timeline,
        mediaPeriodId,
        bufferedDurationUs
    )

    companion object {
        /**
         * All buffering constants in one place. Each profile trades
         * start-up latency against stall rate for its source type.
         */
        private object Budgets {
            // Flash storage reads are fast and never stall: a 500 ms start
            // gate gives near-instant playback, and the small window keeps
            // memory footprint and loader wake-ups minimal.
            const val ON_DEVICE_MIN_MS = 2_000
            const val ON_DEVICE_MAX_MS = 10_000
            const val ON_DEVICE_START_MS = 500
            const val ON_DEVICE_REBUFFER_MS = 1_000

            // Telegram / Netease / Drive audio is forwarded through the app's
            // localhost proxy: steady throughput once the upstream handshake
            // completes, so a moderate window covers proxy reconnects without
            // hoarding memory the way a full streaming buffer would.
            const val CLOUD_DRIVE_MIN_MS = 15_000
            const val CLOUD_DRIVE_MAX_MS = 30_000
            const val CLOUD_DRIVE_START_MS = 1_500
            const val CLOUD_DRIVE_REBUFFER_MS = 2_000

            // YouTube / SoundCloud upstreams are bursty (bot-detection
            // re-handshakes, CDN redirects): a generous forward buffer
            // converts upstream stalls into silent refills. These are the
            // values the engine was already tuned with for this case.
            const val REMOTE_MIN_MS = 30_000
            const val REMOTE_MAX_MS = 60_000
            const val REMOTE_START_MS = 2_000
            const val REMOTE_REBUFFER_MS = 3_000
        }

        /**
         * Maps a media item URI scheme to its buffering profile. Purely
         * scheme-based on purpose: no I/O, deterministic, and callable from
         * the main thread at item-transition time.
         */
        fun kindForUriScheme(scheme: String?): PlaybackSourceKind {
            return when (scheme?.lowercase()) {
                "file", "content", "android_resource", "asset", "data", "", null ->
                    PlaybackSourceKind.ON_DEVICE_FILE
                "telegram", "netease", "gdrive" ->
                    PlaybackSourceKind.CLOUD_DRIVE
                else -> PlaybackSourceKind.REMOTE_STREAM
            }
        }

        /** Builds the control with all three profiles sharing one allocator. */
        fun create(): SourceTunedLoadControl {
            val sharedAllocator = DefaultAllocator(
                /* trimOnReset = */ true,
                C.DEFAULT_BUFFER_SEGMENT_SIZE
            )

            fun control(
                minMs: Int,
                maxMs: Int,
                startMs: Int,
                rebufferMs: Int
            ): DefaultLoadControl = DefaultLoadControl.Builder()
                .setAllocator(sharedAllocator)
                .setBufferDurationsMs(minMs, maxMs, startMs, rebufferMs)
                .build()

            return SourceTunedLoadControl(
                sharedAllocator = sharedAllocator,
                onDeviceFileControl = control(
                    Budgets.ON_DEVICE_MIN_MS,
                    Budgets.ON_DEVICE_MAX_MS,
                    Budgets.ON_DEVICE_START_MS,
                    Budgets.ON_DEVICE_REBUFFER_MS
                ),
                cloudDriveControl = control(
                    Budgets.CLOUD_DRIVE_MIN_MS,
                    Budgets.CLOUD_DRIVE_MAX_MS,
                    Budgets.CLOUD_DRIVE_START_MS,
                    Budgets.CLOUD_DRIVE_REBUFFER_MS
                ),
                remoteStreamControl = control(
                    Budgets.REMOTE_MIN_MS,
                    Budgets.REMOTE_MAX_MS,
                    Budgets.REMOTE_START_MS,
                    Budgets.REMOTE_REBUFFER_MS
                )
            )
        }
    }
}
