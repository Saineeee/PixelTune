package com.saine.pixeltune.presentation.components.scoped

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.saine.pixeltune.presentation.viewmodel.PlayerSheetState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * Encapsulates vertical drag gesture state and target resolution for the player sheet.
 * Behavior is kept identical to the previous inline implementation.
 */
internal class SheetVerticalDragGestureHandler(
    private val scope: CoroutineScope,
    private val velocityTracker: VelocityTracker,
    private val densityProvider: () -> Density,
    private val sheetMotionController: SheetMotionController,
    private val playerContentExpansionFraction: Animatable<Float, AnimationVector1D>,
    private val currentSheetTranslationY: Animatable<Float, AnimationVector1D>,
    private val expandedYProvider: () -> Float,
    private val collapsedYProvider: () -> Float,
    private val miniHeightPxProvider: () -> Float,
    private val currentSheetStateProvider: () -> PlayerSheetState,
    private val visualOvershootScaleY: Animatable<Float, AnimationVector1D>,
    private val onDraggingChange: (Boolean) -> Unit,
    private val onDraggingPlayerAreaChange: (Boolean) -> Unit,
    private val onAnimateSheet: suspend (
        targetExpanded: Boolean,
        animationSpec: AnimationSpec<Float>?,
        initialVelocity: Float
    ) -> Unit,
    private val onExpandSheetState: () -> Unit,
    private val onCollapseSheetState: () -> Unit
) {
    private var initialFractionOnDragStart = 0f
    private var initialYOnDragStart = 0f
    private var accumulatedDragYSinceStart = 0f

    /**
     * Drag deltas are coalesced into a single frame-paced snap loop (same pattern as
     * [QueueSheetController]) instead of launching one coroutine per pointer event.
     * A 120 Hz drag used to allocate (and cancel) one coroutine + one MutatorMutex mutation
     * per move event — pure allocation churn on the input path.
     */
    private var pendingDragAmount = 0f
    private var dragSnapJob: Job? = null

    private fun resetDragPipeline() {
        pendingDragAmount = 0f
        dragSnapJob?.cancel()
        dragSnapJob = null
    }

    private fun launchDragSnapLoopIfNeeded() {
        if (dragSnapJob?.isActive == true) return

        dragSnapJob = scope.launch {
            while (isActive) {
                val delta = pendingDragAmount
                pendingDragAmount = 0f
                if (delta == 0f) break
                applyDragDelta(delta)

                // Coalesce high-frequency move events into at most one snap per display frame.
                if (coroutineContext[MonotonicFrameClock] != null) {
                    withFrameNanos { }
                }
            }
        }
    }

    private suspend fun applyDragDelta(dragAmount: Float) {
        val dragFrame = computeSheetVerticalDragFrame(
            currentTranslationY = currentSheetTranslationY.value,
            dragAmount = dragAmount,
            expandedY = expandedYProvider(),
            collapsedY = collapsedYProvider(),
            miniHeightPx = miniHeightPxProvider(),
            initialFractionOnDragStart = initialFractionOnDragStart,
            initialYOnDragStart = initialYOnDragStart
        )
        sheetMotionController.snapTo(
            translationYValue = dragFrame.translationY,
            expansionFractionValue = dragFrame.expansionFraction
        )
    }

    /** Applies any not-yet-consumed drag delta so settle decisions read the true position. */
    private suspend fun drainPendingDragSnap() {
        dragSnapJob?.cancel()
        dragSnapJob = null
        val delta = pendingDragAmount
        pendingDragAmount = 0f
        if (delta != 0f) {
            applyDragDelta(delta)
        }
    }

    fun onDragStart() {
        scope.launch { sheetMotionController.stop() }
        resetDragPipeline()
        onDraggingChange(true)
        onDraggingPlayerAreaChange(true)
        velocityTracker.resetTracking()
        initialFractionOnDragStart = playerContentExpansionFraction.value
        initialYOnDragStart = currentSheetTranslationY.value
        accumulatedDragYSinceStart = 0f
    }

    fun onVerticalDrag(
        uptimeMillis: Long,
        position: Offset,
        dragAmount: Float
    ) {
        accumulatedDragYSinceStart += dragAmount
        pendingDragAmount += dragAmount
        launchDragSnapLoopIfNeeded()
        velocityTracker.addPosition(uptimeMillis, position)
    }

    fun onDragEnd() {
        onDraggingChange(false)
        onDraggingPlayerAreaChange(false)

        val verticalVelocity = velocityTracker.calculateVelocity().y
        val minDragThresholdPx = with(densityProvider()) { 5.dp.toPx() }
        val velocityThreshold = 150f

        scope.launch {
            // Consume the last pending deltas first so the target-state decision and the
            // settle animation both start from the position the user actually sees.
            drainPendingDragSnap()

            val currentFraction = playerContentExpansionFraction.value
            val targetState = resolveVerticalSheetTargetState(
                currentSheetContentState = currentSheetStateProvider(),
                accumulatedDragY = accumulatedDragYSinceStart,
                minDragThresholdPx = minDragThresholdPx,
                verticalVelocity = verticalVelocity,
                velocityThreshold = velocityThreshold,
                currentFraction = currentFraction
            )

            if (targetState == PlayerSheetState.EXPANDED) {
                launch {
                    onAnimateSheet(true, null, 0f)
                }
                onExpandSheetState()
            } else {
                val dynamicDamping = collapseSpringDampingForFraction(currentFraction)
                launch {
                    val initialSquash = collapseInitialSquashForFraction(currentFraction)
                    visualOvershootScaleY.snapTo(initialSquash)
                    visualOvershootScaleY.animateTo(
                        targetValue = 1f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessVeryLow
                        )
                    )
                }
                launch {
                    onAnimateSheet(
                        false,
                        spring(
                            dampingRatio = dynamicDamping,
                            stiffness = Spring.StiffnessLow
                        ),
                        verticalVelocity
                    )
                }
                onCollapseSheetState()
            }
        }

        accumulatedDragYSinceStart = 0f
    }

    fun onDragCancel() {
        onDragEnd()
    }
}

internal fun Modifier.playerSheetVerticalDragGesture(
    enabled: Boolean,
    handler: SheetVerticalDragGestureHandler
): Modifier {
    if (!enabled) return this
    return this.pointerInput(enabled, handler) {
        detectVerticalDragGestures(
            onDragStart = { handler.onDragStart() },
            onVerticalDrag = { change, dragAmount ->
                change.consume()
                handler.onVerticalDrag(
                    uptimeMillis = change.uptimeMillis,
                    position = change.position,
                    dragAmount = dragAmount
                )
            },
            onDragEnd = { handler.onDragEnd() },
            onDragCancel = { handler.onDragCancel() }
        )
    }
}
