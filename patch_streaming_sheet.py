import re

file_path = 'app/src/main/java/com/theveloper/pixelplay/presentation/components/StreamingProviderSheet.kt'
with open(file_path, 'r') as f:
    content = f.read()

# Add imports
imports_pattern = r"import com\.theveloper\.pixelplay\.data\.netease\.NeteaseRepository"
imports_replacement = r"""import com.theveloper.pixelplay.data.netease.NeteaseRepository
import androidx.compose.material.icons.rounded.PlayCircle
import com.theveloper.pixelplay.presentation.viewmodel.SearchStateHolder.OnlineProvider"""
content = re.sub(imports_pattern, imports_replacement, content)

# Update parameters
params_pattern = r"fun StreamingProviderSheet\([\s\S]*?\n    onDismissRequest: \(\) -> Unit,\n    isNeteaseLoggedIn: Boolean = false,\n    onNavigateToNeteaseDashboard: \(\) -> Unit = \{\},\n    sheetState: SheetState = rememberModalBottomSheetState\(\n        skipPartiallyExpanded = true\n    \)\n\)"
params_replacement = r"""fun StreamingProviderSheet(
    onDismissRequest: () -> Unit,
    isNeteaseLoggedIn: Boolean = false,
    onNavigateToNeteaseDashboard: () -> Unit = {},
    onProviderSelected: (OnlineProvider) -> Unit = {},
    sheetState: SheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )
)"""
content = re.sub(params_pattern, params_replacement, content)


# Add YouTube and SoundCloud cards
cards_pattern = r"            // Google Drive Provider \(coming soon\)\n            ProviderCard\([\s\S]*?\n            \)"
cards_replacement = r"""            // YouTube Provider
            ProviderCard(
                icon = Icons.Rounded.PlayCircle,
                title = "YouTube",
                subtitle = "Stream from YouTube",
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                iconColor = MaterialTheme.colorScheme.errorContainer,
                shape = cardShape,
                onClick = {
                    onProviderSelected(OnlineProvider.YOUTUBE)
                    onDismissRequest()
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
                onClick = {
                    onProviderSelected(OnlineProvider.SOUNDCLOUD)
                    onDismissRequest()
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
            )"""
content = re.sub(cards_pattern, cards_replacement, content)

with open(file_path, 'w') as f:
    f.write(content)
