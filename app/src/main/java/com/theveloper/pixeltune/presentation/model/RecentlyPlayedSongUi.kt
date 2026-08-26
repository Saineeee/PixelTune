package com.theveloper.pixeltune.presentation.model

import com.theveloper.pixeltune.data.model.Song
import com.theveloper.pixeltune.data.stats.PlaybackStatsRepository
import com.theveloper.pixeltune.data.stats.StatsTimeRange
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class RecentlyPlayedSongUiModel(
    val song: Song,
    val lastPlayedTimestamp: Long
)

fun mapRecentlyPlayedSongs(
    playbackHistory: List<PlaybackStatsRepository.PlaybackHistoryEntry>,
    songs: List<Song>,
    range: StatsTimeRange? = null,
    nowMillis: Long = System.currentTimeMillis(),
    maxItems: Int = Int.MAX_VALUE
): List<RecentlyPlayedSongUiModel> {
    if (maxItems <= 0 || playbackHistory.isEmpty()) return emptyList()

    val songById = songs.associateBy { it.id }
    val (startBound, endBound) = range.resolveBounds(
        nowMillis = nowMillis.coerceAtLeast(0L),
        zoneId = ZoneId.systemDefault()
    )

    val seenSongIds = HashSet<String>()
    val deduped = ArrayList<RecentlyPlayedSongUiModel>(maxItems.coerceAtMost(playbackHistory.size))

    val sortedHistory = playbackHistory.sortedWith(
        compareByDescending<PlaybackStatsRepository.PlaybackHistoryEntry> { it.timestamp }
            .thenBy { it.songId }
    )

    for (entry in sortedHistory) {
        if (deduped.size >= maxItems) break
        val safeTimestamp = entry.timestamp.coerceAtLeast(0L)
        if (safeTimestamp > endBound) continue
        if (startBound != null && safeTimestamp < startBound) continue
        if (!seenSongIds.add(entry.songId)) continue

        // FIX(listening-history-cloud): resolve the song from the library first;
        // if that fails (cloud-streamed songs such as YouTube / SoundCloud are
        // NOT part of the local MediaStore-backed library, and the session-only
        // in-memory cloud registry is empty after an app restart), fall back to
        // the metadata snapshot persisted alongside the history entry. Before
        // this fallback existed, unresolvable entries were silently skipped —
        // cloud songs simply never showed up in Listening History / Recently
        // Played.
        val song = songById[entry.songId] ?: entry.toFallbackSong() ?: continue
        deduped += RecentlyPlayedSongUiModel(
            song = song,
            lastPlayedTimestamp = safeTimestamp
        )
    }

    return deduped
}

/**
 * Builds a playable [Song] from a history entry's metadata snapshot.
 *
 * Returns null when the entry carries no snapshot (entries recorded by older
 * app versions only stored songId + timestamp) — such entries can only be
 * resolved through the song library lookup.
 */
private fun PlaybackStatsRepository.PlaybackHistoryEntry.toFallbackSong(): Song? {
    if (!hasMetadataSnapshot) return null
    return Song(
        id = songId,
        title = title.orEmpty(),
        artist = artist ?: "Unknown Artist",
        artistId = -1L,
        artists = emptyList(),
        album = album ?: "",
        albumId = -1L,
        albumArtist = null,
        path = "",
        contentUriString = contentUri ?: "",
        albumArtUriString = albumArtUri,
        duration = songDurationMs ?: 0L,
        genre = null,
        lyrics = null,
        isFavorite = false,
        trackNumber = 0,
        year = 0,
        dateAdded = timestamp,
        dateModified = 0,
        mimeType = null,
        bitrate = null,
        sampleRate = null
    )
}

private fun StatsTimeRange?.resolveBounds(
    nowMillis: Long,
    zoneId: ZoneId
): Pair<Long?, Long> {
    val safeNow = nowMillis.coerceAtLeast(0L)
    val zonedNow = java.time.Instant.ofEpochMilli(safeNow).atZone(zoneId)

    return when (this) {
        StatsTimeRange.DAY -> {
            val start = zonedNow.toLocalDate().atStartOfDay(zoneId).toInstant().toEpochMilli()
            start to safeNow
        }
        StatsTimeRange.WEEK -> {
            val startOfWeek = zonedNow.toLocalDate().with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            val start = startOfWeek.atStartOfDay(zoneId).toInstant().toEpochMilli()
            start to safeNow
        }
        StatsTimeRange.MONTH -> {
            val startOfMonth = zonedNow.toLocalDate().withDayOfMonth(1)
            val start = startOfMonth.atStartOfDay(zoneId).toInstant().toEpochMilli()
            start to safeNow
        }
        StatsTimeRange.YEAR -> {
            val startOfYear = zonedNow.toLocalDate().withDayOfYear(1)
            val start = startOfYear.atStartOfDay(zoneId).toInstant().toEpochMilli()
            start to safeNow
        }
        StatsTimeRange.ALL, null -> {
            null to safeNow
        }
    }
}
