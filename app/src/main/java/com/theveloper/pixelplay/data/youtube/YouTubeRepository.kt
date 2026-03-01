package com.theveloper.pixelplay.data.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YouTubeRepository @Inject constructor() {

    /**
     * Extracts the best available audio stream URL for a given YouTube video ID.
     */
    suspend fun getAudioStreamUrl(youtubeId: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = "https://www.youtube.com/watch?v=$youtubeId"
            Timber.d("Extracting YouTube streams for: $url")

            // Get the stream extractor for YouTube
            val extractor = ServiceList.YouTube.getStreamExtractor(url)

            // Fetch page and extract streams
            extractor.fetchPage()

            val audioStreams = extractor.audioStreams
            if (audioStreams.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("No audio streams found for video $youtubeId"))
            }

            // Filter out non-audio streams just in case and pick the best one.
            // Priority: Opus/WebM -> M4A/AAC, highest bitrate.
            val bestStream = findBestAudioStream(audioStreams)

            if (bestStream != null) {
                Result.success(bestStream.content)
            } else {
                Result.failure(Exception("No suitable audio stream format found for video $youtubeId"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Error extracting YouTube stream for $youtubeId")
            Result.failure(e)
        }
    }

    private fun findBestAudioStream(streams: List<AudioStream>): AudioStream? {
        return streams.maxWithOrNull(compareBy({ getFormatPriority(it) }, { it.bitrate }))
    }

    private fun getFormatPriority(stream: AudioStream): Int {
        val formatName = stream.format?.name?.lowercase() ?: ""
        return when {
            formatName.contains("opus") || formatName.contains("webm") -> 2
            formatName.contains("m4a") || formatName.contains("aac") -> 1
            else -> 0
        }
    }
}
