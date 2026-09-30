package com.saine.pixeltune.presentation.components.scoped

import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import kotlin.math.roundToInt

/** Guard against pathological negative/NaN geometry from partially-initialized providers. */
private fun Float.sanitizedPx(): Int = if (isNaN() || this < 0f) 0 else roundToInt()

/**
 * Deferred sheet-geometry modifiers.
 *
 * The player sheet morphs its size (mini-player height -> full container height) and horizontal
 * insets on every frame of the drag / expand / collapse animation. Driving those values through
 * composition parameters (`.height(x)` / `.padding(...)`) recomposes the entire sheet host —
 * including the mini player content, the full player layer and every `remember`ed lambda in
 * between — once per animation frame.
 *
 * The modifiers below read those animated values inside the **measure / draw phases** instead:
 * state reads inside `layout {}` and `graphicsLayer {}` / `drawBehind {}` blocks are observed by
 * the corresponding phase only, so a morphing frame costs one measure+draw pass and **zero**
 * recomposition.
 */

/**
 * Replaces `.padding(start = X, end = Y).height(H)` for the morphing player card.
 *
 * Reads [startInset], [endInset] and [height] inside the measure block (layout phase), so the
 * per-frame morph never invalidates composition. Reported geometry is identical to the
 * padding+height chain it replaces: node width = incoming max width, node height = [height],
 * child measured with (maxWidth - insets) x (height) and placed at [startInset] on the start edge.
 */
internal fun Modifier.sheetMorphGeometry(
    startInset: () -> Dp,
    endInset: () -> Dp,
    height: () -> Dp
): Modifier = layout { measurable, constraints ->
    val startPx = startInset().toPx().sanitizedPx()
    val endPx = endInset().toPx().sanitizedPx()
    val heightPx = height().toPx().sanitizedPx()

    val childConstraints = Constraints(
        minWidth = 0,
        maxWidth = (constraints.maxWidth - startPx - endPx).coerceAtLeast(0),
        minHeight = 0,
        maxHeight = heightPx
    )
    val placeable = measurable.measure(childConstraints)
    layout(constraints.maxWidth, heightPx) {
        placeable.placeRelative(startPx, 0)
    }
}

/**
 * Replaces `.padding(bottom = X)` when [bottom] animates per frame (predictive-back collapse).
 * The state read happens in the measure block — no recomposition.
 */
internal fun Modifier.deferredBottomPadding(
    bottom: () -> Dp
): Modifier = layout { measurable, constraints ->
    val bottomPx = bottom().toPx().sanitizedPx()
    val placeable = measurable.measure(
        Constraints(
            minWidth = constraints.minWidth,
            maxWidth = constraints.maxWidth,
            minHeight = 0,
            maxHeight = (constraints.maxHeight - bottomPx).coerceAtLeast(0)
        )
    )
    layout(constraints.maxWidth, placeable.height + bottomPx) {
        placeable.placeRelative(0, 0)
    }
}

/**
 * Draws the player card's surface (rounded background + shaped shadow) without recomposing.
 *
 * - `shape` is read inside the `graphicsLayer` block: the shadow outline follows the animated
 *   corner radii and the elevation fades in/out (mini-player shadow only visible while nearly
 *   collapsed) purely in the draw phase. Keeping the modifier permanently attached also avoids the
 *   structural `.then(if (elevation > 0.dp) Modifier.shadow(...))` swap that used to force a full
 *   remeasure exactly at the 0.18 expansion crossing mid-drag.
 * - The background is drawn as the shape's outline in `drawBehind` (equivalent to
 *   `.background(color, shape)` but deferred).
 *
 * Content is NOT clipped to the shape — callers keep their own `clipToBounds()`, matching the
 * previous `.shadow(..., clip = false)` + `.background(color, shape)` behavior.
 */
internal fun Modifier.playerCardSurface(
    shapeState: State<Shape>,
    colorProvider: () -> Color,
    shadowElevationProvider: () -> Dp
): Modifier = this
    .graphicsLayer {
        shape = shapeState.value
        shadowElevation = shadowElevationProvider().toPx()
    }
    .drawBehind {
        drawOutline(
            outline = shapeState.value.createOutline(size, layoutDirection, this),
            color = colorProvider()
        )
    }
