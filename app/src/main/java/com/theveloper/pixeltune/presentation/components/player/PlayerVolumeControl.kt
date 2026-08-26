package com.theveloper.pixeltune.presentation.components.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.widthIn

/**
 * IMPROVE(volume-slider): Material 3 expressive volume control for the full
 * player screen.
 *
 * Tapping the volume icon morphs the compact 42×50 button into a pill that
 * fully expands to reveal a smooth volume slider + live percentage readout —
 * mirroring the morphing-pill animation language the cast button at the top
 * of the player already uses (spring-based size + corner morphs, icon
 * crossfade, M3 `Slider` with theme-matched colors).
 *
 * Collapsed:        [ (icon) ]
 * Expanded:  [ (icon) ————●———— 72% ]
 */
@Composable
fun PlayerVolumeControl(
    volumeProvider: () -> Float,
    onVolumeChange: (Float) -> Unit,
    containerColor: Color,
    contentColor: Color,
    trackActiveColor: Color,
    trackInactiveColor: Color,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    val volume = volumeProvider().coerceIn(0f, 1f)
    val percent = (volume * 100f).toInt()

    // Corner morph: collapsed mirrors the queue button's asymmetric shape,
    // expanded becomes a full pill — same spring language as the cast button.
    val cornerExpanded = 50.dp
    val cornerCompactStart = 6.dp
    val cornerCompactEnd = 50.dp
    val cornerTopStart by animateDpAsState(
        targetValue = if (expanded) cornerExpanded else cornerCompactStart,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "volumeCornerTopStart"
    )
    val cornerBottomStart by animateDpAsState(
        targetValue = if (expanded) cornerExpanded else cornerCompactStart,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "volumeCornerBottomStart"
    )
    val cornerTopEnd by animateDpAsState(
        targetValue = if (expanded) cornerExpanded else cornerCompactEnd,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "volumeCornerTopEnd"
    )
    val cornerBottomEnd by animateDpAsState(
        targetValue = if (expanded) cornerExpanded else cornerCompactEnd,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "volumeCornerBottomEnd"
    )

    Box(
        modifier = modifier
            .height(42.dp)
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
            .clip(
                RoundedCornerShape(
                    topStart = cornerTopStart.coerceAtLeast(0.dp),
                    topEnd = cornerTopEnd.coerceAtLeast(0.dp),
                    bottomStart = cornerBottomStart.coerceAtLeast(0.dp),
                    bottomEnd = cornerBottomEnd.coerceAtLeast(0.dp)
                )
            )
            .background(containerColor)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Toggle button — always visible; tapping expands/collapses the slider.
            Box(
                modifier = Modifier
                    .size(height = 42.dp, width = 50.dp)
                    .clickable { expanded = !expanded },
                contentAlignment = Alignment.Center
            ) {
                val volumeIcon = when {
                    volume <= 0.001f -> Icons.AutoMirrored.Rounded.VolumeOff
                    volume < 0.5f -> Icons.AutoMirrored.Rounded.VolumeDown
                    else -> Icons.AutoMirrored.Rounded.VolumeUp
                }
                AnimatedContent(
                    targetState = volumeIcon,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(180)) +
                            scaleIn(initialScale = 0.7f, animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMedium
                            ))) togetherWith
                            (fadeOut(animationSpec = tween(120)) +
                                scaleOut(targetScale = 0.7f, animationSpec = tween(120)))
                    },
                    label = "volumeIcon"
                ) { icon ->
                    Icon(
                        imageVector = icon,
                        contentDescription = if (expanded) "Hide volume" else "Show volume",
                        tint = contentColor
                    )
                }
            }

            // Expanding slider area.
            AnimatedVisibility(
                visible = expanded,
                enter = expandHorizontally(
                    expandFrom = Alignment.Start,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                ) + fadeIn(animationSpec = tween(200)),
                exit = shrinkHorizontally(
                    shrinkTowards = Alignment.Start,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                ) + fadeOut(animationSpec = tween(150))
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Slider(
                        value = volume,
                        onValueChange = { onVolumeChange(it.coerceIn(0f, 1f)) },
                        valueRange = 0f..1f,
                        modifier = Modifier
                            .width(116.dp)
                            .graphicsLayer { alpha = 0.98f },
                        colors = SliderDefaults.colors(
                            thumbColor = trackActiveColor,
                            activeTrackColor = trackActiveColor,
                            inactiveTrackColor = trackInactiveColor,
                            activeTickColor = Color.Transparent,
                            inactiveTickColor = Color.Transparent
                        )
                    )
                    Text(
                        text = "$percent%",
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(min = 34.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                }
            }
        }
    }
}
