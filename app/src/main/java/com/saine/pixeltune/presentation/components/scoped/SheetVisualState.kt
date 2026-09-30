package com.saine.pixeltune.presentation.components.scoped

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.saine.pixeltune.data.preferences.NavBarStyle
import com.saine.pixeltune.presentation.viewmodel.PlayerSheetState

private const val PREDICTIVE_BACK_SWIPE_EDGE_LEFT = 0
private const val PREDICTIVE_BACK_SWIPE_EDGE_RIGHT = 1

/**
 * Per-frame morphing visual values of the player sheet, exposed as [State]s so they can be read
 * inside layout (`Modifier.layout` / `Modifier.offset {}` lambdas) and draw
 * (`graphicsLayer {}` / `drawBehind {}`) blocks without subscribing the sheet host's composition
 * scope to every animation frame.
 *
 * Callers that still read `.value` in composition (legacy V1 path) get the exact same numbers as
 * before — but the V2 host reads them only from deferred modifier blocks, which removes the
 * per-frame whole-sheet recomposition that made the drag / expand / collapse gestures janky.
 */
internal class SheetVisualState(
    val currentBottomPadding: State<Dp>,
    val playerContentAreaHeight: State<Dp>,
    val visualSheetTranslationY: State<Float>,
    val overallSheetTopCornerRadius: State<Dp>,
    val playerContentActualBottomRadius: State<Dp>,
    val currentHorizontalPaddingStart: State<Dp>,
    val currentHorizontalPaddingEnd: State<Dp>
)

