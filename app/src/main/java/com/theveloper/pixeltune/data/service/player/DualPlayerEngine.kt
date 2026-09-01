package com.theveloper.pixeltune.data.service.player

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.common.Format
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import android.os.Handler
import kotlin.math.max
//import androidx.media3.exoplayer.ffmpeg.FfmpegAudioRenderer
import com.theveloper.pixeltune.data.model.TransitionSettings
import com.theveloper.pixeltune.utils.envelope
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.asStateFlow // Added
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

import com.theveloper.pixeltune.data.netease.NeteaseStreamProxy
import com.theveloper.pixeltune.data.soundcloud.SoundCloudStreamProxy
import com.theveloper.pixeltune.data.telegram.TelegramRepository
import com.theveloper.pixeltune.data.youtube.YouTubeStreamProxy
import com.theveloper.pixeltune.data.downloads.DownloadedSongsRepository
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import android.net.Uri
import java.io.File

/**
 * Manages two ExoPlayer instances (A and B) to enable seamless transitions.
 *
 * Player A is the designated "master" player, which is exposed to the MediaSession.
 * Player B is the auxiliary player used to pre-buffer and fade in the next track.
 * After a transition, Player A adopts the state of Player B, ensuring continuity.
 */
@OptIn(UnstableApi::class)
@Singleton
class DualPlayerEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telegramRepository: TelegramRepository,
    private val telegramStreamProxy: com.theveloper.pixeltune.data.telegram.TelegramStreamProxy,
    private val neteaseStreamProxy: NeteaseStreamProxy,
    private val telegramCacheManager: com.theveloper.pixeltune.data.telegram.TelegramCacheManager,
    private val connectivityStateHolder: com.theveloper.pixeltune.presentation.viewmodel.ConnectivityStateHolder,
    // FIX(cloud-favorites): YouTube + SoundCloud proxies are required so that
    // URIs persisted as `youtube://<videoId>` or `soundcloud://<encoded>` (e.g.
    // favorited cloud songs loaded from the Liked tab) can be resolved to the
    // current session's localhost HTTP proxy URL at play time. Without this,
    // a favorited YouTube song becomes unplayable after an app restart because
    // the stored HTTP proxy URL had an ephemeral port.
    private val youTubeStreamProxy: YouTubeStreamProxy,
    private val soundCloudStreamProxy: SoundCloudStreamProxy,
    // IMPROVE(offline-downloads): when a cloud song has been downloaded to
    // app-private storage, playback must use the local file instead of the
    // localhost proxy — this is what makes downloaded songs work offline.
    private val downloadedSongsRepository: DownloadedSongsRepository
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var transitionJob: Job? = null
    private var transitionRunning = false

    /**
     * FIX(volume-reset): the user's selected base volume (0..1) for the MASTER
     * player and as the scaling factor for every crossfade curve.
     *
     * Historically every transition path hardcoded `playerA.volume = 1f` and
     * faded Player B in toward 1f — silently stomping the user's volume slider
     * back to 100% whenever a track was tapped (queue timeline change ->
     * cancelNext()) or auto-advanced (crossfade ended at 1f). The service keeps
     * this field in sync with the genuine user selection (ReplayGain-adjusted
     * writes are filtered out on the service side), and ALL engine volume math
     * now scales through it instead of a literal 1f.
     */
    @Volatile
    var userVolume: Float = 1f

    private lateinit var playerA: ExoPlayer
    private lateinit var playerB: ExoPlayer

    // Adaptive buffering: one SourceTunedLoadControl per player. The references
    // swap together with the players in performOverlapTransition() so each
    // control keeps answering for the player it was built with.
    private lateinit var masterLoadControl: SourceTunedLoadControl
    private lateinit var auxiliaryLoadControl: SourceTunedLoadControl

    private val onPlayerSwappedListeners = mutableListOf<(Player) -> Unit>()
    
    // Active Audio Session ID Flow
    private val _activeAudioSessionId = kotlinx.coroutines.flow.MutableStateFlow(0)
    val activeAudioSessionId: kotlinx.coroutines.flow.StateFlow<Int> = _activeAudioSessionId.asStateFlow()

    // Audio Focus Management
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var isFocusLossPause = false

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Timber.tag("TransitionDebug").d("AudioFocus LOSS. Pausing.")
                isFocusLossPause = false
                playerA.playWhenReady = false
                playerB.playWhenReady = false
                abandonAudioFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Timber.tag("TransitionDebug").d("AudioFocus LOSS_TRANSIENT. Pausing.")
                isFocusLossPause = true
                playerA.playWhenReady = false
                playerB.playWhenReady = false
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Timber.tag("TransitionDebug").d("AudioFocus GAIN. Resuming if paused by loss.")
                if (isFocusLossPause) {
                    isFocusLossPause = false
                    playerA.playWhenReady = true
                    if (transitionRunning) playerB.playWhenReady = true
                }
            }
        }
    }

    // Listener to attach to the active master player (playerA)
    private val masterPlayerListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady) {
                requestAudioFocus()
            } else {
                if (!isFocusLossPause) {
                    abandonAudioFocus()
                }
            }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            // Integración de test/telegram-streaming-integration
            if (audioSessionId != 0 && _activeAudioSessionId.value != audioSessionId) {
                _activeAudioSessionId.value = audioSessionId
                Timber.tag("TransitionDebug").d("Master audio session changed: %d", audioSessionId)
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Adaptive buffering: retune the master player's LoadControl
            // profile to the incoming source type before loading begins.
            // Items reach the player with their ORIGINAL scheme (proxy/local
            // resolution happens inside the DataSource at load time), which
            // is exactly what the scheme-based classification needs.
            masterLoadControl.select(
                SourceTunedLoadControl.kindForUriScheme(
                    mediaItem?.localConfiguration?.uri?.scheme
                )
            )

            // Integración de feature/telegram-cloud-sync
            val uri = mediaItem?.localConfiguration?.uri
            if (uri?.scheme == "telegram") {
                scope.launch {
                    val result = telegramRepository.resolveTelegramUri(uri.toString())
                    val fileId = result?.first
                    telegramCacheManager.setActivePlayback(fileId)
                    Timber.tag("DualPlayerEngine").d("Telegram playback active: fileId=$fileId")
                }
                // Telegram streaming necesita Wake Mode para evitar cortes
                (playerA as? ExoPlayer)?.setWakeMode(C.WAKE_MODE_LOCAL)
            } else {
                // Limpieza para canciones que no son de Telegram
                telegramCacheManager.setActivePlayback(null)
                (playerA as? ExoPlayer)?.setWakeMode(C.WAKE_MODE_LOCAL)
            }

            // --- Pre-Resolve Next/Prev Tracks para Performance ---
            try {
                val currentIndex = playerA.currentMediaItemIndex
                if (currentIndex != C.INDEX_UNSET) {
                    // 1. Pre-resolver SIGUIENTE
                    if (currentIndex + 1 < playerA.mediaItemCount) {
                        val nextItem = playerA.getMediaItemAt(currentIndex + 1)
                        val nextUri = nextItem.localConfiguration?.uri
                        if (nextUri?.scheme == "telegram") {
                            telegramRepository.preResolveTelegramUri(nextUri.toString())
                        }
                    }
                    // 2. Pre-resolver ANTERIOR (para rapidez al retroceder)
                    if (currentIndex - 1 >= 0) {
                        val prevItem = playerA.getMediaItemAt(currentIndex - 1)
                        val prevUri = prevItem.localConfiguration?.uri
                        if (prevUri?.scheme == "telegram") {
                            telegramRepository.preResolveTelegramUri(prevUri.toString())
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Error during pre-resolution in onMediaItemTransition")
            }
        }
    }

    fun addPlayerSwapListener(listener: (Player) -> Unit) {
        onPlayerSwappedListeners.add(listener)
    }

    fun removePlayerSwapListener(listener: (Player) -> Unit) {
        onPlayerSwappedListeners.remove(listener)
    }

    /** The master player instance that should be connected to the MediaSession. */
    val masterPlayer: Player
        get() = playerA

    fun isTransitionRunning(): Boolean = transitionRunning

    /**
     * Returns the audio session ID of the master player.
     * Use this to attach audio effects like Equalizer.
     */
    /**
     * Returns the audio session ID of the master player.
     * Use this to attach audio effects like Equalizer.
     */
    fun getAudioSessionId(): Int = playerA.audioSessionId

    private var isReleased = false

    // Cache of pre-resolved URIs: original cloud URI string -> resolved playable URI
    private val resolvedUriCache = java.util.concurrent.ConcurrentHashMap<String, Uri>()

    init {
        initialize()
    }

    fun initialize() {
        if (!isReleased && ::playerA.isInitialized && playerA.applicationLooper.thread.isAlive) return

        // Adaptive buffering: fresh controls per engine (re)initialization.
        masterLoadControl = SourceTunedLoadControl.create()
        auxiliaryLoadControl = SourceTunedLoadControl.create()

        // Clean up if needed (though unlikely to be called if already initialized and alive)
        if (::playerA.isInitialized) {
            try { playerA.release() } catch (e: Exception) { /* Ignore */ }
        }
        if (::playerB.isInitialized) {
            try { playerB.release() } catch (e: Exception) { /* Ignore */ }
        }

        // We initialize BOTH players with NO internal focus handling.
        // We manage Audio Focus manually via AudioFocusManager.
        playerA = buildPlayer(handleAudioFocus = false, loadControl = masterLoadControl)
        playerB = buildPlayer(handleAudioFocus = false, loadControl = auxiliaryLoadControl)

        // Attach listener to initial master
        playerA.addListener(masterPlayerListener)

        // Initialize active session ID
        _activeAudioSessionId.value = playerA.audioSessionId
        
        isReleased = false
    }

    private fun requestAudioFocus() {
        if (audioFocusRequest != null) return // Already have or requested

        val attributes = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(focusChangeListener)
            .build()

        val result = audioManager.requestAudioFocus(request)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            audioFocusRequest = request
        } else {
            Timber.tag("TransitionDebug").w("AudioFocus Request Failed: $result")
            playerA.playWhenReady = false
        }
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
            audioFocusRequest = null
        }
    }

    private fun buildPlayer(handleAudioFocus: Boolean, loadControl: SourceTunedLoadControl): ExoPlayer {
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                audioSink: AudioSink,
                eventHandler: Handler,
                eventListener: AudioRendererEventListener,
                out: ArrayList<Renderer>
            ) {
                // Use provided sink or create one with Float output enabled
                // Note: We use the provided audioSink if it works, but here we want to enforce config.
                // Since super.buildAudioRenderers takes the sink, we can just pass our configured one.
                // But wait, the parameter 'audioSink' is passed IN. 
                // We should probably ignore the passed one if we want to enforce ours, OR configure ours and pass it to super.
                
                val sink = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false) // Disable Float output to fix CCodec/Hardware errors on some devices
                    .build()

                out.add(object : MediaCodecAudioRenderer(
                    context,
                    mediaCodecSelector,
                    enableDecoderFallback,
                    eventHandler,
                    eventListener,
                    sink
                ) {
                    override fun getCodecMaxInputSize(
                        codecInfo: MediaCodecInfo,
                        format: Format,
                        streamFormats: Array<Format>
                    ): Int {
                        // Force minimum 512KB buffer for FLAC/High-res audio
                        return max(super.getCodecMaxInputSize(codecInfo, format, streamFormats), 512 * 1024)
                    }
                })

                super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, sink, eventHandler, eventListener, out)
            }
        }.setEnableAudioFloatOutput(false) // Disable Float output helper
         .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
            
        // Lightweight synchronous resolver: only performs cache lookups, NEVER blocks.
        // All heavy resolution (network I/O, proxy readiness) is done ahead of time
        // in resolveCloudUri() which is called from coroutines before ExoPlayer sees the URI.
        val resolver = object : ResolvingDataSource.Resolver {
            override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
                val scheme = dataSpec.uri.scheme
                if (scheme == "telegram" || scheme == "netease" ||
                    scheme == "youtube" || scheme == "soundcloud"
                ) {
                    val originalUri = dataSpec.uri.toString()
                    val resolved = resolvedUriCache[originalUri]
                    if (resolved != null) {
                        Timber.tag("DualPlayerEngine").d("resolveDataSpec: cache hit for $scheme URI")
                        return dataSpec.buildUpon().setUri(resolved).build()
                    }
                    // Cache miss — URI was not pre-resolved. Log warning but do NOT block.
                    // This can happen if the URI was added to the queue without pre-resolution
                    // (e.g., via external intent or legacy code path).
                    Timber.tag("DualPlayerEngine").w("resolveDataSpec: cache MISS for $originalUri — playback may fail")
                }
                // IMPROVE(offline-downloads): downloaded cloud songs must play
                // from their app-private local file even when the queue item
                // still carries the live localhost proxy URL (the normal form
                // for YouTube/SoundCloud search results). The lookup is an
                // in-memory map read + a File.exists() stat — cheap enough for
                // the loader thread — and is a no-op for anything that isn't
                // a downloaded youtube:// / soundcloud:// / proxy URI.
                val downloadedFile = downloadedSongsRepository
                    .downloadedFileForUri(dataSpec.uri.toString())
                if (downloadedFile != null) {
                    val fileUri = Uri.fromFile(downloadedFile)
                    resolvedUriCache[dataSpec.uri.toString()] = fileUri
                    Timber.tag("DualPlayerEngine")
                        .d("resolveDataSpec: using downloaded file ${downloadedFile.name}")
                    return dataSpec.buildUpon().setUri(fileUri).build()
                }
                return dataSpec
            }
        }
        
        val dataSourceFactory = DefaultDataSource.Factory(
            context,
            // FIX(youtube-crash): ExoPlayer's default DefaultHttpDataSource has
            // 8s connect + 8s read timeouts. When the URI is a localhost cloud
            // proxy URL (`http://127.0.0.1:<port>/youtube/<id>` etc.), the
            // proxy's Ktor handler must FIRST fetch the upstream stream URL
            // via NewPipe (which can take several seconds for YouTube's
            // bot-detection dance) and THEN open the OkHttp connection to
            // googlevideo before it can flush the HTTP status line back to
            // ExoPlayer. With the default 8s timeout, ExoPlayer's
            // DefaultHttpDataSource.open() was throwing SocketTimeoutException
            // at `HttpURLConnectionImpl.getResponseCode()` before the proxy's
            // first byte arrived — surfacing to the user as "app freezes /
            // crashes when tapping a YouTube search result to play".
            //
            // Bumping both timeouts to 30s gives the proxy enough headroom
            // for the NewPipe + OkHttp upstream fetch under typical
            // residential mobile latency. The actual byte streaming
            // (CloudStreamForwarder) uses the @StreamingOkHttpClient which
            // already has readTimeout=0 (infinite), so the 30s read timeout
            // here only gates the initial response status line — once bytes
            // start flowing, this timeout no longer fires.
            DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(30_000)
                .setReadTimeoutMs(30_000)
                .setAllowCrossProtocolRedirects(true)
        )
        val resolvingFactory = ResolvingDataSource.Factory(dataSourceFactory, resolver)

        // Buffering is adaptive per source type (local files / cloud-drive
        // proxies / remote streams): the engine selects the profile on every
        // media item transition and before preparing the auxiliary player —
        // see SourceTunedLoadControl.

        return ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolvingFactory))
            .setLoadControl(loadControl)
            .build().apply {
            setAudioAttributes(audioAttributes, handleAudioFocus)
            val offloadDisabledPrefs = TrackSelectionParameters.AudioOffloadPreferences.Builder()
                .setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED)
                .build()
            setTrackSelectionParameters(
                trackSelectionParameters
                    .buildUpon()
                    .setAudioOffloadPreferences(offloadDisabledPrefs)
                    .build()
            )
            setHandleAudioBecomingNoisy(true) // Force player to pause automatically when audio is rerouted from a headset to device speakers
            setWakeMode(C.WAKE_MODE_LOCAL) // Use CPU lock only. WiFi lock unused as we proxy via localhost. Saves battery.
            // Explicitly keep both players live so they can overlap without affecting each other
            playWhenReady = false
        }
    }

    /**
     * Enables or disables pausing at the end of media items for the master player.
     * This is crucial for controlling the transition manually.
     */
    fun setPauseAtEndOfMediaItems(shouldPause: Boolean) {
        playerA.pauseAtEndOfMediaItems = shouldPause
    }

    /**
     * Resolves a cloud URI (telegram:// or netease://) to a playable URI.
     * Performs all network I/O and proxy readiness checks on the calling coroutine,
     * keeping ExoPlayer's playback thread free from blocking.
     *
     * Results are cached in [resolvedUriCache] for the synchronous [resolveDataSpec] to use.
     *
     * @return The resolved playable URI, or the original URI if resolution fails/not needed.
     */
    suspend fun resolveCloudUri(uri: Uri): Uri {
        val uriString = uri.toString()

        // IMPROVE(offline-downloads): a downloaded cloud song always plays
        // from its app-private local file — no proxy, no network, fully
        // offline-capable. Checked before the cache so a download that
        // completed after a previous resolve still wins.
        downloadedSongsRepository.downloadedFileForUri(uriString)?.let { file ->
            val fileUri = Uri.fromFile(file)
            resolvedUriCache[uriString] = fileUri
            return fileUri
        }

        // Fast path: already resolved
        resolvedUriCache[uriString]?.let { return it }

        val resolved: Uri? = when (uri.scheme) {
            "telegram" -> resolveTelegramUriAsync(uri, uriString)
            "netease" -> resolveNeteaseUriAsync(uriString)
            // FIX(cloud-favorites): resolve persisted youtube://<videoId> and
            // soundcloud://<encoded> scheme URIs to the current session's
            // localhost HTTP proxy URL. This is what makes favorited cloud
            // songs playable after an app restart.
            "youtube" -> resolveYouTubeUriAsync(uriString)
            "soundcloud" -> resolveSoundCloudUriAsync(uriString)
            else -> null
        }

        if (resolved != null) {
            resolvedUriCache[uriString] = resolved
            return resolved
        }
        return uri
    }

    private suspend fun resolveTelegramUriAsync(uri: Uri, uriString: String): Uri? {
        var fileId: Int? = null
        var fileSize: Long = 0L

        val pathSegments = uri.pathSegments
        if (pathSegments.isNotEmpty()) {
            val result = telegramRepository.resolveTelegramUri(uriString)
            fileId = result?.first
            fileSize = result?.second ?: 0L
        } else {
            // Fallback to Legacy Scheme: telegram://fileId (host)
            fileId = uri.host?.toIntOrNull()
        }

        if (fileId == null) return null

        Timber.tag("DualPlayerEngine").d("Async resolving Telegram URI for fileId: $fileId")

        // Check if file is already downloaded to use direct file access
        val fileInfo = telegramRepository.getFile(fileId)
        if (fileInfo?.local?.isDownloadingCompleted == true && fileInfo.local.path.isNotEmpty()) {
            Timber.tag("DualPlayerEngine").d("File $fileId is downloaded. Using direct file playback.")
            return Uri.fromFile(File(fileInfo.local.path))
        }

        // Not cached locally. Check connectivity.
        val isOnline = connectivityStateHolder.isOnline.value
        if (!isOnline) {
            Timber.tag("DualPlayerEngine").w("Blocked playback: Offline and not cached (fileId=$fileId).")
            connectivityStateHolder.triggerOfflineBlockedEvent()
            return null
        }

        Timber.tag("DualPlayerEngine").d("File $fileId not downloaded. Using StreamProxy.")

        // Wait for StreamProxy to be ready (non-blocking — runs on coroutine)
        if (!telegramStreamProxy.isReady()) {
            Timber.tag("DualPlayerEngine").w("StreamProxy not ready, awaiting...")
            val proxyReady = telegramStreamProxy.awaitReady(5_000L)
            if (!proxyReady) {
                Timber.tag("DualPlayerEngine").e("StreamProxy not ready after timeout")
                return null
            }
        }

        val proxyUrl = telegramStreamProxy.getProxyUrl(fileId, fileSize)
        return if (proxyUrl.isNotEmpty()) Uri.parse(proxyUrl) else null
    }

    private suspend fun resolveNeteaseUriAsync(uriString: String): Uri? {
        Timber.tag("DualPlayerEngine").d("Async resolving Netease URI: $uriString")

        if (!neteaseStreamProxy.isReady()) {
            Timber.tag("DualPlayerEngine").w("NeteaseStreamProxy not ready, awaiting...")
            val proxyReady = neteaseStreamProxy.awaitReady(5_000L)
            if (!proxyReady) {
                Timber.tag("DualPlayerEngine").e("NeteaseStreamProxy not ready after timeout")
                return null
            }
        }

        val proxyUrl = neteaseStreamProxy.resolveNeteaseUri(uriString)
        if (!proxyUrl.isNullOrBlank()) {
            return Uri.parse(proxyUrl)
        }

        Timber.tag("DualPlayerEngine").w("Failed to resolve Netease URI: $uriString")
        return null
    }

    /**
     * Resolves a `youtube://<videoId>` URI to the current session's YouTube proxy
     * HTTP URL. The proxy URL contains an ephemeral port, so we can never persist
     * it directly — we always resolve from the scheme form at play time.
     *
     * Returns null if the proxy isn't ready after the standard 5-second wait,
     * or if the URI's host (the video ID) is missing / doesn't match YouTube's
     * 11-char video ID format.
     */
    private suspend fun resolveYouTubeUriAsync(uriString: String): Uri? {
        Timber.tag("DualPlayerEngine").d("Async resolving YouTube URI: $uriString")

        if (!youTubeStreamProxy.isReady()) {
            Timber.tag("DualPlayerEngine").w("YouTubeStreamProxy not ready, awaiting...")
            val proxyReady = youTubeStreamProxy.awaitReady(5_000L)
            if (!proxyReady) {
                Timber.tag("DualPlayerEngine").e("YouTubeStreamProxy not ready after timeout")
                return null
            }
        }

        val proxyUrl = youTubeStreamProxy.resolveYouTubeUri(uriString)
        if (!proxyUrl.isNullOrBlank()) {
            return Uri.parse(proxyUrl)
        }

        Timber.tag("DualPlayerEngine").w("Failed to resolve YouTube URI: $uriString")
        return null
    }

    /**
     * Resolves a `soundcloud://<encoded>` URI to the current session's SoundCloud
     * proxy HTTP URL. Mirrors [resolveYouTubeUriAsync] — see that function's docs
     * for why we cannot persist the proxy URL directly.
     *
     * The proxy's resolver handles BOTH payload encodings SoundCloud scheme URIs
     * are persisted in: the URL-encoded token (single opaque segment) and the
     * decoded track URL that [CloudUriUtils.normalizeCloudUriForStorage] writes
     * (it decodes via Uri.pathSegments). The decoded form is re-encoded by the
     * proxy so the rebuilt proxy URL always matches the single-segment Ktor
     * route — without it, restored sessions answered 404 and the previously
     * playing song could not be replayed after an app restart.
     */
    private suspend fun resolveSoundCloudUriAsync(uriString: String): Uri? {
        Timber.tag("DualPlayerEngine").d("Async resolving SoundCloud URI: $uriString")

        if (!soundCloudStreamProxy.isReady()) {
            Timber.tag("DualPlayerEngine").w("SoundCloudStreamProxy not ready, awaiting...")
            val proxyReady = soundCloudStreamProxy.awaitReady(5_000L)
            if (!proxyReady) {
                Timber.tag("DualPlayerEngine").e("SoundCloudStreamProxy not ready after timeout")
                return null
            }
        }

        val proxyUrl = soundCloudStreamProxy.resolveSoundCloudUri(uriString)
        if (!proxyUrl.isNullOrBlank()) {
            return Uri.parse(proxyUrl)
        }

        Timber.tag("DualPlayerEngine").w("Failed to resolve SoundCloud URI: $uriString")
        return null
    }

    /**
     * Resolves a MediaItem's cloud URI (if any) and returns a copy with the resolved URI.
     * For non-cloud URIs, returns the original MediaItem unchanged.
     */
    suspend fun resolveMediaItem(mediaItem: MediaItem): MediaItem {
        val uri = mediaItem.localConfiguration?.uri ?: return mediaItem
        val scheme = uri.scheme
        if (scheme != "telegram" && scheme != "netease" &&
            scheme != "youtube" && scheme != "soundcloud"
        ) return mediaItem

        val resolvedUri = resolveCloudUri(uri)
        if (resolvedUri == uri) return mediaItem // Resolution failed or not needed

        // Rebuild MediaItem with resolved URI, preserving metadata
        return mediaItem.buildUpon()
            .setUri(resolvedUri)
            .build()
    }

    /**
     * Prepares the auxiliary player (Player B) with the next media item.
     * Cloud URIs are resolved asynchronously before passing to ExoPlayer.
     */
    suspend fun prepareNext(mediaItem: MediaItem, startPositionMs: Long = 0L) {
        try {
            Timber.tag("TransitionDebug").d("Engine: prepareNext called for %s", mediaItem.mediaId)

            // Adaptive buffering: tune the auxiliary player's profile to the
            // incoming source type. Classified from the ORIGINAL scheme
            // (before proxy resolution rewrites the URI), so telegram/netease
            // land on the cloud-drive profile even when resolution happens to
            // return a local file for an already-downloaded track.
            auxiliaryLoadControl.select(
                SourceTunedLoadControl.kindForUriScheme(
                    mediaItem.localConfiguration?.uri?.scheme
                )
            )

            // Pre-resolve cloud URI on the coroutine (non-blocking for ExoPlayer)
            val resolvedItem = resolveMediaItem(mediaItem)

            playerB.stop()
            playerB.clearMediaItems()
            playerB.playWhenReady = false
            playerB.setMediaItem(resolvedItem)
            
            // Set appropriate WakeMode for the next item
            val scheme = mediaItem.localConfiguration?.uri?.scheme
            if (scheme == "telegram" || scheme == "http" || scheme == "https") {
                 playerB.setWakeMode(C.WAKE_MODE_LOCAL)
            } else {
                 playerB.setWakeMode(C.WAKE_MODE_LOCAL)
            }
            
            playerB.prepare()
            playerB.volume = 0f // Start silent
            if (startPositionMs > 0) {
                playerB.seekTo(startPositionMs)
            } else {
                playerB.seekTo(0)
            }
            // Critical: leave B paused so it can start instantly when asked
            playerB.pause()
            Timber.tag("TransitionDebug").d("Engine: Player B prepared, paused, volume=0f")
        } catch (e: Exception) {
            Timber.tag("TransitionDebug").e(e, "Failed to prepare next player")
        }
    }

    /**
     * If a track was pre-buffered in Player B, this cancels it.
     */
    fun cancelNext() {
        transitionJob?.cancel()
        transitionRunning = false
        if (playerB.mediaItemCount > 0) {
            Timber.tag("TransitionDebug").d("Engine: Cancelling next player")
            playerB.stop()
            playerB.clearMediaItems()
        }
        // Ensure master player is at the user's selected volume if we cancel
        // and reset the transition machinery (was a hardcoded 1f reset —
        // the direct cause of the volume slider snapping back to 100% when a
        // song was tapped while a pre-buffered next track was pending).
        playerA.volume = userVolume
        setPauseAtEndOfMediaItems(false)
    }

    /**
     * Executes a transition based on the provided settings.
     */
    fun performTransition(settings: TransitionSettings) {
        transitionJob?.cancel()
        transitionRunning = true
        transitionJob = scope.launch {
            try {
                // Force Overlap for now as per instructions
                performOverlapTransition(settings)
            } catch (e: Exception) {
                Timber.tag("TransitionDebug").e(e, "Error performing transition")
                // Fallback: Restore the user's volume and reset logic
                playerA.volume = userVolume
                setPauseAtEndOfMediaItems(false)
                playerB.stop()
            } finally {
                transitionRunning = false
            }
        }
    }

    private suspend fun performOverlapTransition(settings: TransitionSettings) {
        Timber.tag("TransitionDebug").d("Starting Overlap/Crossfade. Duration: %d ms", settings.durationMs)

        if (playerB.mediaItemCount == 0) {
            Timber.tag("TransitionDebug").w("Skipping overlap - next player not prepared (count=0)")
            playerA.volume = userVolume
            setPauseAtEndOfMediaItems(false)
            return
        }

        // Ensure B is fully buffered and paused at the starting position
        if (playerB.playbackState == Player.STATE_IDLE) {
            Timber.tag("TransitionDebug").d("Player B idle. Preparing now.")
            playerB.prepare()
        }

        // Wait until READY using a listener instead of polling to save CPU
        if (playerB.playbackState == Player.STATE_BUFFERING) {
            val ready = awaitPlayerReady(playerB, timeoutMs = 3000L)
            if (!ready) {
                Timber.tag("TransitionDebug").w("Player B not ready for overlap. State=%d", playerB.playbackState)
                playerA.volume = userVolume
                setPauseAtEndOfMediaItems(false)
                return
            }
        } else if (playerB.playbackState != Player.STATE_READY) {
            Timber.tag("TransitionDebug").w("Player B not ready for overlap. State=%d", playerB.playbackState)
            playerA.volume = userVolume
            setPauseAtEndOfMediaItems(false)
            return
        }

        // 1. Start Player B (Next Song) paused with volume=0 then immediately request play so overlap is audible
        // NOTE: playerA is currently playing "Old Song". playerB is "Next Song".
        // FIX(volume-reset): the outgoing track continues at the USER's volume
        // (was hardcoded 1f — audibly blasting back to 100% at fade start).
        playerB.volume = 0f
        playerA.volume = userVolume
        if (!playerA.isPlaying && playerA.playbackState == Player.STATE_READY) {
            // Ensure the outgoing track keeps rendering during the crossfade window
            playerA.play()
        }

        // Make sure PlayWhenReady is honored even if we had paused earlier
        playerB.playWhenReady = true
        playerB.play()

        Timber.tag("TransitionDebug").d("Player B started for overlap. Playing=%s state=%d", playerB.isPlaying, playerB.playbackState)

        // Ensure Player B is actually outputting audio before we begin the fade
        if (!playerB.isPlaying) {
            val playing = awaitPlayerPlaying(playerB, timeoutMs = 2000L)
            if (!playing) {
                Timber.tag("TransitionDebug").e("Player B failed to start in time. Aborting crossfade.")
                playerA.volume = userVolume
                setPauseAtEndOfMediaItems(false)
                return
            }
        }

        // Small warmup to guarantee audible overlap
        delay(75)

        // --- SWAP PLAYERS EARLY (Before Fade) ---
        // This ensures the UI updates to show the "Next Song" immediately when the transition starts.

        // 1. Identify Outgoing (Old A) and Incoming (Old B / New A)
        val outgoingPlayer = playerA
        val incomingPlayer = playerB

        val isSelfTransition = outgoingPlayer.currentMediaItem?.mediaId == incomingPlayer.currentMediaItem?.mediaId

        val currentOutgoingIndex = outgoingPlayer.currentMediaItemIndex

        // History: All songs up to and including the current one (Old Song)
        val historyToTransfer = mutableListOf<MediaItem>()
        val historyEndIndex = if (isSelfTransition) currentOutgoingIndex else currentOutgoingIndex + 1
        for (i in 0 until historyEndIndex) {
            historyToTransfer.add(outgoingPlayer.getMediaItemAt(i))
        }

        // Future: Songs AFTER the Next Song
        // We skip the immediate next one because incomingPlayer already has it.
        val futureToTransfer = mutableListOf<MediaItem>()
        val futureStartIndex = if (isSelfTransition) currentOutgoingIndex + 1 else currentOutgoingIndex + 2
        for (i in futureStartIndex until outgoingPlayer.mediaItemCount) {
            futureToTransfer.add(outgoingPlayer.getMediaItemAt(i))
        }

        // 2. Transfer playback settings (repeat mode, shuffle mode) before swap
        val repeatModeToTransfer = outgoingPlayer.repeatMode
        val shuffleModeToTransfer = outgoingPlayer.shuffleModeEnabled
        incomingPlayer.repeatMode = repeatModeToTransfer
        incomingPlayer.shuffleModeEnabled = shuffleModeToTransfer
        Timber.tag("TransitionDebug").d("Transferred playback settings: repeatMode=%d, shuffle=%s", repeatModeToTransfer, shuffleModeToTransfer)

        // 3. Move manual focus management to the new master player
        outgoingPlayer.removeListener(masterPlayerListener)

        // 4. Swap References (load controls swap WITH their players so each
        // control keeps answering for the player it was built with)
        playerA = incomingPlayer
        playerB = outgoingPlayer
        val swappedMasterControl = auxiliaryLoadControl
        auxiliaryLoadControl = masterLoadControl
        masterLoadControl = swappedMasterControl
        
        // Critical: Reset pauseAtEndOfMediaItems on both players after swap.
        // The outgoing player (now B) had pauseAtEndOfMediaItems=true set before the transition started.
        // If we don't disable it, the outgoing player will pause itself when it reaches the end,
        // causing the "stops then restarts" glitch during crossfade.
        playerB.pauseAtEndOfMediaItems = false
        playerA.pauseAtEndOfMediaItems = false

        playerA.addListener(masterPlayerListener)
        // Ensure we hold focus for the new master
        if (playerA.playWhenReady) {
             requestAudioFocus()
        }

        // 4. Transfer History to New A (Prepend)
        if (historyToTransfer.isNotEmpty()) {
             playerA.addMediaItems(0, historyToTransfer)
             Timber.tag("TransitionDebug").d("Transferred %d history items to new player.", historyToTransfer.size)
        }

        // 5. Transfer Future to New A (Append)
        if (futureToTransfer.isNotEmpty()) {
             playerA.addMediaItems(futureToTransfer)
             Timber.tag("TransitionDebug").d("Transferred %d future items to new player.", futureToTransfer.size)
        }

        // 6. Notify Service to update MediaSession
        onPlayerSwappedListeners.forEach { it(playerA) }
        
        // Update Session ID for Equalizer
        _activeAudioSessionId.value = playerA.audioSessionId
        
        Timber.tag("TransitionDebug").d("Players swapped EARLY. UI should now show next song.")

        // *** FADE LOOP ***
        // playerA is now the Incoming/New Master.
        // playerB is now the Outgoing/Aux.

        val duration = settings.durationMs.toLong().coerceAtLeast(500L)
        val stepMs = 16L
        var elapsed = 0L
        var lastLog = 0L

        while (elapsed <= duration) {
            val progress = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
            // FIX(volume-reset): fade curves are scaled by the USER's selected
            // volume so a crossfade fades between userVolume*envelope values
            // instead of 0..1 — the fade used to end at exactly 1f, resetting
            // the audible volume (and the UI slider) to 100% on every
            // auto-advance.
            val volIn = envelope(progress, settings.curveIn) * userVolume  // Incoming (Now A)
            val volOut = (1f - envelope(progress, settings.curveOut)) * userVolume // Outgoing (Now B)

            playerA.volume = volIn.coerceIn(0f, 1f)
            playerB.volume = volOut.coerceIn(0f, 1f)

            if (elapsed - lastLog >= 250) {
                Timber.tag("TransitionDebug").v("Loop: Progress=%.2f, VolNew=%.2f (Act: %.2f), VolOld=%.2f (Act: %.2f)",
                    progress, volIn, playerA.volume, volOut, playerB.volume)
                lastLog = elapsed
            }

            // Break early if either player stops in a non-ready state to avoid stuck fades.
            if (playerA.playbackState == Player.STATE_ENDED || playerB.playbackState == Player.STATE_ENDED) {
                Timber.tag("TransitionDebug").w("One of the players ended during crossfade (A=%d, B=%d)", playerA.playbackState, playerB.playbackState)
                break
            }

            delay(stepMs)
            elapsed += stepMs
        }

        Timber.tag("TransitionDebug").d("Overlap loop finished.")
        playerB.volume = 0f
        // FIX(volume-reset): settle the new master at the user's volume, not 1f.
        playerA.volume = userVolume

        // Clean up Old Player (now B)
        playerB.pause()
        playerB.stop()
        playerB.clearMediaItems()

        // Fresh Player Strategy: Release and recreate playerB to avoid OEM "stale session" tracking
        playerB.release()
        // Recreated with the auxiliary control that now belongs to playerB
        // (the controls swapped together with the players above); the next
        // prepareNext() retunes its profile to the upcoming source anyway.
        playerB = buildPlayer(handleAudioFocus = false, loadControl = auxiliaryLoadControl)
        Timber.tag("TransitionDebug").d("Old Player (B) released and recreated fresh.")

        // Ensure New Player (A) is fully active and unrestricted
        setPauseAtEndOfMediaItems(false)
    }

    /**
     * Suspends until the player reaches STATE_READY, or until [timeoutMs] elapses.
     * Uses a Player.Listener callback instead of polling to avoid CPU burn.
     */
    private suspend fun awaitPlayerReady(player: ExoPlayer, timeoutMs: Long): Boolean {
        // Fast path: already ready
        if (player.playbackState == Player.STATE_READY) return true
        if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) return false

        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val listener = object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState != Player.STATE_BUFFERING) {
                            player.removeListener(this)
                            if (cont.isActive) cont.resume(playbackState == Player.STATE_READY)
                        }
                    }
                }
                player.addListener(listener)
                cont.invokeOnCancellation { player.removeListener(listener) }
                // Re-check after attaching listener to avoid race
                if (player.playbackState != Player.STATE_BUFFERING) {
                    player.removeListener(listener)
                    if (cont.isActive) cont.resume(player.playbackState == Player.STATE_READY)
                }
            }
        } ?: false
    }

    /**
     * Suspends until the player reports isPlaying == true, or until [timeoutMs] elapses.
     * Uses a Player.Listener callback instead of polling to avoid CPU burn.
     */
    private suspend fun awaitPlayerPlaying(player: ExoPlayer, timeoutMs: Long): Boolean {
        if (player.isPlaying) return true

        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val listener = object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) {
                            player.removeListener(this)
                            if (cont.isActive) cont.resume(true)
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        // If player reaches ENDED or IDLE, it will never start playing
                        if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                            player.removeListener(this)
                            if (cont.isActive) cont.resume(false)
                        }
                    }
                }
                player.addListener(listener)
                cont.invokeOnCancellation { player.removeListener(listener) }
                // Re-check after attaching listener to avoid race
                if (player.isPlaying) {
                    player.removeListener(listener)
                    if (cont.isActive) cont.resume(true)
                }
            }
        } ?: false
    }

    /**
     * Cleans up resources when the engine is no longer needed.
     */
    fun release() {
        transitionJob?.cancel()
        abandonAudioFocus()
        if (::playerA.isInitialized) {
            playerA.removeListener(masterPlayerListener)
            playerA.release()
        }
        if (::playerB.isInitialized) playerB.release()
        isReleased = true
    }
}
