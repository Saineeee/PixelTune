package com.saine.pixeltune.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.saine.pixeltune.presentation.viewmodel.SearchStateHolder.OnlineProvider
import com.saine.pixeltune.ui.theme.GoogleSansRounded
import kotlinx.coroutines.launch
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

/**
 * Bottom sheet that lets the user choose between the online streaming
 * providers (YouTube, SoundCloud).
 *
 * IMPROVE(provider-indicator): [activeProvider] badges the streaming provider
 * the app is currently using, updating in real time as the selection changes.
 *
 * IMPROVE(provider-sheet-dismiss): selecting any provider closes the sheet
 * with the same animated `sheetState.hide()` convention the app's other bottom
 * sheets use, instead of removing it from composition abruptly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamingProviderSheet(
    onDismissRequest: () -> Unit,
    onProviderSelected: (OnlineProvider) -> Unit = {},
    activeProvider: OnlineProvider? = null,
    sheetState: SheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )
) {
    val scope = rememberCoroutineScope()

    // IMPROVE(provider-sheet-dismiss): the app-wide smooth-dismiss pattern —
    // animate the sheet down first, then remove it from composition (same
    // convention as HomeScreen's other bottom sheets).
    fun dismissWithAnimation() {
        scope.launch {
            sheetState.hide()
        }.invokeOnCompletion {
            if (!sheetState.isVisible) {
                onDismissRequest()
            }
        }
    }

    val cardShape = AbsoluteSmoothCornerShape(
        cornerRadiusTR = 20.dp, cornerRadiusTL = 20.dp,
        cornerRadiusBR = 20.dp, cornerRadiusBL = 20.dp,
        smoothnessAsPercentTR = 60, smoothnessAsPercentTL = 60,
        smoothnessAsPercentBR = 60, smoothnessAsPercentBL = 60
    )

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Cloud Streaming",
                style = MaterialTheme.typography.titleLarge,
                fontFamily = GoogleSansRounded,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Stream music from online providers",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = GoogleSansRounded,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(24.dp))

            // YouTube Provider
            ProviderCard(
                icon = Icons.Rounded.PlayCircle,
                title = "YouTube",
                subtitle = "Stream from YouTube",
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                iconColor = MaterialTheme.colorScheme.errorContainer,
                shape = cardShape,
                // IMPROVE(provider-indicator): real-time active badge —
                // only shown for the switchable streaming providers.
                isActive = activeProvider == OnlineProvider.YOUTUBE,
                onClick = {
                    onProviderSelected(OnlineProvider.YOUTUBE)
                    dismissWithAnimation()
                }
            )

            Spacer(Modifier.height(12.dp))

            // SoundCloud Provider
            ProviderCard(
                icon = Icons.Rounded.CloudQueue,
                title = "SoundCloud",
                subtitle = "Stream from SoundCloud",
                containerColor = Color(0xFFFFDAB9),
                contentColor = Color(0xFFCC5500),
                iconColor = Color(0xFFFFDAB9),
                shape = cardShape,
                // IMPROVE(provider-indicator): real-time active badge —
                // only shown for the switchable streaming providers.
                isActive = activeProvider == OnlineProvider.SOUNDCLOUD,
                onClick = {
                    onProviderSelected(OnlineProvider.SOUNDCLOUD)
                    dismissWithAnimation()
                }
            )
        }
    }
}

@Composable
private fun ProviderCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    containerColor: Color,
    contentColor: Color,
    iconColor: Color,
    shape: AbsoluteSmoothCornerShape,
    enabled: Boolean = true,
    isActive: Boolean = false,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.62f)
            .clip(shape = shape)
            .clickable(enabled = enabled, onClick = onClick),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = containerColor
        )
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(contentColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = iconColor
                )
            }

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = GoogleSansRounded,
                    fontWeight = FontWeight.Bold,
                    color = contentColor
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = GoogleSansRounded,
                    color = contentColor.copy(alpha = 0.7f)
                )
            }

            Spacer(Modifier.width(12.dp))

            // IMPROVE(provider-indicator): animated "Active" badge for the
            // currently selected streaming provider (YouTube / SoundCloud).
            // Follows the app's expressive animation language — fade + horizontal
            // expansion with a spring, the same family of motion the library
            // action-row buttons use — and its Material 3 pill styling matches
            // the check-circle affordance of the library's selection states.
            //
            // PERF(sheet-transition): the enter spec used
            // DampingRatioMediumBouncy — an UNDERDAMPED spring animating the
            // badge's layout WIDTH inside a weight(1f) column, so the weighted
            // title/subtitle Texts were re-measured every frame while the width
            // oscillated for several hundred ms, concurrent with the sibling
            // badge's shrink AND the sheet-hide animation on the switch tap.
            // NoBouncy (already used by the exit spec) expands once, smoothly,
            // with the same visual language and no oscillation relayout.
            AnimatedVisibility(
                visible = isActive,
                enter = fadeIn() + expandHorizontally(
                    expandFrom = Alignment.End,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                ),
                exit = fadeOut() + shrinkHorizontally(
                    shrinkTowards = Alignment.End,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                )
            ) {
                Surface(
                    shape = RoundedCornerShape(percent = 50),
                    color = contentColor.copy(alpha = 0.16f),
                    contentColor = contentColor
                ) {
                    Row(
                        modifier = Modifier.padding(
                            start = 12.dp,
                            end = 14.dp,
                            top = 6.dp,
                            bottom = 6.dp
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(
                                com.saine.pixeltune.R.drawable.rounded_check_circle_24
                            ),
                            contentDescription = "Active provider",
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Active",
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = GoogleSansRounded,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
