package com.theveloper.pixeltune.performance

import android.view.Window

/**
 * Release no-op twin of the debug-only frame stats monitor.
 *
 * Release builds never link androidx.metrics and never track frames; this
 * object only exists so shared call sites (MainActivity, navigation)
 * compile in both variants, and R8 strips it entirely from minified
 * release builds. Zero release-build impact.
 */
object DebugFrameStatsMonitor {

    @JvmStatic
    fun attach(window: Window) {
        // Intentional no-op in release builds.
    }

    @JvmStatic
    fun setScreen(label: String) {
        // Intentional no-op in release builds.
    }
}
