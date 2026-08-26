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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
 * IMPROVE(download-feedback): lifecycle events for a download, consumed by
 * the PlayerViewModel (snackbars) and the DownloadNotificationManager
 * (system notifications). Emitted only on real transitions — progress is
 * delivered as [Progress] events at the same throttled cadence as the
 * in-memory state updates (250 ms).
 */
sealed class DownloadEvent {
    abstract val songId: String
    abstract val title: String

    /** The download job actually started (passed dedupe + already-downloaded checks). */
    data class Started(
        override val songId: String,
        override val title: String
    ) : DownloadEvent()

    data class Progress(
        override val songId: String,
        override val title: String,
        val progressPercent: Int,
        val indeterminate: Boolean,
        val bytesDownloaded: Long,
        val totalBytes: Long
    ) : DownloadEvent()

    data class Completed(
        override val songId: String,
        override val title: String
    ) : DownloadEvent()

    /**
     * @param startedStreaming false → the download could not even start
     *        (stream URL resolution failed / DASH-only / HTTP error before any
     *        byte arrived); true → a download that had already started
     *        streaming failed part-way through.
     */
    data class Failed(
        override val songId: String,
        override val title: String,
        val message: String,
        val startedStreaming: Boolean
    ) : DownloadEvent()

    data class Cancelled(
        override val songId: String,
        override val title: String
    ) : DownloadEvent()
}

/** Download aborted before a single byte was streamed ("couldn't start"). */
private class DownloadStartException(message: String) : IllegalStateException(message)

