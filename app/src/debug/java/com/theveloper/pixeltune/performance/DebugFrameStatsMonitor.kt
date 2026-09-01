package com.theveloper.pixeltune.performance

import android.util.Log
import android.view.Window
import androidx.metrics.performance.JankStats
import java.util.Arrays

/**
 * PERF(debug): per-screen frame-time histogram logger for DEBUG builds only.
 *
 * Attaches JankStats to the activity window and buckets every frame's UI
 * duration under the CURRENT screen label (set from navigation). Every
 * [FRAMES_PER_SUMMARY] frames - and on screen switches - it logs one line
 * per screen to Logcat with jank rate, percentiles and a fixed-bin
 * duration histogram in the format:
 *
 * ```
 * FrameStats > screen=Library frames=300 jank=4.3% p50=4.2ms p90=14.9ms
 * p95=22.1ms p99=38.4ms max=62.9ms | bins[0-8]=221 [8-16]=57 [16-33]=13
 * [33-50]=5 [50-100]=3 [100+]=1
 * ```
 *
 * Bin edges are chosen around the display cadences that matter for jank
 * diagnosis: 8ms (~half of a 16.7ms frame budget), 16.7ms (one frame),
 * 33ms (two dropped frames), 50ms and 100ms (severe stalls).
 *
 * This implementation lives in the debug source set and is never compiled
 * into release builds; the release source set carries a no-op twin.
 */
object DebugFrameStatsMonitor {

    private const val TAG = "FrameStats"
    private const val FRAMES_PER_SUMMARY = 300
    private const val MIN_FRAMES_FOR_FLUSH = 50

    /** Fixed histogram bin edges in milliseconds (last bin is open-ended). */
    private val BIN_EDGES_MS = longArrayOf(8, 16, 33, 50, 100)
    private val BIN_LABELS = arrayOf("0-8", "8-16", "16-33", "33-50", "50-100", "100+")

    private var jankStats: JankStats? = null
    private var attachedWindow: Window? = null

    // Per-screen accumulation; the listener runs on the window's thread and
    // setScreen is called from composition, so all state is main-thread only.
    private var screenLabel: String = "unknown"
    private var frameCount = 0
    private var jankCount = 0
    private var durationsMs = LongArray(FRAMES_PER_SUMMARY)

    /** Attaches frame tracking to [window] (idempotent). */
    @JvmStatic
    fun attach(window: Window) {
        if (jankStats != null) {
            // Re-attach to a new window (activity recreation) by dropping the
            // previous instance; statistics restart from zero.
            jankStats?.isTrackingEnabled = false
        }
        attachedWindow = window
        resetAccumulators()
        jankStats = JankStats.createAndTrack(window) { frameData ->
            onFrame(frameData.frameDurationUiNanos, frameData.isJank)
        }.also { it.isTrackingEnabled = true }
        Log.d(TAG, "attached to window $window")
    }

    /** Switches the active screen label, flushing the previous screen's stats. */
    @JvmStatic
    fun setScreen(label: String) {
        if (label == screenLabel) return
        flush(false)
        screenLabel = label
        Log.d(TAG, "screen -> $label")
    }

    private fun onFrame(durationNanos: Long, isJank: Boolean) {
        if (frameCount == durationsMs.size) {
            flush(true)
        }
        val durationMs = durationNanos / 1_000_000
        durationsMs[frameCount] = durationMs
        frameCount++
        if (isJank) jankCount++
    }

    private fun flush(becauseFull: Boolean) {
        if (frameCount < MIN_FRAMES_FOR_FLUSH) {
            resetAccumulators()
            return
        }
        val samples = durationsMs.copyOf(frameCount)
        Arrays.sort(samples)

        fun percentile(p: Double): Long {
            val index = ((frameCount - 1) * p).toInt().coerceIn(0, frameCount - 1)
            return samples[index]
        }

        val bins = IntArray(BIN_LABELS.size)
        for (ms in samples) {
            var bin = BIN_EDGES_MS.indexOfFirst { ms < it }
            if (bin == -1) bin = BIN_LABELS.size - 1
            bins[bin]++
        }
        val binsText = BIN_LABELS.indices.joinToString(" ") { "${BIN_LABELS[it]}=${bins[it]}" }

        Log.d(
            TAG,
            "screen=$screenLabel frames=$frameCount jank=" +
                "%.1f%%".format(jankCount * 100.0 / frameCount) +
                " p50=${percentile(0.50)}ms p90=${percentile(0.90)}ms" +
                " p95=${percentile(0.95)}ms p99=${percentile(0.99)}ms" +
                " max=${samples[frameCount - 1]}ms | bins$binsText" +
                (if (becauseFull) "" else " (screen switch)")
        )
        resetAccumulators()
    }

    private fun resetAccumulators() {
        frameCount = 0
        jankCount = 0
    }
}
