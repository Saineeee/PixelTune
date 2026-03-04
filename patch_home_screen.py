import re

file_path = 'app/src/main/java/com/theveloper/pixelplay/presentation/screens/HomeScreen.kt'
with open(file_path, 'r') as f:
    content = f.read()

# Pass onProviderSelected
call_pattern = r"        StreamingProviderSheet\([\s\S]*?\n            isNeteaseLoggedIn = isNeteaseLoggedIn,\n            onNavigateToNeteaseDashboard = \{\n                navController\.navigateSafely\(Screen\.NeteaseDashboard\.route\)\n            \}\n        \)"
call_replacement = r"""        StreamingProviderSheet(
            onDismissRequest = { showStreamingProviderSheet = false },
            isNeteaseLoggedIn = isNeteaseLoggedIn,
            onNavigateToNeteaseDashboard = {
                navController.navigateSafely(Screen.NeteaseDashboard.route)
            },
            onProviderSelected = { provider ->
                playerViewModel.setOnlineProvider(provider)
            }
        )"""
content = re.sub(call_pattern, call_replacement, content)

with open(file_path, 'w') as f:
    f.write(content)
