package com.theveloper.pixeltune.di

import android.content.Context
import androidx.annotation.OptIn
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.theveloper.pixeltune.BuildConfig
import com.theveloper.pixeltune.PixelTuneApplication
import com.theveloper.pixeltune.data.database.AlbumArtThemeDao
import com.theveloper.pixeltune.data.database.EngagementDao
import com.theveloper.pixeltune.data.database.FavoritesDao
import com.theveloper.pixeltune.data.database.GDriveDao
import com.theveloper.pixeltune.data.database.LyricsDao
import com.theveloper.pixeltune.data.database.MusicDao
import com.theveloper.pixeltune.data.database.PixelTuneDatabase
import com.theveloper.pixeltune.data.database.SearchHistoryDao
import com.theveloper.pixeltune.data.database.TransitionDao
import com.theveloper.pixeltune.data.preferences.UserPreferencesRepository
import com.theveloper.pixeltune.data.preferences.dataStore
import com.theveloper.pixeltune.data.media.SongMetadataEditor
import com.theveloper.pixeltune.data.network.PreferIpv4Dns
import com.theveloper.pixeltune.data.network.deezer.DeezerApiService
import com.theveloper.pixeltune.data.network.netease.NeteaseApiService
import com.theveloper.pixeltune.data.network.lyrics.LrcLibApiService
import com.theveloper.pixeltune.data.repository.ArtistImageRepository
import com.theveloper.pixeltune.data.repository.LyricsRepository
import com.theveloper.pixeltune.data.repository.LyricsRepositoryImpl
import com.theveloper.pixeltune.data.repository.MediaStoreSongRepository
import com.theveloper.pixeltune.data.repository.MusicRepository
import com.theveloper.pixeltune.data.repository.MusicRepositoryImpl
import com.theveloper.pixeltune.data.repository.SongRepository
import com.theveloper.pixeltune.data.repository.TransitionRepository
import com.theveloper.pixeltune.data.repository.TransitionRepositoryImpl
import com.theveloper.pixeltune.data.repository.FolderTreeBuilder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory


