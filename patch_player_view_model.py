import re

file_path = 'app/src/main/java/com/theveloper/pixelplay/presentation/viewmodel/PlayerViewModel.kt'
with open(file_path, 'r') as f:
    content = f.read()

# Add delegate method
delegate_pattern = r"fun updateSearchQuery\(query: String\) \{\n        searchQuery = query\n    \}"
delegate_replacement = r"""fun updateSearchQuery(query: String) {
        searchQuery = query
    }

    fun setOnlineProvider(provider: SearchStateHolder.OnlineProvider) {
        searchStateHolder.setOnlineProvider(provider)
    }"""
content = re.sub(delegate_pattern, delegate_replacement, content)

with open(file_path, 'w') as f:
    f.write(content)
