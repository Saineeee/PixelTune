package com.saine.pixeltune.presentation.components.scoped

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.saine.pixeltune.presentation.viewmodel.PlayerSheetState
import kotlinx.coroutines.CoroutineScope
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

internal class SheetInteractionState(
    /**
     * Card shape as a [State] so it can be consumed inside draw-phase modifier blocks
     * (`graphicsLayer { shape = ... }`). The shape is rebuilt only when the corner radii /
     * smooth-corner toggle actually change, and reading it from the draw phase means the drag /
     * expand morph no longer recomposes the sheet host or reallocates shapes in composition.
     */
    val playerShadowShapeState: State<Shape>,
    val sheetVerticalDragGestureHandler: SheetVerticalDragGestureHandler,
    val canDragSheet: Boolean
)

@Composable
internal fun rememberSheetInteractionState(
    scope: CoroutineScope,
    velocityTracker: VelocityTracker,
    sheetMotionController: SheetMotionController,
    playerContentExpansionFraction: Animatable<Float, AnimationVector1D>,
    currentSheetTranslationY: Animatable<Float, AnimationVector1D>,
    visualOvershootScaleY: Animatable<Float, AnimationVector1D>,
    sheetCollapsedTargetY: Float,
    sheetExpandedTargetY: Float,
    miniPlayerContentHeightPx: Float,
    currentSheetContentState: PlayerSheetState,
    showPlayerContentArea: Boolean,
    overallSheetTopCornerRadiusState: State<Dp>,
    playerContentActualBottomRadiusState: State<Dp>,
    useSmoothCorners: Boolean,
    isDragging: Boolean,
    onAnimateSheet: suspend (
        targetExpanded: Boolean,
        animationSpec: AnimationSpec<Float>?,
        initialVelocity: Float
    ) -> Unit,
    onExpandSheetState: () -> Unit,
    onCollapseSheetState: () -> Unit,
    onDraggingChange: (Boolean) -> Unit,
    onDraggingPlayerAreaChange: (Boolean) -> Unit
): SheetInteractionState {
    // Smooth corners are only rendered while the sheet is at rest; while dragging or animating we
    // fall back to plain rounded corners. Both inputs are read inside the derived state so the
    // running flag flips do not recompose this scope.
    val playerShadowShapeState = remember(
        overallSheetTopCornerRadiusState,
        playerContentActualBottomRadiusState,
        useSmoothCorners,
        isDragging
    ) {
        derivedStateOf {
            val overallSheetTopCornerRadius = overallSheetTopCornerRadiusState.value
            val playerContentActualBottomRadius = playerContentActualBottomRadiusState.value
            if (useSmoothCorners && !isDragging && !playerContentExpansionFraction.isRunning) {
                AbsoluteSmoothCornerShape(
                    cornerRadiusTL = overallSheetTopCornerRadius,
                    smoothnessAsPercentBL = 60,
                    cornerRadiusTR = overallSheetTopCornerRadius,
                    smoothnessAsPercentBR = 60,
                    cornerRadiusBR = playerContentActualBottomRadius,
                    smoothnessAsPercentTL = 60,
                    cornerRadiusBL = playerContentActualBottomRadius,
                    smoothnessAsPercentTR = 60
                )
            } else {
                RoundedCornerShape(
                    topStart = overallSheetTopCornerRadius,
                    topEnd = overallSheetTopCornerRadius,
                    bottomStart = playerContentActualBottomRadius,
                    bottomEnd = playerContentActualBottomRadius
                )
            }
        }
    }

    val collapsedYState = rememberUpdatedState(sheetCollapsedTargetY)
    val expandedYState = rememberUpdatedState(sheetExpandedTargetY)
    val miniHeightState = rememberUpdatedState(miniPlayerContentHeightPx)
    val densityState = rememberUpdatedState(LocalDensity.current)
    val currentSheetState = rememberUpdatedState(currentSheetContentState)
    val onAnimateSheetState = rememberUpdatedState(onAnimateSheet)
    val onExpandSheetStateState = rememberUpdatedState(onExpandSheetState)
    val onCollapseSheetStateState = rememberUpdatedState(onCollapseSheetState)
    val onDraggingChangeState = rememberUpdatedState(onDraggingChange)
    val onDraggingPlayerAreaChangeState = rememberUpdatedState(onDraggingPlayerAreaChange)

    val sheetVerticalDragGestureHandler = remember(
        scope,
        velocityTracker,
        sheetMotionController,
        playerContentExpansionFraction,
        currentSheetTranslationY,
        visualOvershootScaleY
    ) {
        SheetVerticalDragGestureHandler(
            scope = scope,
            velocityTracker = velocityTracker,
            densityProvider = { densityState.value },
            sheetMotionController = sheetMotionController,
            playerContentExpansionFraction = playerContentExpansionFraction,
            currentSheetTranslationY = currentSheetTranslationY,
            expandedYProvider = { expandedYState.value },
            collapsedYProvider = { collapsedYState.value },
            miniHeightPxProvider = { miniHeightState.value },
            currentSheetStateProvider = { currentSheetState.value },
            visualOvershootScaleY = visualOvershootScaleY,
            onDraggingChange = { onDraggingChangeState.value(it) },
            onDraggingPlayerAreaChange = { onDraggingPlayerAreaChangeState.value(it) },
            onAnimateSheet = { targetExpanded, animationSpec, initialVelocity ->
                onAnimateSheetState.value(targetExpanded, animationSpec, initialVelocity)
            },
            onExpandSheetState = { onExpandSheetStateState.value() },
            onCollapseSheetState = { onCollapseSheetStateState.value() }
        )
    }

    return SheetInteractionState(
        playerShadowShapeState = playerShadowShapeState,
        sheetVerticalDragGestureHandler = sheetVerticalDragGestureHandler,
        canDragSheet = showPlayerContentArea
    )
}
