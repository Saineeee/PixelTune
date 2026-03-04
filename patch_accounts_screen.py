import re

file_path = 'app/src/main/java/com/theveloper/pixelplay/presentation/screens/AccountsScreen.kt'
with open(file_path, 'r') as f:
    content = f.read()

# Replace servicePalette
palette_pattern = r"ExternalServiceAccount\.NETEASE -> ServicePalette\([\s\S]*?\n        \)"
palette_replacement = r"""ExternalServiceAccount.NETEASE -> ServicePalette(
            iconContainer = MaterialTheme.colorScheme.tertiaryContainer,
            iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
            statusContainer = Color(0xFFFFF0C7),
            statusTint = Color(0xFF704900),
            primaryActionContainer = MaterialTheme.colorScheme.tertiaryContainer,
            primaryActionTint = MaterialTheme.colorScheme.onTertiaryContainer
        )
        ExternalServiceAccount.YOUTUBE -> ServicePalette(
            iconContainer = MaterialTheme.colorScheme.errorContainer,
            iconTint = MaterialTheme.colorScheme.onErrorContainer,
            statusContainer = Color(0xFFFFD4D4),
            statusTint = Color(0xFF8A0000),
            primaryActionContainer = MaterialTheme.colorScheme.errorContainer,
            primaryActionTint = MaterialTheme.colorScheme.onErrorContainer
        )
        ExternalServiceAccount.SOUNDCLOUD -> ServicePalette(
            iconContainer = Color(0xFFFFDAB9),
            iconTint = Color(0xFFCC5500),
            statusContainer = Color(0xFFFFEBCD),
            statusTint = Color(0xFF8B4500),
            primaryActionContainer = Color(0xFFFFDAB9),
            primaryActionTint = Color(0xFFCC5500)
        )"""
content = re.sub(palette_pattern, palette_replacement, content)

# Replace accountIcon
icon_pattern = r"ExternalServiceAccount\.NETEASE -> Icons\.Rounded\.LibraryMusic"
icon_replacement = r"""ExternalServiceAccount.NETEASE -> Icons.Rounded.LibraryMusic
        ExternalServiceAccount.YOUTUBE -> Icons.Rounded.PlayCircle
        ExternalServiceAccount.SOUNDCLOUD -> Icons.Rounded.CloudQueue"""
content = re.sub(icon_pattern, icon_replacement, content)

# Replace serviceTitle
title_pattern = r"ExternalServiceAccount\.NETEASE -> \"Netease\""
title_replacement = r"""ExternalServiceAccount.NETEASE -> "Netease"
        ExternalServiceAccount.YOUTUBE -> "YouTube"
        ExternalServiceAccount.SOUNDCLOUD -> "SoundCloud\""""
content = re.sub(title_pattern, title_replacement, content)

# Replace openService
open_pattern = r"ExternalServiceAccount\.NETEASE -> \{\n            if \(preferNeteaseDashboard\) \{\n                onOpenNeteaseDashboard\(\)\n            \} else \{\n                safeStartActivity\(\n                    context = context,\n                    intent = Intent\(context, NeteaseLoginActivity::class\.java\)\n                \)\n            \}\n        \}"
open_replacement = r"""ExternalServiceAccount.NETEASE -> {
            if (preferNeteaseDashboard) {
                onOpenNeteaseDashboard()
            } else {
                safeStartActivity(
                    context = context,
                    intent = Intent(context, NeteaseLoginActivity::class.java)
                )
            }
        }
        ExternalServiceAccount.YOUTUBE -> {
            Toast.makeText(context, "YouTube is coming soon.", Toast.LENGTH_SHORT).show()
        }
        ExternalServiceAccount.SOUNDCLOUD -> {
            Toast.makeText(context, "SoundCloud is coming soon.", Toast.LENGTH_SHORT).show()
        }"""
content = re.sub(open_pattern, open_replacement, content)

# Add PlayCircle import
import_pattern = r"import androidx\.compose\.material\.icons\.rounded\.CloudQueue"
import_replacement = r"""import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.PlayCircle"""
content = re.sub(import_pattern, import_replacement, content)

with open(file_path, 'w') as f:
    f.write(content)
