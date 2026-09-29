package com.saine.pixeltune.utils

import com.saine.pixeltune.data.model.SearchResultItem

/**
 * FIX(online-search-chip-crash): LazyColumn key helpers for the ONLINE search
 * results and the cloud catalog track list.
 *
 * CRASH RECAP — the app hard-crashed
 * (`IllegalArgumentException: Key "…" was used multiple times`, thrown by
 * Compose's SaveableStateProvider) the moment the results of the ONLINE
 * search's Playlists or Artists filter chips rendered, and again when a
 * playlist's tracks opened on the CloudCatalog screen:
 *
 *  - `SearchResultsList` registered rows with keys like
 *    `"cloud_playlist_${playlist.id}"` / `"cloud_artist_${artist.id}"`,
 *    where the ids are `url.hashCode().toString()`;
 *  - the provider search pages REPEAT entries (the same playlist in the
 *    top-result shelf AND the main list; the same channel in multiple
 *    `musicShelfRenderer`s of a YT Music artists response; the same video
 *    twice inside a YouTube playlist; SoundCloud playlists repeat tracks
 *    constantly) — two items with the same URL therefore produce the same id
 *    and the SAME LazyColumn key;
 *  - hash-based ids additionally collide for DIFFERENT urls (~1 in 4 billion
 *    pairs — rare, but the crash is fatal when it happens).
 *
 * The repositories now dedupe their pages (identical-URL repeats never reach
 * the UI), but the LazyColumn key contract — "no two simultaneously-composed
 * items share a key" — is enforced HERE, at the crash site, for ANY input
 * list: keys stay byte-identical to the historical ones for the (common)
 * unique case, and only genuinely repeated base keys get a deterministic
 * occurrence suffix. Recomposition behavior is unchanged for every list the
 * app rendered correctly before.
 */
object SearchResultKeys {

    /**
     * The historical base key of a search result row — unchanged from the
     * pre-fix format so item state / scroll anchors survive list updates
     * exactly as before for unique ids.
     */
    fun baseKeyOf(item: SearchResultItem): String = when (item) {
        is SearchResultItem.SongItem -> "song_${item.song.id}"
        is SearchResultItem.AlbumItem -> "album_${item.album.id}"
        is SearchResultItem.ArtistItem -> "artist_${item.artist.id}"
        is SearchResultItem.PlaylistItem -> "playlist_${item.playlist.id}"
        is SearchResultItem.CloudPlaylistItem -> "cloud_playlist_${item.playlist.id}"
        is SearchResultItem.CloudArtistItem -> "cloud_artist_${item.artist.id}"
    }

    /**
     * Globally-unique LazyColumn keys for [items] — one key per element,
     * aligned by index (keys[i] belongs to items[i]).
     *
     * Uniqueness is guaranteed across the WHOLE list, not per section: the
     * results LazyColumn composes several sections (Songs / Albums / Artists /
     * Playlists) in ONE layout, so an id that appears in two sections is just
     * as fatal as a duplicate inside one section.
     */
    fun uniqueKeysFor(items: List<SearchResultItem>): List<String> =
        disambiguate(items.map { baseKeyOf(it) })

    /**
     * Disambiguates [baseKeys] into a list of globally-unique LazyColumn keys
     * (aligned by index). A base key that occurs exactly once is returned
     * UNCHANGED — the common case keeps the exact key strings the app always
     * used, so Compose item state, scroll anchors and recomposition skipping
     * behave exactly as before. A base key that occurs N > 1 times gets a
     * deterministic `~<ordinal>` suffix (first occurrence `~0`, second `~1`,
     * …) — stable for a given list content, unique no matter how many repeats
     * or id collisions the provider data contains.
     */
    fun disambiguate(baseKeys: List<String>): List<String> {
        if (baseKeys.size < 2) return baseKeys.toList()

        val totals = HashMap<String, Int>(baseKeys.size)
        for (base in baseKeys) {
            totals[base] = (totals[base] ?: 0) + 1
        }

        val seen = HashMap<String, Int>()
        val out = ArrayList<String>(baseKeys.size)
        for (base in baseKeys) {
            if ((totals[base] ?: 0) == 1) {
                out.add(base)
            } else {
                val ordinal = seen[base] ?: 0
                seen[base] = ordinal + 1
                out.add("$base~$ordinal")
            }
        }
        return out
    }
}
