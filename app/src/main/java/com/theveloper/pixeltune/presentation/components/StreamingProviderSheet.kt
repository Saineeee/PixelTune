package com.theveloper.pixeltune.presentation.components

import android.content.Intent
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
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.theveloper.pixeltune.R
import com.theveloper.pixeltune.data.netease.NeteaseRepository
import androidx.compose.material.icons.rounded.PlayCircle
import com.theveloper.pixeltune.presentation.viewmodel.SearchStateHolder.OnlineProvider
import com.theveloper.pixeltune.presentation.netease.auth.NeteaseLoginActivity
import com.theveloper.pixeltune.presentation.telegram.auth.TelegramLoginActivity
import com.theveloper.pixeltune.ui.theme.GoogleSansRounded
import kotlinx.coroutines.launch
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

/**
 * Bottom sheet that lets the user choose between streaming providers
 * (Telegram, Google Drive, Netease Cloud Music).
 *
 * For Netease: if already logged in, navigates to dashboard.
 * If not logged in, launches WebView login activity.
 *
 * IMPROVE(provider-indicator): [activeProvider] badges the streaming provider
 * (YouTube / SoundCloud) the app is currently using, updating in real time as
 * the selection changes.
 *
 * IMPROVE(provider-sheet-dismiss): selecting any provider now closes the sheet
 * with the same animated `sheetState.hide()` convention the app's other bottom
 * sheets use, instead of removing it from composition abruptly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamingProviderSheet(
    onDismissRequest: () -> Unit,
    isNeteaseLoggedIn: Boolean = false,
    onNavigateToNeteaseDashboard: () -> Unit = {},
    onProviderSelected: (OnlineProvider) -> Unit = {},
    activeProvider: OnlineProvider? = null,
    sheetState: SheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )
) {
    val context = LocalContext.current
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
                text = "Stream music from your cloud accounts",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = GoogleSansRounded,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(24.dp))

            // Telegram Provider
            ProviderCard(
                iconPainter = painterResource(R.drawable.telegram),
                icon = Icons.Rounded.Cloud,
                title = "Telegram",
                subtitle = "Stream from channels & chats",
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                iconColor = MaterialTheme.colorScheme.primaryContainer,
                shape = cardShape,
                onClick = {
                    context.startActivity(Intent(context, TelegramLoginActivity::class.java))
                    dismissWithAnimation()
                }
            )

            Spacer(Modifier.height(12.dp))

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

            Spacer(Modifier.height(12.dp))

            // Google Drive Provider (coming soon)
            ProviderCard(
                icon = Icons.Rounded.CloudQueue,
                iconPainter = painterResource(R.drawable.rounded_drive_export_24),
                title = "Google Drive",
                subtitle = "Coming soon",
                containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                iconColor = MaterialTheme.colorScheme.onSurface,
                shape = cardShape,
                enabled = false,
                onClick = { }
            )

            Spacer(Modifier.height(12.dp))

            // Netease Cloud Music Provider
            ProviderCard(
                icon = Icons.Rounded.MusicNote,
                iconPainter = painterResource(R.drawable.netease_cloud_music_logo_icon_206716__1_),
                title = "Netease Cloud Music",
                subtitle = if (isNeteaseLoggedIn)
                    "✓ Connected – Open dashboard"
                else
                    "网易云音乐 – Sign in to stream",
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                iconColor = MaterialTheme.colorScheme.tertiaryContainer,
                shape = cardShape,
                onClick = {
                    if (isNeteaseLoggedIn) {
                        onNavigateToNeteaseDashboard()
                    } else {
                        context.startActivity(Intent(context, NeteaseLoginActivity::class.java))
                    }
                    dismissWithAnimation()
                }
            )
        }
    }
}

@Composable
private fun ProviderCard(
    icon: ImageVector,
    iconPainter: Painter? = null,
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
                if (iconPainter != null){
                    Icon(
                        painter = iconPainter,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = iconColor
                    )
                } else {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = iconColor
                    )
                }
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
            AnimatedVisibility(
                visible = isActive,
                enter = fadeIn() + expandHorizontally(
                    expandFrom = Alignment.End,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
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
                            painter = painterResource(R.drawable.rounded_check_circle_24),
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
