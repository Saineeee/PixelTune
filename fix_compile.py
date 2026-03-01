import re

files_to_fix = [
    "app/src/main/java/com/theveloper/pixelplay/presentation/components/QueueBottomSheet.kt",
    "app/src/main/java/com/theveloper/pixelplay/presentation/screens/SearchScreen.kt"
]

for file in files_to_fix:
    with open(file, "r") as f:
        content = f.read()

    # If `import androidx.compose.material3.TextButton` is duplicated right before `import androidx.compose.material3.Divider`, clean it.
    content = content.replace("import androidx.compose.material3.TextButton\n\nimport androidx.compose.material3.Divider", "import androidx.compose.material3.Divider")

    with open(file, "w") as f:
        f.write(content)
