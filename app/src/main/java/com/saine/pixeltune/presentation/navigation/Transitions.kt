package com.saine.pixeltune.presentation.navigation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/**
 * PERF(nav-jank): the pop transitions previously used scaleIn/scaleOut on ENTIRE screens.
 * A scale transform forces the full-screen layer (including the underlying Home screen with
 * all its artwork) to be re-rasterized on every frame of the transition — the main cause of
 * the janky settings open/close feel. The pops now use slide + fade only (Material's standard
 * shared-axis pattern), which the compositor can cheaply interpolate.
 *
 * Duration is also trimmed from 500 ms to 350 ms — the previous window kept BOTH screens
 * compositing for half a second.
 */
const val TRANSITION_DURATION = 350
private val TRANSITION_EASING = FastOutSlowInEasing

// Push: Enter from Right
fun enterTransition() = slideInHorizontally(
    animationSpec = tween(TRANSITION_DURATION, easing = TRANSITION_EASING),
    initialOffsetX = { it }
)

// Push: Exit to Left with Fade
fun exitTransition() = slideOutHorizontally(
    animationSpec = tween(TRANSITION_DURATION, easing = TRANSITION_EASING),
    targetOffsetX = { -it / 3 }
) + fadeOut(
    animationSpec = tween(TRANSITION_DURATION, easing = TRANSITION_EASING)
)

// Pop: Enter from Left (Parallax, No Fade)
fun popEnterTransition() = slideInHorizontally(
    animationSpec = tween(TRANSITION_DURATION, easing = TRANSITION_EASING),
    initialOffsetX = { -it / 3 } // Start from Left (parallax)
)

// Pop: Exit to Right with Fade (scale removed — full-screen re-rasterization jank)
fun popExitTransition() = slideOutHorizontally(
    animationSpec = tween(TRANSITION_DURATION, easing = TRANSITION_EASING),
    targetOffsetX = { it }
) + fadeOut(
    animationSpec = tween(TRANSITION_DURATION, easing = TRANSITION_EASING)
)
