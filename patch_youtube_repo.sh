sed -i 's/val progressiveStreams = audioStreams.filter { stream ->/val progressiveStreams = audioStreams.filter { stream ->/g' ./app/src/main/java/com/theveloper/pixeltune/data/youtube/YouTubeRepository.kt

sed -i '/if (progressiveStreams.isEmpty()) {/i \            var usableStreams = progressiveStreams\n            if (usableStreams.isEmpty()) {\n                // Fallback to any stream with a URL if no progressive streams exist\n                usableStreams = audioStreams.filter { it.isUrl }\n                if (usableStreams.isNotEmpty()) {\n                    Timber.w("No progressive streams found, falling back to other URL streams for $youtubeId")\n                }\n            }' ./app/src/main/java/com/theveloper/pixeltune/data/youtube/YouTubeRepository.kt

sed -i 's/if (progressiveStreams.isEmpty()) {/if (usableStreams.isEmpty()) {/g' ./app/src/main/java/com/theveloper/pixeltune/data/youtube/YouTubeRepository.kt
sed -i 's/val bestStream = findBestAudioStream(progressiveStreams, quality)/val bestStream = findBestAudioStream(usableStreams, quality)/g' ./app/src/main/java/com/theveloper/pixeltune/data/youtube/YouTubeRepository.kt
