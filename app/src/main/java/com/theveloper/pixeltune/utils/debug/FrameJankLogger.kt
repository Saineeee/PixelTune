package com.theveloper.pixeltune.utils.debug

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import androidx.metrics.performance.JankStats
import androidx.navigation.NavController

/**
 * Debug-only frame-timing monitor (JankStats) that logs per-screen frame-time
 * histograms to Logcat.
 *
 * Wiring: [install] is called from Application.onCreate() behind a
 * `BuildConfig.DEBUG` guard, and [attachNavController] from the main
 * composable behind the same guard. In release builds both call sites are
 * unreachable constant-false branches, so R8 removes them and the
 * androidx.metrics classes with them — zero release-build impact.
 *
 * Output format (one line per screen per report, tag `PixelTuneJank`):
 * `screen=<route> frames=<n> jank=<j> (<pct>%) | <=16ms:a 17-32:b 33-48:c 49-64:d >64:e`
 * A report is emitted every [REPORT_EVERY_FRAMES] frames recorded for a
 * screen, so at 60 fps that is roughly one report per 10 seconds of
 * on-screen time — enough signal for before/after comparisons without
 * flooding the log.
 */
object FrameJankLogger {

    private const val LOG_TAG = "PixelTuneJank"

    /** Frame duration bucket upper bounds, in milliseconds. */
    private val BUCKET_BOUNDS_MS = longArrayOf(16, 32, 48, 64)

    /** Frames recorded per screen before a histogram line is logged. */
    private const val REPORT_EVERY_FRAMES = 600

    private class Histogram {
        var frames = 0L
        var jank = 0L
        val buckets = LongArray(BUCKET_BOUNDS_MS.size + 1)
    }

    /** Screen label -> histogram. Only touched from the main thread. */
    private val histograms = LinkedHashMap<String, Histogram>()

    /** Current screen label; starts as the activity name until nav attaches. */
    private var currentScreen: String = "unknown"

    /**
     * Starts tracking every activity's window. Call exactly once, from
     * Application.onCreate(), only in debug builds.
     */
    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    currentScreen = activity.javaClass.simpleName
                    trackWindow(activity)
                }

                override fun onActivityStarted(activity: Activity) {}
                override fun onActivityResumed(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            }
        )
        Log.i(
            LOG_TAG,
            "Frame jank monitor installed (buckets: ${BUCKET_BOUNDS_MS.joinToString("/") { "${it}ms" }})"
        )
    }

    /**
     * Refines the screen label with navigation destinations (route names),
     * giving per-screen rather than per-activity histograms. Call once per
     * NavController instance, only in debug builds.
     */
    fun attachNavController(navController: NavController) {
        navController.addOnDestinationChangedListener { _, destination, _ ->
            currentScreen = destination.route ?: "destination-${destination.id}"
        }
    }

    private fun trackWindow(activity: Activity) {
        val stats = JankStats.createAndTrack(activity.window) { frame ->
            record(
                isJank = frame.isJank,
                durationMs = frame.frameDurationUiNanos / 1_000_000L
            )
        }
        // Keep the library default heuristic (2x the refresh-rate-derived
        // frame budget); the histogram buckets give the fine-grained view.
        stats.jankHeuristicMultiplier = 2f
    }

    private fun record(isJank: Boolean, durationMs: Long) {
        val histogram = histograms.getOrPut(currentScreen) { Histogram() }
        histogram.frames++
        if (isJank) histogram.jank++

        val bucket = BUCKET_BOUNDS_MS.indexOfFirst { durationMs <= it }
            .let { if (it >= 0) it else BUCKET_BOUNDS_MS.size }
        histogram.buckets[bucket]++

        if (histogram.frames % REPORT_EVERY_FRAMES == 0L) {
            report(currentScreen, histogram)
        }
    }

    private fun report(screen: String, histogram: Histogram) {
        val jankPct = if (histogram.frames > 0) {
            100f * histogram.jank / histogram.frames
        } else {
            0f
        }
        Log.i(
            LOG_TAG,
            "screen=$screen frames=${histogram.frames} jank=${histogram.jank} " +
                "(${"%.1f".format(jankPct)}%) | " +
                "<=16ms:${histogram.buckets[0]} 17-32ms:${histogram.buckets[1]} " +
                "33-48ms:${histogram.buckets[2]} 49-64ms:${histogram.buckets[3]} " +
                ">64ms:${histogram.buckets[4]}"
        )
    }
}
