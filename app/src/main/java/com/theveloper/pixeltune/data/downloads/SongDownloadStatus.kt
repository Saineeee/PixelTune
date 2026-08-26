package com.theveloper.pixeltune.data.downloads

import com.theveloper.pixeltune.data.model.Song

/**
 * IMPROVE(offline-downloads): UI-facing status of a song's offline download.
 * [Hidden] is the default for local (non-cloud) songs so every existing
 * SongInfoBottomSheet call site keeps compiling without changes.
 */
sealed class SongDownloadStatus {
    /** Not a downloadable online song — hide the download UI. */
    data object Hidden : SongDownloadStatus()

    /** Online song without an offline copy. */
    data object NotDownloaded : SongDownloadStatus()

    /** Download in flight; [progressPercent] is 0..100 (0 when indeterminate). */
    data class Downloading(val progressPercent: Int, val indeterminate: Boolean) : SongDownloadStatus()

    /** Offline copy available for in-app playback. */
    data object Downloaded : SongDownloadStatus()
}

/**
 * Derives the UI status of [song]'s offline download from the live repository
 * state maps ([downloaded] and [states]). [isCloud] should come from the
 * player's cloud-song classification (local songs are never downloadable).
 */
fun songDownloadStatus(
    song: Song?,
    isCloud: Boolean,
    downloaded: Map<String, DownloadedSong>,
    states: Map<String, DownloadState>
): SongDownloadStatus {
    if (song == null || !isCloud) return SongDownloadStatus.Hidden
    return when {
        downloaded.containsKey(song.id) -> SongDownloadStatus.Downloaded
        states[song.id] is DownloadState.Downloading -> {
            val downloading = states[song.id] as DownloadState.Downloading
            SongDownloadStatus.Downloading(
                progressPercent = if (downloading.progressFraction >= 0f) {
                    (downloading.progressFraction * 100f).toInt().coerceIn(0, 100)
                } else {
                    0
                },
                indeterminate = downloading.progressFraction < 0f
            )
        }
        states[song.id] is DownloadState.Failed -> SongDownloadStatus.NotDownloaded
        else -> SongDownloadStatus.NotDownloaded
    }
}
