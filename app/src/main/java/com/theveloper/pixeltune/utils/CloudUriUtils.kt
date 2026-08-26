package com.theveloper.pixeltune.utils

import android.net.Uri
import kotlin.math.abs

/**
 * Shared helpers for cloud-streamed songs (YouTube / SoundCloud / Telegram /
 * Netease / GDrive).
 *
 * Extracted from [com.theveloper.pixeltune.data.repository.MusicRepositoryImpl]
 * so that other components (ListeningStatsTracker, PlayerViewModel, etc.) can
 * share the exact same URI-normalization and ID-derivation rules. Keeping these
 * rules in ONE place is critical: the write path (favoriting a cloud song),
 * the read path (restoring the cloud ID when loading from Room) and the
 * preference/Room reconciliation (syncFavoritesStores) must all agree on
 * (a) what a "cloud" content URI looks like and
 * (b) how a cloud string ID maps to the Long primary-key domain of Room.
 */
object CloudUriUtils {

    /** Cloud URI schemes persisted in the songs table. */
    val CLOUD_SCHEMES = setOf("youtube", "soundcloud", "telegram", "netease", "gdrive")

    /**
     * Stable Long ID derived from an arbitrary String ID via hashCode, forced
     * to be non-negative so it fits into the same positive-Long primary-key
     * domain as MediaStore song IDs.
     *
     * Used for cloud-streamed songs (YouTube video IDs, etc.) that don't have
     * a natural Long form. The mapping is deterministic across app restarts,
     * so the same cloud song always maps to the same Room row id — making
     * re-liking / unliking after restart correctly find the existing row.
     *
     * Note: hashCode collisions are theoretically possible, but for typical
     * 11-char YouTube IDs the collision space is well below the Long domain,
     * and the practical impact of a collision is at most a single song being
     * replaced in the favorites tab (which the user can re-like to fix).
     */
    fun stableLongIdFromString(id: String): Long {
        if (id.isEmpty()) return 0L
        val hash = id.hashCode().toLong()
        return if (hash >= 0) hash else -hash
    }

    /**
     * Stable negative Long ID for a synthetic (non-MediaStore) artist or
     * album name. Negative IDs can never collide with real MediaStore IDs —
     * the same convention the Telegram/Netease sync uses for unified rows.
     */
    fun stableSyntheticIdFromName(name: String): Long {
        if (name.isEmpty()) return -1L
        val hash = abs(name.trim().lowercase().hashCode().toLong())
        return if (hash == 0L) -1L else -hash
    }

    /**
     * Converts a cloud-streamed playback URI to a stable, restart-safe scheme
     * URI so it can be persisted to the Room DB without breaking when the
     * local HTTP proxy rebinds to a different port on the next app launch.
     *
     * Examples:
     *   "http://127.0.0.1:53719/youtube/dQw4w9WgXcQ"
     *     -> "youtube://dQw4w9WgXcQ"
     *   "http://127.0.0.1:53719/soundcloud/encodedPayload"
     *     -> "soundcloud://encodedPayload"
     *
     * URIs that are already in scheme form (telegram://, netease://, gdrive://,
     * youtube://, soundcloud://) and local file/content URIs are returned
     * unchanged. Plain absolute paths are also returned unchanged.
     */
    fun normalizeCloudUriForStorage(contentUriString: String): String {
        if (contentUriString.isEmpty()) return contentUriString
        // Already in scheme form — leave alone.
        val parsed = runCatching { Uri.parse(contentUriString) }.getOrNull()
            ?: return contentUriString
        val scheme = parsed.scheme?.lowercase()
        if (scheme != null && (scheme in CLOUD_SCHEMES || scheme == "content" || scheme == "file")) {
            return contentUriString
        }
        // Convert localhost HTTP proxy URLs back to their scheme form.
        if (scheme == "http" || scheme == "https") {
            val host = parsed.host?.lowercase()
            if (host == "127.0.0.1" || host == "localhost") {
                val pathSegments = parsed.pathSegments
                // Path shape: ["youtube", "<id>"] or ["soundcloud", "<encoded>"]
                if (pathSegments.size >= 2) {
                    val provider = pathSegments[0].lowercase()
                    val payload = pathSegments.subList(1, pathSegments.size)
                        .joinToString("/") { it }
                    return when (provider) {
                        "youtube" -> "youtube://$payload"
                        "soundcloud" -> "soundcloud://$payload"
                        else -> contentUriString
                    }
                }
            }
        }
        return contentUriString
    }

    /**
     * Whether the given URI string identifies a cloud-streamed song — either
     * via a known cloud scheme (youtube://, soundcloud://, telegram://,
     * netease://, gdrive://) or via an HTTP(S) URL (the live local proxy URL
     * while streaming, or the upstream service URL).
     *
     * Local MediaStore songs use content:// or file:// URIs (or plain absolute
     * filesystem paths) and are therefore never classified as cloud by this
     * check.
     */
    fun isCloudContentUri(contentUriString: String): Boolean {
        if (contentUriString.isEmpty()) return false
        val parsed = runCatching { Uri.parse(contentUriString) }.getOrNull() ?: return false
        val scheme = parsed.scheme?.lowercase() ?: return false
        if (scheme in CLOUD_SCHEMES) return true
        if (scheme == "http" || scheme == "https") return true
        return false
    }
}
