import re

file_path = 'app/src/main/java/com/theveloper/pixelplay/presentation/viewmodel/SearchStateHolder.kt'
with open(file_path, 'r') as f:
    content = f.read()

# Add imports
imports_pattern = r"import com\.theveloper\.pixelplay\.data\.youtube\.YouTubeStreamProxy"
imports_replacement = r"""import com.theveloper.pixelplay.data.youtube.YouTubeStreamProxy
import com.theveloper.pixelplay.data.soundcloud.SoundCloudRepository
import com.theveloper.pixelplay.data.soundcloud.SoundCloudStreamProxy"""
content = re.sub(imports_pattern, imports_replacement, content)

# Update constructor
constructor_pattern = r"private val youTubeRepository: YouTubeRepository,\n    private val youTubeStreamProxy: YouTubeStreamProxy"
constructor_replacement = r"""private val youTubeRepository: YouTubeRepository,
    private val youTubeStreamProxy: YouTubeStreamProxy,
    private val soundCloudRepository: SoundCloudRepository,
    private val soundCloudStreamProxy: SoundCloudStreamProxy"""
content = re.sub(constructor_pattern, constructor_replacement, content)

# Add enum and provider state
state_pattern = r"    // Search State\n    private val _searchResults = MutableStateFlow<ImmutableList<SearchResultItem>>\(persistentListOf\(\)\)"
state_replacement = r"""    enum class OnlineProvider {
        YOUTUBE, SOUNDCLOUD
    }

    private val _currentProvider = MutableStateFlow(OnlineProvider.YOUTUBE)
    val currentProvider = _currentProvider.asStateFlow()

    fun setOnlineProvider(provider: OnlineProvider) {
        _currentProvider.value = provider
    }

    // Search State
    private val _searchResults = MutableStateFlow<ImmutableList<SearchResultItem>>(persistentListOf())"""
content = re.sub(state_pattern, state_replacement, content)

# Update search logic
search_logic_pattern = r"if \(request\.isOnline\) \{\n                                youTubeRepository\.searchYouTube\(normalizedQuery, currentFilter\) \{ youtubeId ->\n                                    youTubeStreamProxy\.getProxyUrl\(youtubeId\)\n                                \}\n                            \}"
search_logic_replacement = r"""if (request.isOnline) {
                                if (_currentProvider.value == OnlineProvider.YOUTUBE) {
                                    youTubeRepository.searchYouTube(normalizedQuery, currentFilter) { youtubeId ->
                                        youTubeStreamProxy.getProxyUrl(youtubeId)
                                    }
                                } else {
                                    soundCloudRepository.searchSoundCloud(normalizedQuery, currentFilter) { encodedUrl ->
                                        soundCloudStreamProxy.getProxyUrl(encodedUrl)
                                    }
                                }
                            }"""
content = re.sub(search_logic_pattern, search_logic_replacement, content)

with open(file_path, 'w') as f:
    f.write(content)