@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Singleton
    @Provides
    fun provideApplication(@ApplicationContext app: Context): PixelTuneApplication {
        return app as PixelTuneApplication
    }

    @Singleton
    @Provides
    fun provideGson(): com.google.gson.Gson {
        return com.google.gson.Gson()
    }

    @OptIn(UnstableApi::class)
    @Singleton
    @Provides
    fun provideSessionToken(@ApplicationContext context: Context): androidx.media3.session.SessionToken {
        return androidx.media3.session.SessionToken(
            context,
            android.content.ComponentName(context, com.theveloper.pixeltune.data.service.MusicService::class.java)
        )
    }

    @Provides
    @Singleton
    fun providePreferencesDataStore(
        @ApplicationContext context: Context
    ): DataStore<Preferences> = context.dataStore

    @Singleton
    @Provides
    fun provideJson(): Json { // Proveer Json
        return Json {
            isLenient = true
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
    }

    @Singleton
    @Provides
    fun providePixelTuneDatabase(@ApplicationContext context: Context): PixelTuneDatabase {
        return Room.databaseBuilder(
            context.applicationContext,
            PixelTuneDatabase::class.java,
            "PixelTune_database"
        ).addMigrations(
            PixelTuneDatabase.MIGRATION_3_4,
            PixelTuneDatabase.MIGRATION_4_5,
            PixelTuneDatabase.MIGRATION_5_6,
            PixelTuneDatabase.MIGRATION_6_7,
            PixelTuneDatabase.MIGRATION_7_8,
            PixelTuneDatabase.MIGRATION_8_9,
            PixelTuneDatabase.MIGRATION_9_10,
            PixelTuneDatabase.MIGRATION_10_11,
            PixelTuneDatabase.MIGRATION_11_12,
            PixelTuneDatabase.MIGRATION_12_13,
            PixelTuneDatabase.MIGRATION_13_14,
            PixelTuneDatabase.MIGRATION_14_15,
            PixelTuneDatabase.MIGRATION_15_16,
            PixelTuneDatabase.MIGRATION_16_17,
            PixelTuneDatabase.MIGRATION_17_18,
            PixelTuneDatabase.MIGRATION_18_19,
            PixelTuneDatabase.MIGRATION_19_20,
            PixelTuneDatabase.MIGRATION_20_21,
            PixelTuneDatabase.MIGRATION_21_22,
            PixelTuneDatabase.MIGRATION_22_23,
            PixelTuneDatabase.MIGRATION_23_24
        )
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    @Singleton
    @Provides
    fun provideAlbumArtThemeDao(database: PixelTuneDatabase): AlbumArtThemeDao {
        return database.albumArtThemeDao()
    }

    @Singleton
    @Provides
    fun provideSearchHistoryDao(database: PixelTuneDatabase): SearchHistoryDao { // NUEVO MÉTODO
        return database.searchHistoryDao()
    }

    @Singleton
    @Provides
    fun provideMusicDao(database: PixelTuneDatabase): MusicDao { // Proveer MusicDao
        return database.musicDao()
    }

    @Singleton
    @Provides
    fun provideTransitionDao(database: PixelTuneDatabase): TransitionDao {
        return database.transitionDao()
    }

    @Singleton
    @Provides
    fun provideEngagementDao(database: PixelTuneDatabase): EngagementDao {
        return database.engagementDao()
    }

    @Singleton
    @Provides
    fun provideFavoritesDao(database: PixelTuneDatabase): FavoritesDao {
        return database.favoritesDao()
    }

    @Singleton
    @Provides
    fun provideLyricsDao(database: PixelTuneDatabase): LyricsDao {
        return database.lyricsDao()
    }

    @Singleton
    @Provides
    fun provideGDriveDao(database: PixelTuneDatabase): GDriveDao {
        return database.gdriveDao()
    }

    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context
    ): ImageLoader {
        return ImageLoader.Builder(context)
            // FIX(cloud-streaming-speed): dedicated network client for album
            // artwork (ytimg / sndcdn / deezer CDN images).
            //
            // Previously Coil silently built its OWN default OkHttpClient,
            // which — like every OkHttp client — tries DNS routes in OS order
            // (IPv6 first on dual-stack carriers). On carriers with a broken
            // IPv6 route to the CDN, every artwork request burned its whole
            // 10 s connect timeout on the blackholed AAAA route before
            // falling back to IPv4: album art loaded with multi-second delays
            // or timed out entirely ("sometimes it doesn't even show up").
            //
            // Sharing the IPv4-first resolver (see [PreferIpv4Dns]) makes the
            // first connection attempt the working one. No logging
            // interceptor here: image bytes never belong in logcat.
            .callFactory(
                OkHttpClient.Builder()
                    .dns(PreferIpv4Dns)
                    .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
            )
            .dispatcher(Dispatchers.Default) // Use CPU-bound dispatcher for decoding
            .allowHardware(true) // Re-enable hardware bitmaps for better performance
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(0.20) // Use 20% of app memory for image cache
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(100L * 1024 * 1024) // 100 MB disk cache
                    .build()
            }
            .respectCacheHeaders(false) // Ignore server cache headers, always cache
            .build()
    }

    @Provides
    @Singleton
    fun provideLyricsRepository(
        @ApplicationContext context: Context,
        lrcLibApiService: LrcLibApiService,
        lyricsDao: LyricsDao,
        okHttpClient: OkHttpClient
    ): LyricsRepository {
        return LyricsRepositoryImpl(
            context = context,
            lrcLibApiService = lrcLibApiService,
            lyricsDao = lyricsDao,
            okHttpClient = okHttpClient
        )
    }

    @Provides
    @Singleton
    fun provideSongRepository(
        @ApplicationContext context: Context,
        mediaStoreObserver: com.theveloper.pixeltune.data.observer.MediaStoreObserver,
        favoritesDao: FavoritesDao,
        userPreferencesRepository: UserPreferencesRepository,
        musicDao: MusicDao
    ): SongRepository {
        return MediaStoreSongRepository(
            context = context,
            mediaStoreObserver = mediaStoreObserver,
            favoritesDao = favoritesDao,
            userPreferencesRepository = userPreferencesRepository,
            musicDao = musicDao
        )
    }

    @Singleton
    @Provides
    fun provideTelegramDao(database: PixelTuneDatabase): com.theveloper.pixeltune.data.database.TelegramDao {
        return database.telegramDao()
    }

    @Singleton
    @Provides
    fun provideNeteaseDao(database: PixelTuneDatabase): com.theveloper.pixeltune.data.database.NeteaseDao {
        return database.neteaseDao()
    }

    @Provides
    @Singleton
    fun provideFolderTreeBuilder(): FolderTreeBuilder {
        return FolderTreeBuilder()
    }

    @Provides
    @Singleton
    fun provideMusicRepository(
        @ApplicationContext context: Context,
        userPreferencesRepository: UserPreferencesRepository,
        searchHistoryDao: SearchHistoryDao,
        musicDao: MusicDao,
        lyricsRepository: LyricsRepository,
        telegramDao: com.theveloper.pixeltune.data.database.TelegramDao,
        telegramCacheManager: com.theveloper.pixeltune.data.telegram.TelegramCacheManager,
        telegramRepository: com.theveloper.pixeltune.data.telegram.TelegramRepository,
        songRepository: SongRepository,
        favoritesDao: FavoritesDao,
        artistImageRepository: ArtistImageRepository,
        folderTreeBuilder: FolderTreeBuilder
    ): MusicRepository {
        return MusicRepositoryImpl(
            context = context,
            userPreferencesRepository = userPreferencesRepository,
            searchHistoryDao = searchHistoryDao,
            musicDao = musicDao,
            lyricsRepository = lyricsRepository,
            telegramDao = telegramDao,
            telegramCacheManager = telegramCacheManager,
            telegramRepository = telegramRepository,
            songRepository = songRepository,
            favoritesDao = favoritesDao,
            artistImageRepository = artistImageRepository,
            folderTreeBuilder = folderTreeBuilder
        )

    }

    @Provides
    @Singleton
    fun provideTransitionRepository(
        transitionRepositoryImpl: TransitionRepositoryImpl
    ): TransitionRepository {
        return transitionRepositoryImpl
    }

    @Singleton
    @Provides
    fun provideSongMetadataEditor(
        @ApplicationContext context: Context,
        musicDao: MusicDao,
        telegramDao: com.theveloper.pixeltune.data.database.TelegramDao
    ): SongMetadataEditor {
        return SongMetadataEditor(context, musicDao, telegramDao)
    }

        /**
     * Dedicated OkHttpClient for NewPipe extractor requests (YouTube / YouTube
     * Music / SoundCloud) — see the @NewPipeOkHttpClient qualifier for the full
     * rationale.
     *
     * Key differences from the app-wide default client:
     *  - Logging is BASIC in debug (request line + headers only) and NONE in
     *    release. The default client's Level.BODY logging in debug builds piped
     *    every extractor response — including multi-megabyte sw.js / base.js and
     *    the 4-5 sequential /player JSON responses per playback — through
     *    logcat, adding seconds of I/O + GC to every search and playback start.
     *  - 30s read timeout: NewPipe pages can be several MB on slow networks;
     *    the default 8s occasionally tripped mid-download.
     */
    @Provides
    @Singleton
    @com.theveloper.pixeltune.di.NewPipeOkHttpClient
    fun provideNewPipeOkHttpClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        val connectionPool = okhttp3.ConnectionPool(
            maxIdleConnections = 8,
            keepAliveDuration = 60,
            timeUnit = java.util.concurrent.TimeUnit.SECONDS
        )

        return OkHttpClient.Builder()
            .connectionPool(connectionPool)
            // FIX(cloud-streaming-speed): IPv4-first route ordering. On
            // dual-stack carriers with a broken IPv6 route, OkHttp's first
            // connect attempt (AAAA, tried first in OS resolver order) hangs
            // for the FULL connect timeout before falling back to IPv4 —
            // which matched the reported "more than 15 seconds" search /
            // playback-start latency exactly. See [PreferIpv4Dns] for the
            // full analysis and the IPv6-only-network safety argument.
            .dns(PreferIpv4Dns)
            // FIX(cloud-streaming-speed): 6s connect timeout (was 15s).
            // connectTimeout is the per-route budget OkHttp spends before
            // trying the next route, so it directly bounds the worst-case
            // penalty of a dead route. Healthy TCP+TLS handshakes complete
            // in well under 2s even on slow mobile links; 6s only ever gets
            // spent on broken routes, where failing over faster is strictly
            // better than hanging.
            .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            // 30s read timeout kept: NewPipe pages can be several MB
            // (sw.js, watch/search HTML) on genuinely slow networks.
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(loggingInterceptor)
            .build()
    }

    /**
     * Provee una instancia singleton de OkHttpClient con logging e interceptor de User-Agent.
     * Retry logic with backoff is handled in coroutine-based callers.
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor()
        loggingInterceptor.setLevel(
            if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        )
        
        // Connection pool with optimized connections for better performance
        val connectionPool = okhttp3.ConnectionPool(
            maxIdleConnections = 5,
            keepAliveDuration = 30,
            timeUnit = java.util.concurrent.TimeUnit.SECONDS
        )
        
        return OkHttpClient.Builder()
            .connectionPool(connectionPool)
            // FIX(cloud-streaming-speed): IPv4-first route ordering for the
            // app-wide client too (Retrofit APIs: lyrics, Deezer, Netease,
            // GDrive) — same rationale as the NewPipe / streaming / artwork
            // clients; see [PreferIpv4Dns].
            .dns(PreferIpv4Dns)
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // Add User-Agent header (required by some APIs).
            //
            // IMPORTANT: Only set the app User-Agent when the request does NOT already
            // have one. NewPipeDownloader explicitly sets a browser User-Agent on every
            // request it issues (for YouTube / SoundCloud scraping), and YouTube will
            // serve a "page needs to be reloaded" bot-check page if it sees a non-browser
            // UA. Using `.header()` unconditionally here would override that browser UA
            // and break YouTube playback (player stuck at 00:00).
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val requestWithUserAgent = if (originalRequest.header("User-Agent") == null) {
                    originalRequest.newBuilder()
                        .header("User-Agent", "PixelTune/1.0 (Android; Music Player)")
                        .build()
                } else {
                    originalRequest
                }
                chain.proceed(requestWithUserAgent)
            }
            .addInterceptor(loggingInterceptor)
            .build()
    }

    /**
     * Dedicated OkHttpClient for the cloud-streaming proxies
     * (YouTube / Netease / SoundCloud / GDrive).
     *
     * CRITICAL FIX for the "YouTube playback stuck at 00:00" bug:
     *
     * The default app-wide client uses readTimeout=8s. YouTube throttles audio
     * streams adaptively; reading a 5 MB body via OkHttp's `bytes()` (or any
     * per-chunk read) can stall for >8s between bytes, which throws
     * `SocketTimeoutException`. The proxy then has nothing to send to ExoPlayer,
     * ExoPlayer's own connect timeout fires (also 8s), and the progress bar
     * stays frozen at 00:00 with no error surfaced to the UI.
     *
     * This streaming client removes the per-read timeout entirely so that
     * throttled upstream reads don't abort the call. The proxies now also
     * stream bytes through `respondOutputStream` instead of buffering the
     * entire body — see [CloudStreamForwarder] for the streaming pipeline.
     */
    @Provides
    @Singleton
    @StreamingOkHttpClient
    fun provideStreamingOkHttpClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.HEADERS
                    else HttpLoggingInterceptor.Level.NONE
        }

        val connectionPool = okhttp3.ConnectionPool(
            maxIdleConnections = 8,
            keepAliveDuration = 60,
            timeUnit = java.util.concurrent.TimeUnit.SECONDS
        )

        return OkHttpClient.Builder()
            .connectionPool(connectionPool)
            // FIX(cloud-streaming-speed): IPv4-first route ordering — same
            // rationale as the NewPipe client above. This is the client the
            // stream proxies use for googlevideo / sndcdn media fetches;
            // EVERY seek opens a fresh upstream connection through it (the
            // previous connection is discarded when ExoPlayer tears down
            // the previous ranged read), so a blackholed IPv6 route added
            // the whole connect timeout to EVERY seek — the reported
            // "extremely long time to play from a forwarded position".
            .dns(PreferIpv4Dns)
            // 10s connect timeout (was 30s): bounds the dead-route penalty
            // while leaving generous headroom for slow TLS handshakes.
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            // 0 = no read timeout. REQUIRED for YouTube's adaptive throttling:
            // the gap between upstream chunks can exceed the app's default 8s.
            .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            // 0 = no overall call timeout. The call ends when the upstream body
            // is fully consumed (or the client disconnects).
            .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // The streaming proxies set their own browser User-Agent per-source
            // (YouTube needs a browser UA to bypass bot checks). This interceptor
            // only injects a default UA when none is present, so per-call UAs win.
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val requestWithUserAgent = if (originalRequest.header("User-Agent") == null) {
                    originalRequest.newBuilder()
                        .header("User-Agent", "PixelTune/1.0 (Android; Music Player)")
                        .build()
                } else {
                    originalRequest
                }
                chain.proceed(requestWithUserAgent)
            }
            .addInterceptor(loggingInterceptor)
            .build()
    }

    /**
     * Provee una instancia de OkHttpClient con timeouts para búsquedas de lyrics.
     * Includes DNS resolver, modern TLS, connection pool, and connection retry.
     */
    @Provides
    @Singleton
    @FastOkHttpClient
    fun provideFastOkHttpClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor()
        loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.HEADERS)
        
        // Connection pool to reuse connections for better performance
        val connectionPool = okhttp3.ConnectionPool(
            maxIdleConnections = 5,
            keepAliveDuration = 30,
            timeUnit = java.util.concurrent.TimeUnit.SECONDS
        )
        
        // Use Cloudflare and Google DNS to avoid potential DNS issues
        val dns = okhttp3.Dns { hostname ->
            try {
                // First try system DNS
                okhttp3.Dns.SYSTEM.lookup(hostname)
            } catch (e: Exception) {
                // Fallback to manual resolution if system DNS fails
                java.net.InetAddress.getAllByName(hostname).toList()
            }
        }

        return OkHttpClient.Builder()
            .dns(dns)
            .connectionPool(connectionPool)
            // Use HTTP/1.1 to avoid HTTP/2 stream issues with some servers
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            // Use modern TLS connection spec
            .connectionSpecs(listOf(
                okhttp3.ConnectionSpec.MODERN_TLS,
                okhttp3.ConnectionSpec.COMPATIBLE_TLS,
                okhttp3.ConnectionSpec.CLEARTEXT
            ))
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            // Enable built-in retry on connection failure
            .retryOnConnectionFailure(true)
            // Add headers
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val requestWithHeaders = originalRequest.newBuilder()
                    .header("User-Agent", "PixelTune/1.0 (Android; Music Player)")
                    .header("Accept", "application/json")
                    .build()
                chain.proceed(requestWithHeaders)
            }
            .addInterceptor(loggingInterceptor)
            .build()
    }

    /**
     * Provee una instancia singleton de Retrofit para la API de LRCLIB.
     */
    @Provides
    @Singleton
    fun provideRetrofit(@FastOkHttpClient okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://lrclib.net/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    /**
     * Provee una instancia singleton del servicio de la API de LRCLIB.
     */
    @Provides
    @Singleton
    fun provideLrcLibApiService(retrofit: Retrofit): LrcLibApiService {
        return retrofit.create(LrcLibApiService::class.java)
    }

    /**
     * Provee una instancia de Retrofit para la API de Deezer.
     */
    @Provides
    @Singleton
    @DeezerRetrofit
    fun provideDeezerRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://api.deezer.com/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    /**
     * Provee el servicio de la API de Deezer.
     */
    @Provides
    @Singleton
    fun provideDeezerApiService(@DeezerRetrofit retrofit: Retrofit): DeezerApiService {
        return retrofit.create(DeezerApiService::class.java)
    }

    /**
     * Provee el repositorio de imágenes de artistas.
     */

    @Singleton
    @Provides
    fun provideYouTubeRepository(): com.theveloper.pixeltune.data.youtube.YouTubeRepository {
        return com.theveloper.pixeltune.data.youtube.YouTubeRepository()
    }

    @Singleton
    @Provides
    fun provideYouTubeStreamProxy(
        repository: com.theveloper.pixeltune.data.youtube.YouTubeRepository,
        @StreamingOkHttpClient okHttpClient: OkHttpClient,
        userPreferencesRepository: UserPreferencesRepository
    ): com.theveloper.pixeltune.data.youtube.YouTubeStreamProxy {
        return com.theveloper.pixeltune.data.youtube.YouTubeStreamProxy(repository, okHttpClient, userPreferencesRepository)
    }

    @Singleton
    @Provides
    fun provideSoundCloudRepository(): com.theveloper.pixeltune.data.soundcloud.SoundCloudRepository {
        return com.theveloper.pixeltune.data.soundcloud.SoundCloudRepository()
    }

    @Singleton
    @Provides
    fun provideSoundCloudStreamProxy(
        repository: com.theveloper.pixeltune.data.soundcloud.SoundCloudRepository,
        @StreamingOkHttpClient okHttpClient: OkHttpClient,
        userPreferencesRepository: UserPreferencesRepository
    ): com.theveloper.pixeltune.data.soundcloud.SoundCloudStreamProxy {
        return com.theveloper.pixeltune.data.soundcloud.SoundCloudStreamProxy(repository, okHttpClient, userPreferencesRepository)
    }

    @Provides
    @Singleton
    fun provideArtistImageRepository(
        deezerApiService: DeezerApiService,
        musicDao: MusicDao
    ): ArtistImageRepository {
        return ArtistImageRepository(deezerApiService, musicDao)
    }
}
