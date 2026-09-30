package com.saine.pixeltune.presentation.components.scoped

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.saine.pixeltune.presentation.viewmodel.PlayerSheetState

internal data class FullPlayerRuntimePolicy(
    val allowRealtimeUpdates: Boolean
)

/**
 * Gates high-frequency UI updates (progress bar sampling, animations) behind
 * conditions that only flip at expansion thresholds — not on every frame.
 *
 * [expansionFraction] is read inside [derivedStateOf], producing a Boolean that
 * changes only when crossing the 0.985 / 0.95 thresholds. This avoids per-frame
 * recomposition of the caller during gestures.
 *
 * [bottomSheetOpenFractionState] is likewise read inside the [derivedStateOf] (it animates
 * every frame while the queue sheet opens/closes), so the caller is only invalidated when
 * the resulting Boolean actually flips.
 */
@Composable
internal fun rememberFullPlayerRuntimePolicy(
    currentSheetState: PlayerSheetState,
    expansionFraction: Animatable<Float, AnimationVector1D>,
    bottomSheetOpenFractionState: State<Float>
): FullPlayerRuntimePolicy {
    val allowRealtimeUpdates by remember(currentSheetState) {
        derivedStateOf {
            val ef = expansionFraction.value
            // Compute content alpha inline (same formula as FullPlayerVisualState).
            val alpha = (ef - 0.25f).coerceIn(0f, 0.75f) / 0.75f
            val isOccluded = bottomSheetOpenFractionState.value >= 0.08f

            currentSheetState == PlayerSheetState.EXPANDED &&
                ef >= 0.985f &&
                alpha >= 0.95f &&
                !isOccluded
        }
    }

    return FullPlayerRuntimePolicy(
        allowRealtimeUpdates = allowRealtimeUpdates
    )
}
