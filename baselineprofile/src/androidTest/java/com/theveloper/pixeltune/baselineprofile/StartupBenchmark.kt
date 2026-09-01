package com.theveloper.pixeltune.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

/**
 * Startup profile generator for PixelTune release builds.
 *
 * Run via ./gradlew :app:generateBaselineProfile with a connected device or
 * emulator; the androidx.baselineprofile plugin writes the resulting rules
 * into app/src/release/generated/baselineProfiles/ and AGP embeds them into
 * release builds through the ProfileInstaller.
 *
 * The target app id is injected as the `targetAppId` instrumentation
 * argument by this module's androidComponents wiring, so the benchmark
 * follows whatever variant is actually under test.
 */
class StartupBenchmark {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun startup() = baselineProfileRule.collect(PACKAGE_NAME) {
        // Standard startup profile: navigate home, cold-start the app and
        // wait for the first idle frame so the profile covers the startup
        // critical path (Application init, Hilt graph, first composition).
        pressHome()
        startActivityAndWait()
        device.waitForIdle()
    }
}

private val PACKAGE_NAME: String =
    InstrumentationRegistry.getArguments().getString("targetAppId")
        ?: error("targetAppId instrumentation argument is not set")
