package com.vara.mediatracker.ui.library

import com.vara.mediatracker.data.model.MediaItemEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryQueryTest {

    private fun item(
        id: String,
        title: String = id,
        type: String = "Movie",
        state: String = "Wishlist",
        taste: String = "None",
        network: String? = null,
        studios: String? = null,
        genres: String? = null,
        language: String? = null,
        releaseDate: String? = null,
        lastInteractionDate: String? = null
    ) = MediaItemEntity(
        id = id,
        title = title,
        type = type,
        state = state,
        taste = taste,
        network = network,
        studios = studios,
        genres = genres,
        language = language,
        releaseDate = releaseDate,
        lastInteractionDate = lastInteractionDate
    )

    private val library = listOf(
        item("movie_1", "Alpha", type = "Movie", state = "Completed", taste = "Loved",
            studios = "Marvel Studios, Paramount", genres = "Action, Sci-Fi", language = "en",
            releaseDate = "2019-05-01", lastInteractionDate = "2026-01-03"),
        item("movie_2", "Beta", type = "Movie", state = "Wishlist", taste = "None",
            studios = "Lucasfilm Ltd.", genres = "Sci-Fi", language = "en",
            releaseDate = "1983-05-01", lastInteractionDate = "2026-01-02"),
        item("tv_3", "Gamma", type = "TV Show", state = "Watching", taste = "Like",
            network = "Disney+", studios = "Marvel Studios", genres = "Animation",
            language = "en", releaseDate = "2021-01-01", lastInteractionDate = "2026-01-01"),
        item("tv_4", "Delta", type = "TV Show", state = "Watching", taste = "Dislike",
            network = "HBO", genres = "Drama", language = "hi",
            releaseDate = "1995-01-01", lastInteractionDate = "2026-01-04")
    )

    @Test
    fun `type filter narrows to movies or tv`() {
        val movies = LibraryQuery.apply(library, "", LibraryFilters(type = "Movies"))
        assertEquals(listOf("movie_1", "movie_2"), movies.map { it.id }.sorted())

        val tv = LibraryQuery.apply(library, "", LibraryFilters(type = "TV Shows"))
        assertEquals(listOf("tv_3", "tv_4"), tv.map { it.id }.sorted())
    }

    @Test
    fun `state and taste filters combine`() {
        val loved = LibraryQuery.apply(library, "", LibraryFilters(tastes = setOf("Loved")))
        assertEquals(listOf("movie_1"), loved.map { it.id })

        // "Like" stored singular still matches the "Liked" filter.
        val liked = LibraryQuery.apply(library, "", LibraryFilters(tastes = setOf("Liked")))
        assertEquals(listOf("tv_3"), liked.map { it.id })

        val watching = LibraryQuery.apply(library, "", LibraryFilters(state = "Watching"))
        assertEquals(listOf("tv_3", "tv_4"), watching.map { it.id }.sorted())
    }

    @Test
    fun `studio and genre filters match comma-joined values`() {
        val marvel = LibraryQuery.apply(library, "", LibraryFilters(studios = setOf("Marvel Studios")))
        assertEquals(listOf("movie_1", "tv_3"), marvel.map { it.id }.sorted())

        val sciFi = LibraryQuery.apply(library, "", LibraryFilters(genres = setOf("Sci-Fi")))
        assertEquals(listOf("movie_1", "movie_2"), sciFi.map { it.id }.sorted())
    }

    @Test
    fun `network filter is exact and case-insensitive`() {
        val disney = LibraryQuery.apply(library, "", LibraryFilters(networks = setOf("disney+")))
        assertEquals(listOf("tv_3"), disney.map { it.id })
    }

    @Test
    fun `search matches title case-insensitively`() {
        val results = LibraryQuery.apply(library, "be", LibraryFilters())
        assertEquals(listOf("movie_2"), results.map { it.id })
    }

    @Test
    fun `sort by release date is newest first`() {
        val sorted = LibraryQuery.sort(library, LibraryFilters.SORT_RELEASE)
        assertEquals(listOf("tv_3", "movie_1", "tv_4", "movie_2"), sorted.map { it.id })
    }

    @Test
    fun `group by network buckets shows and falls back for movies`() {
        val groups = LibraryQuery.group(library, LibraryFilters.GROUP_NETWORK).toMap()
        assertEquals(listOf("tv_3"), groups.getValue("Disney+").map { it.id })
        assertEquals(listOf("tv_4"), groups.getValue("HBO").map { it.id })
        // Movies carry no network, so they land in the fallback bucket together.
        assertEquals(listOf("movie_1", "movie_2"), groups.getValue("Unknown network").map { it.id }.sorted())
    }

    @Test
    fun `group by decade buckets release years`() {
        val groups = LibraryQuery.group(library, LibraryFilters.GROUP_DECADE).toMap()
        assertEquals(listOf("movie_1"), groups.getValue("2010s").map { it.id })
        assertEquals(listOf("tv_4"), groups.getValue("1990s").map { it.id })
    }

    @Test
    fun `facet values are ranked by count`() {
        val studios = LibraryQuery.facetValues(library, LibraryFacet.STUDIO)
        assertEquals("Marvel Studios", studios.first())
    }

    @Test
    fun `no grouping returns a single flat section`() {
        val groups = LibraryQuery.group(library, LibraryFilters.GROUP_NONE)
        assertEquals(1, groups.size)
        assertEquals("", groups.first().first)
        assertEquals(4, groups.first().second.size)
    }
}
