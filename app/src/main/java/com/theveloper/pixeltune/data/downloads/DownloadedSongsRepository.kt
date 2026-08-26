package com.theveloper.pixeltune.data.downloads

import android.content.Context
import android.net.Uri
import com.theveloper.pixeltune.data.model.Song
import com.theveloper.pixeltune.data.preferences.StreamingQuality
import com.theveloper.pixeltune.data.preferences.UserPreferencesRepository
import com.theveloper.pixeltune.data.soundcloud.SoundCloudRepository
import com.theveloper.pixeltune.data.youtube.YouTubeRepository
import com.theveloper.pixeltune.di.StreamingOkHttpClient
import com.theveloper.pixeltune.utils.CloudUriUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * IMPROVE(offline-downloads): a single downloaded cloud song, persisted as part
 * of the downloads index (DataStore JSON). The audio file itself lives in the
 * app's private storage (`filesDir/downloads`), so — like Netflix downloads —
 * it can only be played back by PixelTune and is NOT accessible to other apps
 * or the system media store.
 */
@Serializable
data class DownloadedSong(
    /** Stable song id — the same id the player queue uses (YouTube video id / SoundCloud hash id). */
    val songId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val albumArtUri: String? = null,
    /** Cloud content URI in its restart-safe scheme form (`youtube://<id>`, `soundcloud://<encoded>`). */
    val contentUri: String,
    val durationMs: Long = 0L,
    /** Absolute path of the downloaded audio file inside app-private storage. */
    val filePath: String,
    val mimeType: String? = null,
    val sizeBytes: Long = 0L,
    /** "youtube" | "soundcloud" */
    val provider: String,
    val youtubeId: String? = null,
    val downloadedAtMs: Long = 0L
)

/**
 * Live state of an in-flight (or just-failed) download, keyed by song id.
 * Absence of an entry means "not downloading".
 */
sealed class DownloadState {
    /**
     * @param progressFraction 0..1, or a negative value when the total size is
     *        unknown (indeterminate).
     */
    data class Downloading(
        val progressFraction: Float,
        val bytesDownloaded: Long,
        val totalBytes: Long
    ) : DownloadState()

    data class Failed(val message: String) : DownloadState()
}

/**
 * IMPROVE(offline-downloads): repository that owns the lifecycle of downloaded
 * cloud songs.
 *
 * Design notes:
 *  - The index (metadata only) is persisted to DataStore as JSON — deliberately
 *    NOT Room — so no schema migration is needed.
 *  - The index is mirrored into an in-memory [StateFlow] at startup, which lets
 *    [com.theveloper.pixeltune.data.service.player.DualPlayerEngine] do a
 *    synchronous "is this song downloaded?" lookup on the playback path.
 *  - Files are downloaded with the same streaming OkHttp client + browser
 *    User-Agent the localhost proxies use, so upstream behaviour matches
 *    what playback already exercises.
 *  - DASH manifest URLs are rejected: a `.mpd` manifest is not a progressive
 *    audio file and would still require network access to play.
 */
