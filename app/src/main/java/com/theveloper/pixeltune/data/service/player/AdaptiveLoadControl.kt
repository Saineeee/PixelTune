package com.theveloper.pixeltune.data.service.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator
import java.util.concurrent.ConcurrentHashMap

/**
 * PERF(player): per-source-type buffering profiles.
 *
 * The engine previously applied a single engine-wide LoadControl tuned for
 * the localhost cloud-streaming proxies. That configuration wastes start
 * latency and memory on local files (which need no network headroom) and
 * lumps two very different network paths (proxied personal-cloud storage
 * vs. remote music streaming) into one buffer size.
 *
 * [SourceBufferProfile] selects buffering constants per source type.
 * Because ExoPlayer fixes the LoadControl at player construction, profiles
 * are switched at runtime by [AdaptiveLoadControl], a stateless dispatcher
 * that delegates every [LoadControl] call to the DefaultLoadControl built
 * for the currently active profile. All delegates share one
 * [DefaultAllocator], so buffer memory is a single pool regardless of how
 * often the profile changes.
 */
@OptIn(UnstableApi::class)
enum class SourceBufferProfile(
    /** Forward-buffer floor: loading always continues below this. */
    val minBufferMs: Int,
    /** Forward-buffer ceiling: loading pauses at/above this. */
    val maxBufferMs: Int,
    /** Buffered media required before playback may start. */
    val bufferForPlaybackMs: Int,
    /** Buffered media required before playback resumes after an underrun. */
    val bufferForPlaybackAfterRebufferMs: Int,
    /** Media retained behind the playhead for instant backward seeks. */
    val backBufferMs: Int
) {
    /**
     * Local files on device storage. Disk reads are effectively free and
     * time-to-first-audio dominates, so the start gate is halved versus the
     * remote profiles (500 ms is still above codec warm-up jitter) and the
     * forward/back windows are kept small: with ~200 kbps-per-segment audio
     * this caps resident buffer memory at a few hundred KB while any seek
     * re-reads from disk in microseconds.
     */
    LOCAL_FILE(
        minBufferMs = 2_000,
        maxBufferMs = 10_000,
        bufferForPlaybackMs = 500,
        bufferForPlaybackAfterRebufferMs = 1_000,
        backBufferMs = 2_000
    ),

    /**
     * Proxied personal-cloud streams (Telegram file proxy, Google Drive).
     * Throughput is bursty (Drive API throttling, Telegram DC rate limits)
     * but loyal - once connected, bytes keep flowing without the
     * re-resolution dance of the streaming providers. A moderate 15-45 s
     * window absorbs those bursts without the memory cost of the remote
     * profile, and the 1 s start gate keeps tap-to-audio snappy because
     * the proxy usually delivers its first bytes quickly.
     */
    CLOUD_DRIVE(
        minBufferMs = 15_000,
        maxBufferMs = 45_000,
        bufferForPlaybackMs = 1_000,
        bufferForPlaybackAfterRebufferMs = 2_000,
        backBufferMs = 15_000
    ),

    /**
     * Remote music streaming (YouTube / SoundCloud / Netease) via the
     * localhost proxies. Upstream URLs are resolved through NewPipe and can
     * expire or be throttled mid-stream, so this keeps the generous 30-60 s
     * forward window the engine has been battle-tested with, plus a 30 s
     * back-buffer for fast rewinds without re-resolving. The 2 s / 3 s
     * start/rebuffer gates are the values already proven here to resume
     * playback ~2.5x sooner than the older 5 s gates.
     */
    REMOTE_STREAM(
        minBufferMs = 30_000,
        maxBufferMs = 60_000,
        bufferForPlaybackMs = 2_000,
        bufferForPlaybackAfterRebufferMs = 3_000,
        backBufferMs = 30_000
    )
}

/**
 * Builds the [DefaultLoadControl] for a [SourceBufferProfile].
 *
 * All delegates share [allocator] so buffer memory is one pool across
 * profile switches, and time thresholds are prioritized over byte-size
 * thresholds for both the local and streaming tiers: this is an audio-only
 * app where the byte calculators are tuned for video, and making time
 * authoritative also keeps loading decisions a pure function of buffered
 * duration, so a mid-stream profile switch can never stall the loader.
 *
 * The local-playback tier of every delegate is set to the LOCAL_FILE
 * constants: media3 itself detects local URIs (file/content/asset/...)
 * per query, so local files get minimal buffering no matter which profile
 * is currently active.
 */
@OptIn(UnstableApi::class)
private fun buildProfileLoadControl(
    profile: SourceBufferProfile,
    allocator: DefaultAllocator
): DefaultLoadControl {
    val local = SourceBufferProfile.LOCAL_FILE
    return DefaultLoadControl.Builder()
        .setAllocator(allocator)
        .setBufferDurationsMs(
            profile.minBufferMs,
            profile.maxBufferMs,
            profile.bufferForPlaybackMs,
            profile.bufferForPlaybackAfterRebufferMs
        )
        .setBufferDurationsMsForLocalPlayback(
            local.minBufferMs,
            local.maxBufferMs,
            local.bufferForPlaybackMs,
            local.bufferForPlaybackAfterRebufferMs
        )
        .setPrioritizeTimeOverSizeThresholdsForStreaming(true)
        .setPrioritizeTimeOverSizeThresholdsForLocalPlayback(true)
        .setBackBuffer(profile.backBufferMs, /* retainBackBufferFromKeyframe = */ false)
        .build()
}