/** A download that was already streaming failed part-way through. */
private class DownloadMidwayException(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

/**
 * Live state of an in-flight (or just-failed) download, keyed by song id.
 * Absence of an entry means "not downloading".
 */
sealed class DownloadState {
    /**
     * @param progressFraction 0..1, or a negative value when the total size is
     *        unknown (indeterminate).
     * @param title song display title, carried so UI surfaces that only see
     *        the state map (e.g. the Library DOWNLOADS chip's active rows)
     *        can label the download without extra bookkeeping.
     */
    data class Downloading(
        val progressFraction: Float,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val title: String = ""
    ) : DownloadState()

    data class Failed(val message: String) : DownloadState()
}

/**
 * IMPROVE(downloads-chip): maps a persisted [DownloadedSong] back to the
 * [Song] shape the player consumes. The content URI keeps its restart-safe
 * scheme form (`youtube://<id>` / `soundcloud://<encoded>`), which
 * DualPlayerEngine already resolves to the app-private file when offline.
 */
fun DownloadedSong.toSong(): Song = Song(
    id = songId,
    title = title,
    artist = artist,
    artistId = -1L,
    artists = emptyList(),
    album = album ?: "",
    albumId = -1L,
    albumArtist = null,
    path = contentUri,
    contentUriString = contentUri,
    albumArtUriString = albumArtUri,
    duration = durationMs,
    genre = null,
    lyrics = null,
    isFavorite = false,
    trackNumber = 0,
    year = 0,
    dateAdded = downloadedAtMs / 1000L,
    dateModified = downloadedAtMs / 1000L,
    mimeType = mimeType ?: "audio/mp4",
    bitrate = null,
    sampleRate = null,
    telegramFileId = null,
    telegramChatId = null,
    neteaseId = null,
    gdriveFileId = null,
    youtubeId = youtubeId
)

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
    @StreamingOkHttpClient private val okHttpClient: OkHttpClient,
    // IMPROVE(download-progress-notification): real-time progress cards in
    // the system notification panel, driven directly from the download loop.
    private val downloadNotificationManager: DownloadNotificationManager
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

    // IMPROVE(download-feedback): lifecycle events for snackbars + notifications.
    private val _downloadEvents = MutableSharedFlow<DownloadEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val downloadEvents: SharedFlow<DownloadEvent> = _downloadEvents.asSharedFlow()

    private val downloadJobs = ConcurrentHashMap<String, Job>()
    private val indexLoaded = CompletableDeferred<Unit>()

    // Titles of in-flight downloads, so failure/cancel events can name the
    // song even when the Song object is no longer reachable.
    private val inFlightTitles = ConcurrentHashMap<String, String>()
    // Last integer percent posted to the notification manager, so the system
    // card refreshes only on visible change instead of every 250 ms tick.
    private val lastNotifiedPercent = ConcurrentHashMap<String, Int>()
    // Last timestamp an indeterminate progress card was refreshed — keeps
    // size-unknown downloads to one notification post per second.
    private val lastNotifiedAt = ConcurrentHashMap<String, Long>()

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
            inFlightTitles[songId] = song.title.ifBlank { "Unknown" }
            _downloadStates.value = _downloadStates.value +
                (songId to DownloadState.Downloading(
                    progressFraction = -1f,
                    bytesDownloaded = 0L,
                    totalBytes = -1L,
                    title = inFlightTitles[songId] ?: ""
                ))
            // IMPROVE(download-feedback): confirm the start via snackbar + a
            // live indeterminate progress card in the notification panel.
            _downloadEvents.tryEmit(DownloadEvent.Started(songId, inFlightTitles[songId] ?: ""))
            postProgressNotification(
                songId = songId,
                title = inFlightTitles[songId] ?: "",
                fraction = -1f,
                bytesDownloaded = 0L,
                totalBytes = -1L
            )
            try {
                val downloaded = performDownload(song)
                _downloadStates.value = _downloadStates.value - songId
                publishDownloadedSongs(_downloadedSongs.value + (songId to downloaded))
                persistIndex()
                Timber.i("Downloaded '%s' (%s) to %s", song.title, songId, downloaded.filePath)
                // IMPROVE(download-feedback): completion snackbar + a
                // short-lived "Downloaded" system notification.
                _downloadEvents.tryEmit(DownloadEvent.Completed(songId, downloaded.title))
                downloadNotificationManager.notifyCompleted(songId, downloaded.title)
            } catch (e: CancellationException) {
                cleanupPartialDownload(songId)
                _downloadStates.value = _downloadStates.value - songId
                Timber.i("Download cancelled for %s", songId)
                _downloadEvents.tryEmit(
                    DownloadEvent.Cancelled(songId, inFlightTitles[songId] ?: "")
                )
                downloadNotificationManager.cancelProgress(songId)
                throw e
            } catch (e: Exception) {
                cleanupPartialDownload(songId)
                _downloadStates.value = _downloadStates.value +
                    (songId to DownloadState.Failed(e.message ?: "Download failed"))
                Timber.e(e, "Download failed for %s", songId)
                // IMPROVE(download-feedback): distinguish "couldn't start"
                // (no byte ever streamed) from "failed midway" — the user
                // asked to be informed of both, differently.
                val startedStreaming = e !is DownloadStartException
                _downloadEvents.tryEmit(
                    DownloadEvent.Failed(
                        songId = songId,
                        title = inFlightTitles[songId] ?: "",
                        message = e.message ?: "Download failed",
                        startedStreaming = startedStreaming
                    )
                )
                downloadNotificationManager.notifyFailed(
                    songId = songId,
                    title = inFlightTitles[songId] ?: "",
                    reason = e.message ?: "Download failed"
                )
                // Clear the failure flag after a short delay so a retry tap
                // works without the UI permanently showing an error state.
                delay(4_000L)
                if (_downloadStates.value[songId] is DownloadState.Failed) {
                    _downloadStates.value = _downloadStates.value - songId
                }
            } finally {
                downloadJobs.remove(songId)
                inFlightTitles.remove(songId)
                lastNotifiedPercent.remove(songId)
                lastNotifiedAt.remove(songId)
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
        downloadNotificationManager.cancelProgress(songId)
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

    /**
     * IMPROVE(download-progress-notification): posts / refreshes the live
     * progress card, throttled to integer-percent changes (plus a forced
     * refresh on the indeterminate → determinate transition) so the system
     * notification panel shows smooth real-time progress without flooding
     * NotificationManager.notify.
     */
    private fun postProgressNotification(
        songId: String,
        title: String,
        fraction: Float,
        bytesDownloaded: Long,
        totalBytes: Long
    ) {
        val indeterminate = fraction < 0f || totalBytes <= 0L
        val percent = if (indeterminate) 0 else (fraction * 100f).toInt().coerceIn(0, 100)
        val now = System.currentTimeMillis()
        if (indeterminate) {
            // At most one indeterminate refresh per second (the bar itself is
            // animated by the system, so the card only needs a periodic touch).
            val last = lastNotifiedAt[songId] ?: 0L
            if (now - last < 1_000L) return
            lastNotifiedAt[songId] = now
        } else {
            val last = lastNotifiedPercent[songId]
            if (last != null && percent == last && percent != 100) {
                return
            }
            lastNotifiedPercent[songId] = percent
        }
        // IMPROVE(download-feedback): Progress events also flow to the event
        // stream so any UI observer (e.g. the Downloads chip's active rows)
        // can mirror the notification cadence.
        _downloadEvents.tryEmit(
            DownloadEvent.Progress(
                songId = songId,
                title = title,
                progressPercent = percent,
                indeterminate = indeterminate,
                bytesDownloaded = bytesDownloaded,
                totalBytes = totalBytes
            )
        )
        runCatching {
            downloadNotificationManager.notifyProgress(
                songId = songId,
                title = title,
                progressPercent = percent,
                indeterminate = indeterminate,
                bytesDownloaded = bytesDownloaded,
                totalBytes = totalBytes
            )
        }
    }

    private suspend fun performDownload(song: Song): DownloadedSong {
        // 1. Resolve the direct upstream audio stream URL for the song.
        //    IMPROVE(download-feedback): every failure in this pre-stream phase
        //    is a DownloadStartException — the download "couldn't start".
        val provider = providerOf(song)
            ?: throw DownloadStartException("This song is not a downloadable online song.")
        val quality = runCatching {
            userPreferencesRepository.streamingQualityFlow.first()
        }.getOrNull() ?: StreamingQuality.NORMAL

        val streamUrl: String = when (provider) {
            PROVIDER_YOUTUBE -> {
                val videoId = song.youtubeId
                    ?: songIdFromCloudUri(song.contentUriString)
                    ?: song.id.takeIf { it.length == 11 }
                    ?: throw DownloadStartException("Missing YouTube video id.")
                youTubeRepository.getAudioStreamUrl(videoId, quality).getOrElse {
                    throw DownloadStartException("Could not resolve this song's audio stream.")
                }
            }
            PROVIDER_SOUNDCLOUD -> {
                val trackUrl = soundCloudTrackUrl(song)
                    ?: throw DownloadStartException("Missing SoundCloud track URL.")
                soundCloudRepository.getAudioStreamUrl(trackUrl, quality).getOrElse {
                    throw DownloadStartException("Could not resolve this song's audio stream.")
                }
            }
            else -> throw DownloadStartException("Unsupported provider.")
        }

        // DASH manifests are not self-contained audio files — they only work
        // while online, which defeats the purpose of an offline download.
        if (streamUrl.contains(".mpd") || streamUrl.contains("/api/manifest/dash")) {
            throw DownloadStartException(
                "This song only offers a streamed manifest and can't be downloaded."
            )
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
                        throw DownloadStartException("Download failed (HTTP ${response.code}).")
                    }
                    val body = response.body ?: throw DownloadStartException("Empty download response.")
                    totalBytes = body.contentLength()
                    _downloadStates.value = _downloadStates.value +
                        (song.id to DownloadState.Downloading(
                            progressFraction = if (totalBytes > 0) 0f else -1f,
                            bytesDownloaded = 0L,
                            totalBytes = totalBytes,
                            title = inFlightTitles[song.id] ?: song.title
                        ))
                    postProgressNotification(
                        songId = song.id,
                        title = inFlightTitles[song.id] ?: song.title,
                        fraction = if (totalBytes > 0) 0f else -1f,
                        bytesDownloaded = 0L,
                        totalBytes = totalBytes
                    )

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
                                        (song.id to DownloadState.Downloading(
                                            progressFraction = fraction,
                                            bytesDownloaded = downloadedBytes,
                                            totalBytes = totalBytes,
                                            title = inFlightTitles[song.id] ?: song.title
                                        ))
                                    // IMPROVE(download-progress-notification): live
                                    // progress card in the notification panel.
                                    postProgressNotification(
                                        songId = song.id,
                                        title = inFlightTitles[song.id] ?: song.title,
                                        fraction = fraction,
                                        bytesDownloaded = downloadedBytes,
                                        totalBytes = totalBytes
                                    )
                                }
                            }
                        }
                    }
                }

                if (downloadedBytes <= 0L) {
                    runCatching { partFile.delete() }
                    throw DownloadStartException("Downloaded file was empty.")
                }

                if (finalFile.exists()) {
                    runCatching { finalFile.delete() }
                }
                if (!partFile.renameTo(finalFile)) {
                    runCatching { partFile.delete() }
                    throw DownloadMidwayException("Could not finalize the download.")
                }
            } catch (e: java.io.IOException) {
                // IMPROVE(download-feedback): an IO failure before the first
                // byte means the download couldn't start; once bytes have
                // streamed, it's a mid-way failure. Cancellation passes through
                // untouched (handled by the outer catch).
                runCatching { call.cancel() }
                throw if (downloadedBytes > 0L) {
                    DownloadMidwayException("Connection lost during download.", e)
                } else {
                    DownloadStartException("Could not reach the download server.")
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
