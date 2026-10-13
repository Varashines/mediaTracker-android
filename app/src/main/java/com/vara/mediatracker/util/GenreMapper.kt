package com.vara.mediatracker.util

import java.util.Locale

/**
 * Transforms raw TMDB genre names into standardized, atomic genres.
 *
 * Ported one-for-one from the macOS `GenreMapper`, so a title's genre facet is identical
 * on both platforms: ["Action & Adventure", "Sci-Fi"] -> ["Action", "Adventure",
 * "Science Fiction"]. Applied when the metadata worker stores genres.
 */
object GenreMapper {

    fun standardize(rawGenres: List<String>): List<String> {
        val results = sortedSetOf<String>()
        for (raw in rawGenres) {
            for (component in decompose(raw)) {
                results.add(map(component))
            }
        }
        return results.toList()
    }

    /** Splits compound genre strings on `&` and `/`, then on " and ". */
    private fun decompose(raw: String): List<String> {
        val results = mutableListOf<String>()
        for (component in raw.split('&', '/')) {
            val trimmed = component.trim()
            if (trimmed.lowercase(Locale.US) == "and") continue
            // Handle cases like "War and Politics".
            for (sub in trimmed.split(" and ")) {
                val finalTrim = sub.trim()
                if (finalTrim.isNotEmpty()) results.add(finalTrim)
            }
        }
        return results
    }

    /** Maps inconsistent naming to standardized atomic categories. */
    private fun map(genre: String): String = when (genre.lowercase(Locale.US)) {
        "sci-fi", "science fiction", "science-fiction" -> "Science Fiction"
        "tv movie" -> "TV Movie"
        "soap" -> "Soap Opera"
        else -> genre.split(' ').joinToString(" ") { word ->
            word.replaceFirstChar { it.uppercase(Locale.US) }
        }
    }
}
