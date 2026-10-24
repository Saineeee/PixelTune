package com.saine.pixeltune.data.search

/**
 * Online search autocomplete helpers shared by the YouTube and SoundCloud
 * repositories.
 *
 * IMPROVE(online-search-autocomplete): both providers' NewPipe suggestion
 * extractors return raw lists that can contain blank entries, untrimmed
 * whitespace and occasional duplicates; the display pipeline expects a
 * clean, bounded, order-preserving list. Centralizing the normalization
 * keeps both providers' output identical in shape.
 */
object SearchSuggestionUtils {

    /**
     * Cap on displayed autocomplete suggestions — enough variety to be
     * useful, small enough to scan while typing without pushing the
     * keyboard off the useful part of the screen.
     */
    const val MAX_SUGGESTIONS = 8

    /**
     * Normalizes a provider's raw suggestion list for display:
     * - trims each entry,
     * - drops blank entries,
     * - removes duplicates (first occurrence wins),
     * - preserves the provider's relevance order,
     * - caps the list at [MAX_SUGGESTIONS].
     */
    fun normalize(raw: List<String>): List<String> =
        raw.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_SUGGESTIONS)
            .toList()
}
