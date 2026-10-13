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
        genres = genres,
        language = language,
        releaseDate = releaseDate,
        lastInteractionDate = lastInteractionDate
    )

    private val library = listOf(
        item("movie_1", "Alpha", type = "Movie", state = "Completed", taste = "Loved",
            genres = "Action, Science Fiction", language = "en",
            releaseDate = "2019-05-01", lastInteractionDate = "2026-01-03"),
        item("movie_2", "Beta", type = "Movie", state = "Wishlist", taste = "None",
            genres = "Science Fiction", language = "en",
            releaseDate = "1983-05-01", lastInteractionDate = "2026-01-02"),
        item("tv_3", "Gamma", type = "TV Show", state = "Watching", taste = "Like",
            genres = "Animation", language = "en",
            releaseDate = "2021-01-01", lastInteractionDate = "2026-01-01"),
        item("tv_4", "Delta", type = "TV Show", state = "Watching", taste = "Dislike",
            genres = "Drama", language = "hi",
            releaseDate = "1995-01-01", lastInteractionDate = "2026-01-04")
    )

    private val badges = mapOf("movie_1" to "PREMIERE", "tv_3" to "NEW")

    @Test
    fun `type filter narrows to movies or tv`() {
        val movies = LibraryQuery.apply(library, "", LibraryFilters(type = "Movies"))
        assertEquals(listOf("movie_1", "movie_2"), movies.map { it.id }.sorted())

        val tv = LibraryQuery.apply(library, "", LibraryFilters(type = "TV Shows"))
        assertEquals(listOf("tv_3", "tv_4"), tv.map { it.id }.sorted())
    }

    @Test
    fun `state filter matches`() {
        val watching = LibraryQuery.apply(library, "", LibraryFilters(state = "Watching"))
        assertEquals(listOf("tv_3", "tv_4"), watching.map { it.id }.sorted())

        val loved = LibraryQuery.apply(library, "", LibraryFilters(state = "Loved"))
        assertEquals(listOf("movie_1"), loved.map { it.id })
    }

    @Test
    fun `badge filter matches on the item badge label`() {
        val premieres = LibraryQuery.apply(library, "", LibraryFilters(badges = setOf("PREMIERE")), badges)
        assertEquals(listOf("movie_1"), premieres.map { it.id })

        val newAndPremiere = LibraryQuery.apply(library, "", LibraryFilters(badges = setOf("NEW", "PREMIERE")), badges)
        assertEquals(listOf("movie_1", "tv_3"), newAndPremiere.map { it.id }.sorted())
    }

    @Test
    fun `genre filter matches comma-joined values`() {
        val sciFi = LibraryQuery.apply(library, "", LibraryFilters(genres = setOf("Science Fiction")))
        assertEquals(listOf("movie_1", "movie_2"), sciFi.map { it.id }.sorted())
    }

    @Test
    fun `language filter is exact and case-insensitive`() {
        val hindi = LibraryQuery.apply(library, "", LibraryFilters(languages = setOf("HI")))
        assertEquals(listOf("tv_4"), hindi.map { it.id })
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
    fun `group by genre buckets by the first genre`() {
        val groups = LibraryQuery.group(library, LibraryFilters.GROUP_GENRE).toMap()
        assertEquals(listOf("movie_1"), groups.getValue("Action").map { it.id })
        assertEquals(listOf("tv_4"), groups.getValue("Drama").map { it.id })
    }

    @Test
    fun `group by decade buckets release years`() {
        val groups = LibraryQuery.group(library, LibraryFilters.GROUP_DECADE).toMap()
        assertEquals(listOf("movie_1"), groups.getValue("2010s").map { it.id })
        assertEquals(listOf("tv_4"), groups.getValue("1990s").map { it.id })
    }

    @Test
    fun `facet values are ranked by count`() {
        val genres = LibraryQuery.facetValues(library, LibraryFacet.GENRE)
        assertEquals("Science Fiction", genres.first())
    }

    @Test
    fun `no grouping returns a single flat section`() {
        val groups = LibraryQuery.group(library, LibraryFilters.GROUP_NONE)
        assertEquals(1, groups.size)
        assertEquals("", groups.first().first)
        assertEquals(4, groups.first().second.size)
    }
}