/**
 * A [LoadControl] whose active profile can be switched at media-item
 * boundaries via [switchProfile].
 *
 * Implementation notes:
 *  - Every call is forwarded to the delegate built for the active profile;
 *    the delegates share one allocator (see [buildProfileLoadControl]).
 *  - The wrapper tracks which players registered through [onPrepared] so a
 *    mid-timeline switch migrates the registration (stop on the old
 *    delegate, re-prepare on the new one). Without this, the new delegate
 *    would reject the next shouldContinueLoading call for an unregistered
 *    player.
 *  - With time-over-size thresholds enabled, shouldContinueLoading is
 *    effectively a pure function of buffered duration, so a profile switch
 *    racing the loader thread can only change thresholds, never wedge the
 *    loading state machine.
 */
@OptIn(UnstableApi::class)
class AdaptiveLoadControl @JvmOverloads constructor(
    initialProfile: SourceBufferProfile = SourceBufferProfile.REMOTE_STREAM
) : LoadControl {

    private val sharedAllocator = DefaultAllocator(
        /* trimOnReset = */ true,
        C.DEFAULT_BUFFER_SEGMENT_SIZE
    )

    private val delegates: Map<SourceBufferProfile, DefaultLoadControl> =
        SourceBufferProfile.entries.associateWith {
            buildProfileLoadControl(it, sharedAllocator)
        }

    /** Player IDs currently registered on [active]; guards switch migration. */
    private val registeredPlayers = ConcurrentHashMap<PlayerId, Int>()

    @Volatile
    private var activeProfile: SourceBufferProfile = initialProfile

    private val active: DefaultLoadControl
        get() = delegates.getValue(activeProfile)

    /**
     * Switches the active buffering profile. Safe to call from any thread;
     * must only be called at media-item boundaries (the player re-prepares
     * or re-selects tracks around those anyway).
     */
    fun switchProfile(profile: SourceBufferProfile) {
        if (profile == activeProfile) return
        synchronized(this) {
            if (profile == activeProfile) return
            val old = active
            val new = delegates.getValue(profile)
            // Migrate live player registrations so the new delegate accepts
            // the next loading query for each registered player.
            for ((playerId, _) in registeredPlayers) {
                old.onStopped(playerId)
                new.onPrepared(playerId)
            }
            activeProfile = profile
        }
    }

    override fun onPrepared(playerId: PlayerId) {
        registeredPlayers.merge(playerId, 1, Int::plus)
        active.onPrepared(playerId)
    }

    override fun onStopped(playerId: PlayerId) {
        registeredPlayers.compute(playerId) { _, count ->
            val next = (count ?: 1) - 1
            if (next > 0) next else null
        }
        active.onStopped(playerId)
    }

    override fun onReleased(playerId: PlayerId) {
        registeredPlayers.remove(playerId)
        active.onReleased(playerId)
    }

    override fun getAllocator(playerId: PlayerId): Allocator =
        active.getAllocator(playerId)

    override fun getBackBufferDurationUs(playerId: PlayerId): Long =
        active.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean =
        active.retainBackBufferFromKeyframe(playerId)

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean =
        active.shouldContinueLoading(parameters)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean =
        active.shouldStartPlayback(parameters)

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<ExoTrackSelection?>
    ) {
        active.onTracksSelected(parameters, trackGroups, trackSelections)
    }
}

/**
 * Classifies a (possibly already-resolved) playback [Uri] into its
 * [SourceBufferProfile]:
 *  - no scheme, or file/content/asset-style schemes -> local file;
 *  - telegram:// / gdrive:// source URIs -> proxied personal cloud;
 *  - resolved loopback proxy URLs by their route path (/youtube,
 *    /soundcloud, /netease -> remote streaming; /gdrive, /stream ->
 *    cloud drive), and any non-loopback http(s) URL -> remote streaming.
 *
 * Null or unclassifiable URIs conservatively map to REMOTE_STREAM, the
 * profile with the strongest stall protection.
 */
@OptIn(UnstableApi::class)
fun bufferProfileFor(uri: Uri?): SourceBufferProfile {
    if (uri == null) return SourceBufferProfile.REMOTE_STREAM
    return when (uri.scheme?.lowercase()) {
        null, "file", "content", "data", "asset", "android.resource", "rawresource" ->
            SourceBufferProfile.LOCAL_FILE
        "telegram", "gdrive" -> SourceBufferProfile.CLOUD_DRIVE
        else -> {
            val isLoopbackProxy = uri.host.equals("127.0.0.1", ignoreCase = true) ||
                uri.host.equals("localhost", ignoreCase = true)
            if (isLoopbackProxy) {
                when (uri.pathSegments.firstOrNull()) {
                    "gdrive", "stream" -> SourceBufferProfile.CLOUD_DRIVE
                    else -> SourceBufferProfile.REMOTE_STREAM
                }
            } else {
                SourceBufferProfile.REMOTE_STREAM
            }
        }
    }
}