@Singleton
class DownloadedSongsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val youTubeRepository: YouTubeRepository,
    private val soundCloudRepository: SoundCloudRepository,
    @StreamingOkHttpClient private val okHttpClient: OkHttpClient
) {

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val indexJson = Json { ignoreUnknownKeys = true }

    private val _downloadedSongs = MutableStateFlow<Map<String, DownloadedSong>>(emptyMap())
    /** All completed downloads, keyed by [DownloadedSong.songId]. */
    val downloadedSongs: StateFlow<Map<String, DownloadedSong>> = _downloadedSongs.asStateFlow()

    // Secondary index keyed by the STORAGE-NORMALIZED content URI
    // (CloudUriUtils.normalizeCloudUriForStorage). SoundCloud payloads get
    // URL-decoded during that normalization, which changes their hashCode —
    // so the songId lookup alone cannot match restored `soundcloud://` URIs.
    // The normalized-URI match closes that gap deterministically.
    @Volatile
    private var downloadedSongsByUri: Map<String, DownloadedSong> = emptyMap()

    private val _downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    /** In-flight / just-failed downloads, keyed by song id. */
    val downloadStates: StateFlow<Map<String, DownloadState>> = _downloadStates.asStateFlow()

    private val downloadJobs = ConcurrentHashMap<String, Job>()
    private val indexLoaded = CompletableDeferred<Unit>()

    private val downloadsDir: File
        get() = File(context.filesDir, "downloads").apply { if (!exists()) mkdirs() }

    init {
        repositoryScope.launch { loadIndex() }
    }

    // ---------------------------------------------------------------------
    // Lookups (playback path)
    // ---------------------------------------------------------------------

    fun isDownloaded(songId: String): Boolean = _downloadedSongs.value.containsKey(songId)

    /** The downloaded file for a song id, or null when missing/deleted on disk. */
    fun downloadedFileFor(songId: String): File? {
        val entry = _downloadedSongs.value[songId] ?: return null
        val file = File(entry.filePath)
        return if (file.exists() && file.length() > 0L) file else null
    }

    /**
     * Resolves a *playback* cloud URI (any of the forms the app produces) to a
     * downloaded file when one exists:
     *  - `youtube://<videoId>` / `http://127.0.0.1:<port>/youtube/<videoId>`
     *  - `soundcloud://<encoded>` / `http://127.0.0.1:<port>/soundcloud/<encoded>`
     *
     * Two lookup strategies, in order:
     *  1. derive the song id from the RAW uri payload (the SoundCloud id is
     *     the hash of the *encoded* payload — never decode it first);
     *  2. match the storage-normalized URI against the index (covers decoded
     *     `soundcloud://` payloads restored from persisted snapshots).
     */
    fun downloadedFileForUri(uriString: String): File? {
        if (uriString.isEmpty()) return null
        // Strategy 1: derive the song id from the RAW uri payload.
        songIdFromCloudUri(uriString)?.let { id ->
            downloadedFileFor(id)?.let { return it }
        }
        // Strategy 2: match the storage-normalized URI against the index.
        // Covers decoded `soundcloud://` payloads restored from persisted
        // snapshots (whose hash no longer equals the song id) as well as
        // plain `youtube://<id>` entries.
        val normalized = CloudUriUtils.normalizeCloudUriForStorage(uriString)
        downloadedSongsByUri[normalized]?.let { entry ->
            val file = File(entry.filePath)
            if (file.exists() && file.length() > 0L) return file
        }
        return null
    }

    /** Extracts the canonical song id from any cloud playback URI form. */
    fun songIdFromCloudUri(uriString: String): String? {
        if (uriString.isEmpty()) return null
        return when {
            uriString.startsWith("youtube://") -> {
                uriString.removePrefix("youtube://").takeIf { it.isNotEmpty() }
            }
            uriString.startsWith("soundcloud://") -> {
                val payload = uriString.removePrefix("soundcloud://")
                if (payload.isEmpty()) null else payload.hashCode().toString()
            }
            else -> {
                // Localhost proxy form: http://127.0.0.1:<port>/youtube/<id>
                //                                 /soundcloud/<encoded>
                //
                // IMPORTANT: parse from the RAW string, never Uri.parse().
                // Uri.pathSegments() DECODES the segment, but the SoundCloud
                // song id is the hash of the *encoded* payload — decoding
                // would produce a different string and break the lookup.
                val lower = uriString.lowercase()
                if (!lower.startsWith("http://127.0.0.1:") &&
                    !lower.startsWith("http://localhost:") &&
                    !lower.startsWith("https://127.0.0.1:") &&
                    !lower.startsWith("https://localhost:")
                ) return null
                // Strip scheme + authority, keep the raw (still-encoded) path.
                val rawPath = uriString.substringAfter("://", "").substringAfter("/", "")
                when {
                    rawPath.startsWith("youtube/") -> {
                        rawPath.removePrefix("youtube/")
                            .substringBefore("?")
                            .takeIf { it.isNotEmpty() }
                    }
                    rawPath.startsWith("soundcloud/") -> {
                        val payload = rawPath.removePrefix("soundcloud/")
                            .substringBefore("?")
                        if (payload.isEmpty()) null else payload.hashCode().toString()
                    }
                    else -> null
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Download / delete
    // ---------------------------------------------------------------------

    /**
     * Downloads [song] (a cloud-streamed YouTube/SoundCloud song) into
     * app-private storage. Idempotent: returns immediately when the song is
     * already downloaded, and refuses to start a second parallel download for
     * the same song.
     */
    fun downloadSong(song: Song) {
        val songId = song.id
        if (songId.isEmpty()) return
        if (downloadJobs.containsKey(songId)) return

        val job = repositoryScope.launch {
            // Wait for the persisted index before dedupe checks — a download
            // started in the first milliseconds after process start must not
            // re-download something that is already on disk.
            indexLoaded.await()
            if (isDownloaded(songId)) {
                return@launch
            }
            _downloadStates.value = _downloadStates.value + (songId to DownloadState.Downloading(-1f, 0L, -1L))
            try {
                val downloaded = performDownload(song)
                _downloadStates.value = _downloadStates.value - songId
                publishDownloadedSongs(_downloadedSongs.value + (songId to downloaded))
                persistIndex()
                Timber.i("Downloaded '%s' (%s) to %s", song.title, songId, downloaded.filePath)
            } catch (e: CancellationException) {
                cleanupPartialDownload(songId)
                _downloadStates.value = _downloadStates.value - songId
                Timber.i("Download cancelled for %s", songId)
                throw e
            } catch (e: Exception) {
                cleanupPartialDownload(songId)
                _downloadStates.value = _downloadStates.value +
                    (songId to DownloadState.Failed(e.message ?: "Download failed"))
                Timber.e(e, "Download failed for %s", songId)
                // Clear the failure flag after a short delay so a retry tap
                // works without the UI permanently showing an error state.
                delay(4_000L)
                if (_downloadStates.value[songId] is DownloadState.Failed) {
                    _downloadStates.value = _downloadStates.value - songId
                }
            } finally {
                downloadJobs.remove(songId)
            }
        }
        downloadJobs[songId] = job
    }

    /** Cancels an in-flight download for [songId] (no-op when none is running). */
    fun cancelDownload(songId: String) {
        downloadJobs[songId]?.cancel()
    }

    /** Whether an in-flight download exists for [songId]. */
    fun isDownloading(songId: String): Boolean = downloadJobs.containsKey(songId)

    /** Deletes the downloaded file + index entry for [songId]. */
    fun deleteDownload(songId: String) {
        downloadJobs[songId]?.cancel()
        repositoryScope.launch {
            val entry = _downloadedSongs.value[songId] ?: return@launch
            runCatching { File(entry.filePath).delete() }
            publishDownloadedSongs(_downloadedSongs.value - songId)
            _downloadStates.value = _downloadStates.value - songId
            persistIndex()
            Timber.i("Deleted download for %s", songId)
        }
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    /** Publishes a new downloads map to both in-memory indices. */
    private fun publishDownloadedSongs(map: Map<String, DownloadedSong>) {
        _downloadedSongs.value = map
        downloadedSongsByUri = map.values.associateBy { it.contentUri }
    }

    private suspend fun loadIndex() {
        try {
            val stored = runCatching {
                userPreferencesRepository.downloadedSongsJsonFlow.firstOrNull()
            }.getOrNull()
            val entries = if (stored.isNullOrBlank()) {
                emptyList()
            } else {
                runCatching { indexJson.decodeFromString<List<DownloadedSong>>(stored) }
                    .getOrDefault(emptyList())
            }
            // Drop entries whose file vanished (cleared data / manual removal).
            val valid = entries.filter { File(it.filePath).exists() && File(it.filePath).length() > 0L }
            publishDownloadedSongs(valid.associateBy { it.songId })
            if (valid.size != entries.size) {
                persistIndex()
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to load downloads index")
        } finally {
            indexLoaded.complete(Unit)
        }
    }

    private suspend fun persistIndex() {
        try {
            val list = _downloadedSongs.value.values.sortedByDescending { it.downloadedAtMs }
            userPreferencesRepository.saveDownloadedSongs(list)
        } catch (e: Exception) {
            Timber.w(e, "Failed to persist downloads index")
        }
    }

    private fun cleanupPartialDownload(songId: String) {
        val partial = File(downloadsDir, "$songId.part")
        runCatching { partial.delete() }
    }

    private suspend fun performDownload(song: Song): DownloadedSong {
        // 1. Resolve the direct upstream audio stream URL for the song.
        val provider = providerOf(song)
            ?: throw IllegalStateException("This song is not a downloadable online song.")
        val quality = runCatching {
            userPreferencesRepository.streamingQualityFlow.first()
        }.getOrNull() ?: StreamingQuality.NORMAL

        val streamUrl: String = when (provider) {
            PROVIDER_YOUTUBE -> {
                val videoId = song.youtubeId
                    ?: songIdFromCloudUri(song.contentUriString)
                    ?: song.id.takeIf { it.length == 11 }
                    ?: throw IllegalStateException("Missing YouTube video id.")
                youTubeRepository.getAudioStreamUrl(videoId, quality).getOrElse {
                    throw IllegalStateException("Could not resolve this song's audio stream.")
                }
            }
            PROVIDER_SOUNDCLOUD -> {
                val trackUrl = soundCloudTrackUrl(song)
                    ?: throw IllegalStateException("Missing SoundCloud track URL.")
                soundCloudRepository.getAudioStreamUrl(trackUrl, quality).getOrElse {
                    throw IllegalStateException("Could not resolve this song's audio stream.")
                }
            }
            else -> throw IllegalStateException("Unsupported provider.")
        }

        // DASH manifests are not self-contained audio files — they only work
        // while online, which defeats the purpose of an offline download.
        if (streamUrl.contains(".mpd") || streamUrl.contains("/api/manifest/dash")) {
            throw IllegalStateException("This song only offers a streamed manifest and can't be downloaded.")
        }

        // 2. Stream the body to a .part file, reporting progress.
        val extension = extensionFor(streamUrl, provider)
        val partFile = File(downloadsDir, "${song.id}.part")
        val finalFile = File(downloadsDir, "${song.id}.$extension")

        val request = Request.Builder()
            .url(streamUrl)
            .header("Accept-Encoding", "identity")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )
            .build()

        var totalBytes = -1L
        var downloadedBytes = 0L
        var lastReport = 0L

        withContext(Dispatchers.IO) {
            val call = okHttpClient.newCall(request)
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("Download failed (HTTP ${response.code}).")
                    }
                    val body = response.body ?: throw IllegalStateException("Empty download response.")
                    totalBytes = body.contentLength()
                    _downloadStates.value = _downloadStates.value +
                        (song.id to DownloadState.Downloading(if (totalBytes > 0) 0f else -1f, 0L, totalBytes))

                    partFile.outputStream().use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(BUFFER_SIZE_BYTES)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                downloadedBytes += read
                                val now = System.currentTimeMillis()
                                if (now - lastReport >= 250L) {
                                    lastReport = now
                                    val fraction = if (totalBytes > 0) {
                                        downloadedBytes.toFloat() / totalBytes
                                    } else {
                                        -1f
                                    }
                                    _downloadStates.value = _downloadStates.value +
                                        (song.id to DownloadState.Downloading(fraction, downloadedBytes, totalBytes))
                                }
                            }
                        }
                    }
                }

                if (downloadedBytes <= 0L) {
                    runCatching { partFile.delete() }
                    throw IllegalStateException("Downloaded file was empty.")
                }

                if (finalFile.exists()) {
                    runCatching { finalFile.delete() }
                }
                if (!partFile.renameTo(finalFile)) {
                    runCatching { partFile.delete() }
                    throw IllegalStateException("Could not finalize the download.")
                }
            } catch (e: Exception) {
                // Ensure a cancelled coroutine also aborts the OkHttp call's
                // blocking socket read immediately.
                if (e is CancellationException) {
                    runCatching { call.cancel() }
                }
                throw e
            }
        }

        return DownloadedSong(
            songId = song.id,
            title = song.title.ifBlank { "Unknown" },
            artist = song.artist.ifBlank { "Unknown Artist" },
            album = song.album.takeIf { it.isNotBlank() },
            albumArtUri = song.albumArtUriString,
            contentUri = CloudUriUtils.normalizeCloudUriForStorage(song.contentUriString),
            durationMs = song.duration.coerceAtLeast(0L),
            filePath = finalFile.absolutePath,
            mimeType = song.mimeType,
            sizeBytes = downloadedBytes,
            provider = provider,
            youtubeId = song.youtubeId,
            downloadedAtMs = System.currentTimeMillis()
        )
    }

    /** "youtube" | "soundcloud" | null (not downloadable). */
    private fun providerOf(song: Song): String? {
        val normalized = CloudUriUtils.normalizeCloudUriForStorage(song.contentUriString)
        return when {
            normalized.startsWith("youtube://") -> PROVIDER_YOUTUBE
            normalized.startsWith("soundcloud://") -> PROVIDER_SOUNDCLOUD
            song.youtubeId != null -> PROVIDER_YOUTUBE
            else -> null
        }
    }

    /**
     * SoundCloud playback URIs carry the URL-encoded track URL as their
     * payload; the plain track URL is the payload decoded ONCE. `song.path`
     * usually holds the raw https URL already. Payloads are extracted from the
     * RAW string (never Uri.parse) so the encoded form survives intact.
     */
    private fun soundCloudTrackUrl(song: Song): String? {
        if (song.path.startsWith("http") && song.path.contains("soundcloud.com")) {
            return song.path
        }
        val encodedPayload: String? = when {
            song.contentUriString.startsWith("soundcloud://") ->
                song.contentUriString.removePrefix("soundcloud://")
            song.contentUriString.contains("/soundcloud/") -> {
                // Live proxy URL form — extract the still-encoded payload.
                val rawPath = song.contentUriString.substringAfter("://", "")
                    .substringAfter("/", "")
                if (rawPath.startsWith("soundcloud/")) {
                    rawPath.removePrefix("soundcloud/").substringBefore("?")
                } else {
                    null
                }
            }
            else -> null
        }
        if (encodedPayload.isNullOrEmpty()) return null
        return runCatching { URLDecoder.decode(encodedPayload, "UTF-8") }.getOrNull()
    }

    private fun extensionFor(streamUrl: String, provider: String): String {
        // googlevideo URLs often carry a mime=audio%2Fmp4 style parameter.
        val mime = runCatching {
            Uri.parse(streamUrl).getQueryParameter("mime")
        }.getOrNull()
        return when {
            mime != null -> when {
                mime.contains("mpeg", ignoreCase = true) -> "mp3"
                mime.contains("webm", ignoreCase = true) ||
                    mime.contains("opus", ignoreCase = true) -> "webm"
                else -> "m4a"
            }
            streamUrl.substringBefore('?').endsWith(".mp3", ignoreCase = true) -> "mp3"
            provider == PROVIDER_SOUNDCLOUD -> "mp3"
            else -> "m4a"
        }
    }

    companion object {
        const val PROVIDER_YOUTUBE = "youtube"
        const val PROVIDER_SOUNDCLOUD = "soundcloud"
        private const val BUFFER_SIZE_BYTES = 32 * 1024
    }
}
