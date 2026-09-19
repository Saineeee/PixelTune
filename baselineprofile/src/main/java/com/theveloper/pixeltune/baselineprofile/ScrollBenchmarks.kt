package com.theveloper.pixeltune.baselineprofile

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * PERF(smoothness): scroll/frame-timing benchmarks for the app's main
 * scrollable surfaces.
 *
 * Until this file existed, `:baselineprofile` only measured startup timing —
 * none of the hot scrollable screens had any jank metrics, so smoothness
 * regressions/fixes could not be quantified. These benchmarks produce
 * frame-duration / jank statistics (Macrobenchmark reports frames over
 * deadline, 50th/90th/95th/99th percentiles) for:
 *
 *  - Home feed (mixed cards + recently played)
 *  - Library Songs tab (the main song list)
 *  - Library pager swipe across tabs
 *  - Search results (when a query with results is available)
 *
 * Run them before/after UI performance changes, e.g.:
 *
 *   ./gradlew :baselineprofile:connectedBenchmarkAndroidTest \
 *       -P android.testInstrumentationRunnerArguments.class=\
 * com.theveloper.pixeltune.baselineprofile.ScrollBenchmarks
 *
 * All navigation is resilient (selectors may not match an empty library) —
 * on a device without media the scroll simply no-ops and the metrics reflect
 * the empty-list frames, which is still comparable before/after.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ScrollBenchmarks {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    private val packageName: String
        get() = InstrumentationRegistry.getArguments().getString("targetAppId")
            ?: "com.saine.pixeltune"

    // ---- Benchmark cases -------------------------------------------------

    @Test
    fun scrollHomeFeed() = scrollBenchmark("Home feed scroll") {
        clickTab("Home|Inicio")
        waitForContent()
        repeat(3) {
            flingListUp()
            flingListDown()
        }
    }

    @Test
    fun scrollLibrarySongs() = scrollBenchmark("Library Songs scroll") {
        clickTab("Library|Biblioteca")
        waitForContent()
        repeat(4) {
            flingListUp()
            flingListDown()
        }
    }

    @Test
    fun swipeLibraryPager() = scrollBenchmark("Library pager swipes") {
        clickTab("Library|Biblioteca")
        waitForContent()
        repeat(4) {
            swipeLeft()
            waitForContent()
            blindScroll()
            swipeRight()
            waitForContent()
        }
    }

    @Test
    fun scrollSearchResults() = scrollBenchmark("Search results scroll") {
        clickTab("Search|Buscar")
        waitForContent()
        // Type a broad query so the local results list has content to scroll.
        val queryField = device.wait(
            Until.findObject(By.res(packageName, "search_field")),
            2_000
        ) ?: device.wait(
            Until.findObject(By.clazz("android.widget.EditText")),
            2_000
        )
        if (queryField != null) {
            queryField.text = "a"
            Thread.sleep(1_500)
            repeat(3) {
                flingListUp()
                flingListDown()
            }
        } else {
            // Fallback: scroll whatever the search screen shows (history /
            // genre grid) — still comparable across runs.
            repeat(3) {
                flingListUp()
                flingListDown()
            }
        }
    }

    // ---- Harness ---------------------------------------------------------

    private fun scrollBenchmark(
        label: String,
        compilationMode: CompilationMode = CompilationMode.Partial(),
        case: MacrobenchmarkScope.() -> Unit
    ) = benchmarkRule.measureRepeated(
        packageName = packageName,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = compilationMode,
        iterations = 3,
        startupMode = StartupMode.WARM,
        setupBlock = {
            grantMediaPermissions()
            pressHome()
        }
    ) {
        // WARM start so scroll metrics aren't dominated by cold-start IO.
        startActivityAndWait()
        handlePermissionDialogs()
        handleOnboarding()
        runCase(label, case)
        pressHome()
    }

    private fun MacrobenchmarkScope.runCase(label: String, block: MacrobenchmarkScope.() -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            android.util.Log.w("ScrollBenchmarks", "Case '$label' degraded: ${t.message}")
        }
    }

    // ---- Resilient helpers (mirrors BaselineProfileGenerator's approach) --

    private fun MacrobenchmarkScope.grantMediaPermissions() {
        val permissions = listOf(
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.READ_MEDIA_AUDIO"
        )
        permissions.forEach {
            try {
                device.executeShellCommand("pm grant $packageName $it")
            } catch (_: Exception) {
            }
        }
    }

    private fun MacrobenchmarkScope.handlePermissionDialogs() {
        val pattern = Pattern.compile(
            "Allow|Permitir|While using|Mientras",
            Pattern.CASE_INSENSITIVE
        )
        repeat(3) {
            device.findObject(By.text(pattern))?.click()
            Thread.sleep(400)
        }
    }

    private fun MacrobenchmarkScope.handleOnboarding() {
        val pattern = Pattern.compile(
            "Next|Continue|Skip|Done|Siguiente|Omitir|Empezar",
            Pattern.CASE_INSENSITIVE
        )
        repeat(4) {
            device.findObject(By.text(pattern))?.click()
            Thread.sleep(600)
        }
    }

    private fun MacrobenchmarkScope.clickTab(tabNamePattern: String) {
        val pattern = Pattern.compile(tabNamePattern, Pattern.CASE_INSENSITIVE)
        val tab = device.findObject(By.desc(pattern)) ?: device.findObject(By.text(pattern))
        tab?.let {
            device.click(it.visibleCenter.x, it.visibleCenter.y)
            Thread.sleep(1_200)
        }
    }

    private fun MacrobenchmarkScope.waitForContent() {
        Thread.sleep(800)
    }

    private fun MacrobenchmarkScope.flingListUp() {
        val midX = device.displayWidth / 2
        device.swipe(midX, (device.displayHeight * 0.72).toInt(), midX, (device.displayHeight * 0.28).toInt(), 12)
        Thread.sleep(900)
    }

    private fun MacrobenchmarkScope.flingListDown() {
        val midX = device.displayWidth / 2
        device.swipe(midX, (device.displayHeight * 0.28).toInt(), midX, (device.displayHeight * 0.72).toInt(), 12)
        Thread.sleep(900)
    }

    private fun MacrobenchmarkScope.blindScroll() {
        val midX = device.displayWidth / 2
        device.swipe(midX, (device.displayHeight * 0.7).toInt(), midX, (device.displayHeight * 0.3).toInt(), 25)
        Thread.sleep(700)
        device.swipe(midX, (device.displayHeight * 0.3).toInt(), midX, (device.displayHeight * 0.7).toInt(), 25)
        Thread.sleep(700)
    }

    private fun MacrobenchmarkScope.swipeLeft() {
        val y = (device.displayHeight * 0.5).toInt()
        device.swipe((device.displayWidth * 0.85).toInt(), y, (device.displayWidth * 0.15).toInt(), y, 25)
        Thread.sleep(800)
    }

    private fun MacrobenchmarkScope.swipeRight() {
        val y = (device.displayHeight * 0.5).toInt()
        device.swipe((device.displayWidth * 0.15).toInt(), y, (device.displayWidth * 0.85).toInt(), y, 25)
        Thread.sleep(800)
    }
}
