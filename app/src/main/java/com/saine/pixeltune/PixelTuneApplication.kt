package com.saine.pixeltune

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentCallbacks2
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.saine.pixeltune.utils.CrashHandler
import com.saine.pixeltune.utils.MediaMetadataRetrieverPool
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import org.schabi.newpipe.extractor.NewPipe
import com.saine.pixeltune.data.youtube.NewPipeDownloader
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject

@HiltAndroidApp
class PixelTuneApplication : Application(), ImageLoaderFactory, Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var imageLoader: dagger.Lazy<ImageLoader>

    @Inject
    lateinit var youtubeStreamProxy: com.saine.pixeltune.data.youtube.YouTubeStreamProxy

    @Inject
    lateinit var soundCloudStreamProxy: com.saine.pixeltune.data.soundcloud.SoundCloudStreamProxy

    @Inject
    lateinit var okHttpClient: OkHttpClient

    @Inject
    lateinit var userPreferencesRepository: com.saine.pixeltune.data.preferences.UserPreferencesRepository

    // FIX(streaming-performance): dedicated client for ALL NewPipe extractor
    // requests. The app-wide default client logs every response BODY in debug
    // builds (and the CI ships debug APKs) — piping multi-MB extractor pages
    // through logcat made every search/playback/artwork fetch take seconds.
    // See @NewPipeOkHttpClient in di/ for the full rationale.
    @Inject
    @com.saine.pixeltune.di.NewPipeOkHttpClient
    lateinit var newPipeOkHttpClient: OkHttpClient

    // AÑADE EL COMPANION OBJECT
    companion object {
        const val NOTIFICATION_CHANNEL_ID = "PixelTune_music_channel"
    }

    override fun onCreate() {
        super.onCreate()

        // Benchmark variant intentionally restarts/kills app process during tests.
        // Avoid persisting those events as user-facing crash reports.
        if (BuildConfig.BUILD_TYPE != "benchmark") {
            CrashHandler.install(this)
        }

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            // Release tree: only WARN/ERROR/WTF - no DEBUG/VERBOSE/INFO
            Timber.plant(ReleaseTree())
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "PixelTune Music Playback",
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
        
        // Initialize NewPipe Extractor
        // FIX(streaming-performance): run NewPipe on the dedicated non-BODY-logging
        // client — see @NewPipeOkHttpClient. (The app-wide default client is still
        // used everywhere else: Retrofit APIs, lyrics, Deezer, …)
        NewPipe.init(NewPipeDownloader(newPipeOkHttpClient))

        // Start proxies
        youtubeStreamProxy.start()
        soundCloudStreamProxy.start()

        // One-time cleanup: drop playlists owned by removed cloud providers
        // (Telegram / Netease / GDrive) and their stale preferences.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                userPreferencesRepository.purgeRemovedProviderPlaylists()
            } catch (e: Exception) {
                Timber.e(e, "Failed to purge removed-provider playlists")
            }
        }
    }

    override fun newImageLoader(): ImageLoader {
        return imageLoader.get()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            MediaMetadataRetrieverPool.clear()
        }
    }

    // 3. Sobrescribe el método para proveer la configuración de WorkManager
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

}
