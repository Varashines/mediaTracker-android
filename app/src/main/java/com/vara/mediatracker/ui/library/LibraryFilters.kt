package com.vara.mediatracker.ui.library

import com.vara.mediatracker.data.model.MediaItemEntity
import java.util.Locale

/**
 * Multi-select facet filters, a sort, and a group-by for the Library.
 *
 * The Library is where discovery happens on mobile: instead of a separate dashboard,
 * every facet the Discovery Hub aggregated (network, studio, genre, language) becomes a
 * filter here, applied to the poster grid the user is already looking at.
 */
data class LibraryFilters(
    val type: String = TYPE_ALL,
    val state: String = STATE_ALL,
    val tastes: Set<String> = emptySet(),
    val networks: Set<String> = emptySet(),
    val studios: Set<String> = emptySet(),
    val genres: Set<String> = emptySet(),
    val languages: Set<String> = emptySet(),
    val sort: String = SORT_RECENT,
    val groupBy: String = GROUP_NONE
) {
    /** Number of active facet selections, for the Filters button badge. */
    val activeCount: Int
        get() = tastes.size + networks.size + studios.size + genres.size + languages.size

    companion object {
        const val TYPE_ALL = "All"
        const val STATE_ALL = "All"

        const val SORT_RECENT = "Recently added"
        const val SORT_TITLE = "Title"
        const val SORT_RELEASE = "Release date"
        const val SORT_WATCHED = "Recently watched"

        const val GROUP_NONE = "None"
        const val GROUP_NETWORK = "Network"
        const val GROUP_STUDIO = "Studio"
        const val GROUP_GENRE = "Genre"
        const val GROUP_DECADE = "Decade"

        val SORTS = listOf(SORT_RECENT, SORT_TITLE, SORT_RELEASE, SORT_WATCHED)
        val GROUPS = listOf(GROUP_NONE, GROUP_NETWORK, GROUP_STUDIO, GROUP_GENRE, GROUP_DECADE)
        val TASTES = listOf("Loved", "Liked", "Disliked")
    }
}

/** Facets the filter sheet can offer, in display order. */
enum class LibraryFacet(val key: String, val title: String) {
    NETWORK("network", "Networks"),
    STUDIO("studio", "Studios"),
    GENRE("genre", "Genres"),
    LANGUAGE("language", "Languages")
}

/** Pure filtering / sorting / grouping over the library, so it can be unit-tested. */
object LibraryQuery {

    fun apply(items: List<MediaItemEntity>, query: String, filters: LibraryFilters): List<MediaItemEntity> {
        val q = query.trim()
        val filtered = items.filter { item ->
            if (item.isSoftDeleted) return@filter false
            (q.isEmpty() || item.title.contains(q, ignoreCase = true)) &&
                matchesType(item, filters.type) &&
                matchesState(item, filters.state) &&
                (filters.tastes.isEmpty() || filters.tastes.any { matchesTaste(item.taste, it) }) &&
                (filters.networks.isEmpty() || filters.networks.any { it.equals(item.network, ignoreCase = true) }) &&
                (filters.studios.isEmpty() || filters.studios.any { csvContains(item.studios, it) }) &&
                (filters.genres.isEmpty() || filters.genres.any { csvContains(item.genres, it) }) &&
                (filters.languages.isEmpty() || filters.languages.any { it.equals(item.language, ignoreCase = true) })
        }
        return sort(filtered, filters.sort)
    }

    fun sort(items: List<MediaItemEntity>, sort: String): List<MediaItemEntity> = when (sort) {
        LibraryFilters.SORT_TITLE -> items.sortedBy { it.title.lowercase(Locale.US) }
        LibraryFilters.SORT_RELEASE -> items.sortedByDescending { it.releaseDate ?: "" }
        LibraryFilters.SORT_WATCHED -> items.sortedByDescending { it.lastStateChangeDate ?: it.lastInteractionDate ?: "" }
        else -> items.sortedByDescending { it.lastInteractionDate ?: it.updatedAt ?: it.id }
    }

    /**
     * Groups the (already filtered + sorted) items for display. The empty-string key
     * means "no grouping" — the caller renders a flat grid.
     */
    fun group(items: List<MediaItemEntity>, groupBy: String): List<Pair<String, List<MediaItemEntity>>> {
        if (groupBy == LibraryFilters.GROUP_NONE) return listOf("" to items)
        val grouped = when (groupBy) {
            LibraryFilters.GROUP_NETWORK -> items.groupBy { firstCsv(it.network) ?: "Unknown network" }
            LibraryFilters.GROUP_STUDIO -> items.groupBy { firstCsv(it.studios) ?: "Unknown studio" }
            LibraryFilters.GROUP_GENRE -> items.groupBy { firstCsv(it.genres) ?: "Unknown genre" }
            LibraryFilters.GROUP_DECADE -> items.groupBy { decadeOf(it.releaseDate) }
            else -> return listOf("" to items)
        }
        return grouped.entries
            .sortedWith(compareByDescending<Map.Entry<String, List<MediaItemEntity>>> { it.value.size }.thenBy { it.key })
            .map { it.key to it.value }
    }

    /** Distinct values for a facet, most common first, for the filter sheet's chips. */
    fun facetValues(items: List<MediaItemEntity>, facet: LibraryFacet): List<String> {
        val values = when (facet) {
            LibraryFacet.NETWORK -> items.mapNotNull { it.network?.takeIf { n -> n.isNotBlank() } }
            LibraryFacet.STUDIO -> items.flatMap { csv(it.studios) }
            LibraryFacet.GENRE -> items.flatMap { csv(it.genres) }
            LibraryFacet.LANGUAGE -> items.mapNotNull { it.language?.takeIf { it.isNotBlank() } }
        }
        return values.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase(Locale.US) })
            .map { it.key }
    }

    fun languageLabel(code: String): String =
        runCatching { Locale.forLanguageTag(code).displayLanguage.ifBlank { code.uppercase(Locale.US) } }
            .getOrDefault(code.uppercase(Locale.US))

    // ---- helpers ----

    private fun matchesType(item: MediaItemEntity, type: String): Boolean = when (type) {
        "Movies" -> item.type.contains("Movie", ignoreCase = true)
        "TV Shows" -> item.type.contains("TV", ignoreCase = true)
        else -> true
    }

    private fun matchesState(item: MediaItemEntity, state: String): Boolean = when (state) {
        "Watching" -> item.state.equals("Watching", true) || item.state.equals("Active", true)
        "Wishlist" -> item.state.equals("Wishlist", true)
        "Completed" -> item.state.equals("Completed", true)
        "Loved" -> matchesTaste(item.taste, "Loved")
        else -> true
    }

    private fun matchesTaste(taste: String, filter: String): Boolean = when (filter) {
        "Loved" -> taste.equals("Loved", true) || taste.equals("Love", true)
        "Liked" -> taste.equals("Liked", true) || taste.equals("Like", true)
        "Disliked" -> taste.equals("Disliked", true) || taste.equals("Dislike", true)
        else -> false
    }

    private fun csvContains(csv: String?, value: String): Boolean =
        csv?.split(',')?.any { it.trim().equals(value, ignoreCase = true) } == true

    private fun csv(csv: String?): List<String> =
        csv?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    private fun firstCsv(csv: String?): String? = this.csv(csv).firstOrNull()

    private fun decadeOf(releaseDate: String?): String {
        val year = releaseDate?.take(4)?.toIntOrNull() ?: return "Unknown"
        return "${(year / 10) * 10}s"
    }
}