@Composable
internal fun rememberSheetVisualState(
    showPlayerContentArea: Boolean,
    collapsedStateHorizontalPadding: Dp,
    predictiveBackCollapseProgress: Float,
    predictiveBackSwipeEdge: Int?,
    currentSheetContentState: PlayerSheetState,
    playerContentExpansionFraction: Animatable<Float, AnimationVector1D>,
    containerHeight: Dp,
    currentSheetTranslationY: Animatable<Float, AnimationVector1D>,
    sheetCollapsedTargetY: Float,
    navBarStyle: String,
    navBarCornerRadiusDp: Dp,
    isNavBarHidden: Boolean,
    isPlaying: Boolean,
    hasCurrentSong: Boolean,
    swipeDismissProgress: Float
): SheetVisualState {
    val currentBottomPadding = remember(
        showPlayerContentArea,
        collapsedStateHorizontalPadding,
        predictiveBackCollapseProgress,
        currentSheetContentState
    ) {
        derivedStateOf {
            if (predictiveBackCollapseProgress > 0f &&
                showPlayerContentArea &&
                currentSheetContentState == PlayerSheetState.EXPANDED
            ) {
                lerp(0.dp, collapsedStateHorizontalPadding, predictiveBackCollapseProgress)
            } else {
                0.dp
            }
        }
    }

    val playerContentAreaHeight = remember(
        showPlayerContentArea,
        playerContentExpansionFraction,
        containerHeight
    ) {
        derivedStateOf {
            if (showPlayerContentArea) {
                lerp(
                    start = com.saine.pixeltune.presentation.components.MiniPlayerHeight,
                    stop = containerHeight,
                    fraction = playerContentExpansionFraction.value
                )
            } else {
                0.dp
            }
        }
    }

    val visualSheetTranslationY = remember {
        derivedStateOf {
            currentSheetTranslationY.value * (1f - predictiveBackCollapseProgress) +
                (sheetCollapsedTargetY * predictiveBackCollapseProgress)
        }
    }

    val overallSheetTopCornerRadius = remember(
        showPlayerContentArea,
        playerContentExpansionFraction,
        predictiveBackCollapseProgress,
        currentSheetContentState,
        navBarStyle,
        navBarCornerRadiusDp,
        isNavBarHidden
    ) {
        derivedStateOf {
            if (showPlayerContentArea) {
                val collapsedCornerTarget = if (navBarStyle == NavBarStyle.FULL_WIDTH) {
                    32.dp
                } else if (isNavBarHidden) {
                    60.dp
                } else {
                    navBarCornerRadiusDp
                }

                if (predictiveBackCollapseProgress > 0f &&
                    currentSheetContentState == PlayerSheetState.EXPANDED
                ) {
                    val expandedCorner = 0.dp
                    lerp(expandedCorner, collapsedCornerTarget, predictiveBackCollapseProgress)
                } else {
                    val fraction = playerContentExpansionFraction.value
                    val expandedTarget = 0.dp
                    lerp(collapsedCornerTarget, expandedTarget, fraction)
                }
            } else {
                if (navBarStyle == NavBarStyle.FULL_WIDTH) {
                    0.dp
                } else if (isNavBarHidden) {
                    60.dp
                } else {
                    navBarCornerRadiusDp
                }
            }
        }
    }

    val playerContentActualBottomRadius = remember(
        navBarStyle,
        showPlayerContentArea,
        playerContentExpansionFraction,
        isPlaying,
        hasCurrentSong,
        predictiveBackCollapseProgress,
        currentSheetContentState,
        swipeDismissProgress,
        isNavBarHidden,
        navBarCornerRadiusDp
    ) {
        derivedStateOf {
            if (navBarStyle == NavBarStyle.FULL_WIDTH) {
                val fraction = playerContentExpansionFraction.value
                return@derivedStateOf lerp(32.dp, 0.dp, fraction)
            }

            val calculatedNormally =
                if (predictiveBackCollapseProgress > 0f &&
                    showPlayerContentArea &&
                    currentSheetContentState == PlayerSheetState.EXPANDED
                ) {
                    val expandedRadius = 0.dp
                    val collapsedRadiusTarget = if (isNavBarHidden) 60.dp else 12.dp
                    lerp(expandedRadius, collapsedRadiusTarget, predictiveBackCollapseProgress)
                } else {
                    if (showPlayerContentArea) {
                        val fraction = playerContentExpansionFraction.value
                        val collapsedRadius = if (isNavBarHidden) 60.dp else 12.dp
                        if (fraction < 0.2f) {
                            lerp(collapsedRadius, 26.dp, (fraction / 0.2f).coerceIn(0f, 1f))
                        } else {
                            lerp(26.dp, 0.dp, ((fraction - 0.2f) / 0.8f).coerceIn(0f, 1f))
                        }
                    } else {
                        if (!isPlaying || !hasCurrentSong) {
                            if (isNavBarHidden) 32.dp else navBarCornerRadiusDp
                        } else {
                            if (isNavBarHidden) 32.dp else 12.dp
                        }
                    }
                }

            if (currentSheetContentState == PlayerSheetState.COLLAPSED &&
                swipeDismissProgress > 0f &&
                showPlayerContentArea &&
                playerContentExpansionFraction.value < 0.01f
            ) {
                val baseCollapsedRadius = if (isNavBarHidden) 32.dp else 12.dp
                lerp(baseCollapsedRadius, navBarCornerRadiusDp, swipeDismissProgress)
            } else {
                calculatedNormally
            }
        }
    }

    val actualCollapsedStateHorizontalPadding =
        if (navBarStyle == NavBarStyle.FULL_WIDTH) 14.dp else collapsedStateHorizontalPadding

    val currentHorizontalPadding = remember(
        showPlayerContentArea,
        playerContentExpansionFraction,
        actualCollapsedStateHorizontalPadding
    ) {
        derivedStateOf {
            if (showPlayerContentArea) {
                lerp(
                    actualCollapsedStateHorizontalPadding,
                    0.dp,
                    playerContentExpansionFraction.value
                )
            } else {
                actualCollapsedStateHorizontalPadding
            }
        }
    }

    val currentHorizontalPaddingStart = remember(
        showPlayerContentArea,
        currentSheetContentState,
        predictiveBackCollapseProgress,
        predictiveBackSwipeEdge,
        actualCollapsedStateHorizontalPadding,
        currentHorizontalPadding
    ) {
        derivedStateOf {
            if (predictiveBackCollapseProgress > 0f &&
                showPlayerContentArea &&
                currentSheetContentState == PlayerSheetState.EXPANDED
            ) {
                val gestureSidePadding = lerp(
                    start = 0.dp,
                    stop = actualCollapsedStateHorizontalPadding,
                    fraction = predictiveBackCollapseProgress
                )

                when (predictiveBackSwipeEdge) {
                    PREDICTIVE_BACK_SWIPE_EDGE_LEFT -> gestureSidePadding
                    PREDICTIVE_BACK_SWIPE_EDGE_RIGHT -> 0.dp
                    else -> currentHorizontalPadding.value
                }
            } else {
                currentHorizontalPadding.value
            }
        }
    }

    val currentHorizontalPaddingEnd = remember(
        showPlayerContentArea,
        currentSheetContentState,
        predictiveBackCollapseProgress,
        predictiveBackSwipeEdge,
        actualCollapsedStateHorizontalPadding,
        currentHorizontalPadding
    ) {
        derivedStateOf {
            if (predictiveBackCollapseProgress > 0f &&
                showPlayerContentArea &&
                currentSheetContentState == PlayerSheetState.EXPANDED
            ) {
                val gestureSidePadding = lerp(
                    start = 0.dp,
                    stop = actualCollapsedStateHorizontalPadding,
                    fraction = predictiveBackCollapseProgress
                )

                when (predictiveBackSwipeEdge) {
                    PREDICTIVE_BACK_SWIPE_EDGE_LEFT -> 0.dp
                    PREDICTIVE_BACK_SWIPE_EDGE_RIGHT -> gestureSidePadding
                    else -> currentHorizontalPadding.value
                }
            } else {
                currentHorizontalPadding.value
            }
        }
    }

    return SheetVisualState(
        currentBottomPadding = currentBottomPadding,
        playerContentAreaHeight = playerContentAreaHeight,
        visualSheetTranslationY = visualSheetTranslationY,
        overallSheetTopCornerRadius = overallSheetTopCornerRadius,
        playerContentActualBottomRadius = playerContentActualBottomRadius,
        currentHorizontalPaddingStart = currentHorizontalPaddingStart,
        currentHorizontalPaddingEnd = currentHorizontalPaddingEnd
    )
}
