package com.theveloper.pixeltune.presentation.components

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush

@Composable
fun ShimmerBox(modifier: Modifier = Modifier) {
    // Use MaterialTheme colors for proper dark/light mode support
    val baseColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val highlightColor = MaterialTheme.colorScheme.surfaceContainerHighest

    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, delayMillis = 200),
        ),
        label = "shimmerTranslate"
    )

    // PERF(scroll): the brush used to be rebuilt in composition with
    // `translateAnim.value` read directly — every shimmer box recomposed at
    // ~60 Hz while visible (loading skeletons show 12 rows × 3-4 boxes, plus
    // album-art loading overlays). Building the gradient in the draw phase
    // only re-issues the draw pass: no recomposition, no remeasure, and the
    // per-frame Brush allocation stays out of the composition scope.
    Box(
        modifier = modifier.drawBehind {
            val brush = Brush.linearGradient(
                colors = listOf(baseColor, highlightColor, baseColor),
                start = Offset.Zero,
                end = Offset(x = translateAnim.value, y = translateAnim.value)
            )
            drawRect(brush = brush)
        }
    )
}
