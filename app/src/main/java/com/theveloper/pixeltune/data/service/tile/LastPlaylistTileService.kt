package com.theveloper.pixeltune.data.service.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.theveloper.pixeltune.MainActivity
import com.theveloper.pixeltune.data.preferences.UserPreferencesRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Quick Settings tile that resumes the most recently played playlist.
 * Reads the last playlist ID from DataStore and fires ACTION_OPEN_PLAYLIST to MainActivity.
 * Works whether the app is open or not.
 */
@RequiresApi(Build.VERSION_CODES.N)
class LastPlaylistTileService : TileService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface LastPlaylistTileEntryPoint {
        fun userPreferencesRepository(): UserPreferencesRepository
    }

    // PERF(anr): TileService callbacks (onStartListening / onClick) run on the
    // MAIN thread inside a tight system ANR window. They used runBlocking to
    // read DataStore — a synchronous disk read (and on first read, DataStore
    // initialization) on main. The reads now run on a background dispatcher and
    // the tile state / click handling are applied asynchronously.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val prefsRepo: UserPreferencesRepository by lazy {
        val appContext = applicationContext
        val entryPoint = EntryPointAccessors.fromApplication(
            appContext,
            LastPlaylistTileEntryPoint::class.java
        )
        entryPoint.userPreferencesRepository()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onStartListening() {
        serviceScope.launch {
            val lastPlaylistId = withContext(Dispatchers.IO) {
                prefsRepo.lastPlaylistIdFlow.first()
            }
            qsTile?.apply {
                state = if (lastPlaylistId != null) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
                updateTile()
            }
        }
    }

    override fun onClick() {
        serviceScope.launch {
            val playlistId = withContext(Dispatchers.IO) {
                prefsRepo.lastPlaylistIdFlow.first()
            }
            if (playlistId == null) return@launch

            val intent = Intent(this@LastPlaylistTileService, MainActivity::class.java).apply {
                action = MainActivity.ACTION_OPEN_PLAYLIST
                putExtra(MainActivity.EXTRA_PLAYLIST_ID, playlistId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                this@LastPlaylistTileService,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        }
    }
}
