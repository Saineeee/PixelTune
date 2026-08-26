package com.theveloper.pixeltune.data.downloads

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.theveloper.pixeltune.MainActivity
import com.theveloper.pixeltune.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * IMPROVE(download-progress-notification): posts the real-time progress of an
 * in-flight offline download to the system notification panel.
 *
 * Design notes:
 *  - One notification per song (stable id derived from the song id hash), so
 *    parallel downloads each get their own live progress card.
 *  - `setOnlyAlertOnce(true)` + `setSilent(true)` on progress updates so a
 *    long download never buzzes/beeps on every 250 ms refresh; the card just
 *    silently updates its progress bar, like Netflix/YouTube Music.
 *  - Progress uses the M3-flavoured `NotificationCompat.Builder` progress API:
 *    determinate (0..100) when the total size is known, indeterminate
 *    otherwise.
 *  - Completion / failure cards are non-ongoing and auto-dismiss so the user
 *    is informed when something happens, exactly as requested.
 */
@Singleton
class DownloadNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val notificationManager: NotificationManager? =
        context.getSystemService(NotificationManager::class.java)

    init {
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                DOWNLOADS_CHANNEL_ID,
                context.getString(R.string.download_notifications_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.download_notifications_channel_description)
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun contentIntent(songId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            // Deep-link hint: open the Library DOWNLOADS chip when tapping the
            // notification. MainActivity reads the extra via intent?.extras.
            putExtra(EXTRA_OPEN_DOWNLOADS, songId)
        }
        return PendingIntent.getActivity(
            context,
            songId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Live progress card while a download is streaming. */
    fun notifyProgress(
        songId: String,
        title: String,
        progressPercent: Int,
        indeterminate: Boolean,
        bytesDownloaded: Long,
        totalBytes: Long
    ) {
        val builder = baseBuilder(songId, title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setSmallIcon(R.drawable.rounded_download_24)
            .setContentText(
                if (indeterminate) {
                    context.getString(R.string.download_notification_preparing)
                } else {
                    context.getString(
                        R.string.download_notification_progress,
                        progressPercent.coerceIn(0, 100),
                        formatBytes(bytesDownloaded),
                        formatBytes(totalBytes)
                    )
                }
            )

        if (indeterminate) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(100, progressPercent.coerceIn(0, 100), false)
        }

        notificationManager?.notify(notificationIdFor(songId), builder.build())
    }

    /** Short-lived card confirming a download finished. */
    fun notifyCompleted(songId: String, title: String) {
        val builder = baseBuilder(songId, title)
            .setOngoing(false)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setSmallIcon(R.drawable.rounded_download_24)
            .setContentText(context.getString(R.string.download_notification_completed))

        notificationManager?.notify(notificationIdFor(songId), builder.build())
    }

    /** Short-lived card reporting a failed download. */
    fun notifyFailed(songId: String, title: String, reason: String) {
        val builder = baseBuilder(songId, title)
            .setOngoing(false)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setSmallIcon(R.drawable.rounded_download_24)
            .setContentText(
                context.getString(R.string.download_notification_failed, reason)
            )

        notificationManager?.notify(notificationIdFor(songId), builder.build())
    }

    /** Removes the live progress card (download cancelled / replaced). */
    fun cancelProgress(songId: String) {
        notificationManager?.cancel(notificationIdFor(songId))
    }

    private fun baseBuilder(songId: String, title: String): NotificationCompat.Builder {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationCompat.Builder(context, DOWNLOADS_CHANNEL_ID)
        } else {
            NotificationCompat.Builder(context)
        }
        return builder
            .setContentTitle(title)
            .setContentIntent(contentIntent(songId))
            .setOnlyAlertOnce(true)
    }

    private fun notificationIdFor(songId: String): Int =
        (DOWNLOADS_NOTIFICATION_ID_BASE + songId.hashCode()) and 0x7FFFFFFF

    private fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "—"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1.0 -> String.format(java.util.Locale.US, "%.1f MB", mb)
            kb >= 1.0 -> String.format(java.util.Locale.US, "%.0f kB", kb)
            else -> "$bytes B"
        }
    }

    companion object {
        const val DOWNLOADS_CHANNEL_ID = "PixelTune_downloads_channel"
        const val EXTRA_OPEN_DOWNLOADS = "com.theveloper.pixeltune.extra.OPEN_DOWNLOADS"
        // 0x0D0D0000 = 218359808 — fits in a positive Int and keeps download
        // notification ids far away from the media playback notification id.
        private const val DOWNLOADS_NOTIFICATION_ID_BASE = 0x0D0D0000
    }
}
