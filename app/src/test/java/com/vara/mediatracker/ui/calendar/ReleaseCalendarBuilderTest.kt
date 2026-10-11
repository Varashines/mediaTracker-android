package com.vara.mediatracker.ui.calendar

import com.vara.mediatracker.data.model.EpisodeEntity
import com.vara.mediatracker.data.model.MediaIds
import com.vara.mediatracker.data.model.MediaItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ReleaseCalendarBuilderTest {

    private val year = 2026
    private val month = Calendar.MARCH // 0-based

    private fun movie(id: String, release: String?, softDeleted: Boolean = false, type: String = "Movie") =
        MediaItemEntity(id = id, title = id, type = type, releaseDate = release, isSoftDeleted = softDeleted)

    private fun show(id: String = "tv_1399", softDeleted: Boolean = false) =
        MediaItemEntity(id = id, title = id, type = "TV Show", isSoftDeleted = softDeleted)

    private fun ep(
        season: Int = 1,
        number: Int = 1,
        showId: String = "tv_1399",
        airDate: String? = null
    ) = EpisodeEntity(
        uniqueId = MediaIds.buildEpisodeUniqueId(showId, season, number),
        showId = showId,
        seasonNumber = season,
        episodeNumber = number,
        airDate = airDate
    )

    private fun events(items: List<MediaItemEntity>, episodes: List<EpisodeEntity>) =
        buildMonthReleaseEvents(items, episodes, year, month)

    @Test
    fun `multiple episodes on the same day collapse into one event`() {
        val result = events(
            listOf(show()),
            listOf(
                ep(1, 1, airDate = "2026-03-10"),
                ep(1, 2, airDate = "2026-03-10"),
                ep(1, 3, airDate = "2026-03-10")
            )
        )

        assertEquals(1, result.size)
        val event = result.single()
        assertEquals(3, event.episodeCount)
        assertEquals(listOf("S1 E1", "S1 E2", "S1 E3"), event.episodeLabels)
        assertTrue(event.isEpisode)
    }

    @Test
    fun `episodes on different days stay as separate events`() {
        val result = events(
            listOf(show()),
            listOf(
                ep(1, 1, airDate = "2026-03-10"),
                ep(1, 2, airDate = "2026-03-11")
            )
        )

        assertEquals(2, result.size)
        assertTrue(result.all { it.episodeCount == 1 })
    }

    @Test
    fun `two shows dropping on the same day stay separate`() {
        val result = events(
            listOf(show("tv_1"), show("tv_2")),
            listOf(
                ep(1, 1, showId = "tv_1", airDate = "2026-03-10"),
                ep(1, 1, showId = "tv_2", airDate = "2026-03-10")
            )
        )

        assertEquals(2, result.size)
        assertEquals(setOf("tv_1", "tv_2"), result.map { it.mediaItem.id }.toSet())
    }

    @Test
    fun `a multi episode drop spanning seasons groups together`() {
        val result = events(
            listOf(show()),
            listOf(
                ep(1, 9, airDate = "2026-03-10"),
                ep(2, 1, airDate = "2026-03-10")
            )
        )

        assertEquals(1, result.size)
        assertEquals(listOf("S1 E9", "S2 E1"), result.single().episodeLabels)
    }

    @Test
    fun `movie releases are standalone events`() {
        val result = events(listOf(movie("movie_1", "2026-03-05")), emptyList())

        val event = result.single()
        assertEquals("Movie Premiere", event.contextLabel)
        assertEquals(0, event.episodeCount)
        assertTrue(!event.isEpisode)
    }

    @Test
    fun `episodes are excluded from movie events`() {
        // A TV show with a releaseDate must not also produce a movie-style event.
        val result = events(listOf(show()), listOf(ep(1, 1, airDate = "2026-03-10")))

        assertEquals(1, result.size)
        assertTrue(result.single().isEpisode)
    }

    @Test
    fun `soft deleted titles are excluded`() {
        val result = events(
            listOf(
                movie("movie_live", "2026-03-05"),
                movie("movie_gone", "2026-03-06", softDeleted = true),
                show(softDeleted = true)
            ),
            listOf(ep(1, 1, airDate = "2026-03-10"))
        )

        assertEquals(listOf("movie_live"), result.map { it.mediaItem.id })
    }

    @Test
    fun `events outside the requested month are dropped`() {
        val result = events(
            listOf(
                movie("movie_a", "2026-02-28"),
                movie("movie_b", "2026-04-01"),
                movie("movie_c", "2026-03-15"),
                show()
            ),
            listOf(ep(1, 1, airDate = "2026-02-28"), ep(1, 2, airDate = "2026-03-12"))
        )

        assertEquals(setOf("movie_c"), result.filter { !it.isEpisode }.map { it.mediaItem.id }.toSet())
        assertEquals(1, result.count { it.isEpisode })
    }

    @Test
    fun `episodes with a missing or malformed air date are skipped`() {
        val result = events(
            listOf(show()),
            listOf(
                ep(1, 1, airDate = null),
                ep(1, 2, airDate = "garbage"),
                ep(1, 3, airDate = "2026-03-10")
            )
        )

        assertEquals(1, result.size)
        assertEquals(1, result.single().episodeCount)
    }

    @Test
    fun `episodes whose show is not in the library are skipped`() {
        val result = events(
            listOf(show("tv_1")),
            listOf(ep(1, 1, showId = "tv_999", airDate = "2026-03-10"))
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `bare tmdb show ids resolve to the canonical show`() {
        val result = events(
            listOf(show("tv_1399")),
            listOf(ep(showId = "1399", season = 2, number = 3, airDate = "2026-03-10"))
        )

        assertEquals(1, result.size)
        assertEquals("tv_1399", result.single().mediaItem.id)
        assertEquals(listOf("S2 E3"), result.single().episodeLabels)
    }

    @Test
    fun `contextLabel describes the group size`() {
        val single = events(listOf(show()), listOf(ep(1, 1, airDate = "2026-03-10"))).single()
        assertEquals("S1 E1", single.contextLabel)

        val triple = events(
            listOf(show()),
            listOf(
                ep(1, 4, airDate = "2026-03-10"),
                ep(1, 5, airDate = "2026-03-10"),
                ep(1, 6, airDate = "2026-03-10")
            )
        ).single()
        assertEquals("S1 E4 +2 more", triple.contextLabel)
    }

    @Test
    fun `results are ordered by date`() {
        val result = events(
            listOf(
                movie("movie_late", "2026-03-20"),
                movie("movie_early", "2026-03-02"),
                show()
            ),
            listOf(ep(1, 1, airDate = "2026-03-11"))
        )

        assertEquals(
            listOf("2026-03-02", "2026-03-11", "2026-03-20"),
            result.map { it.dateString }
        )
    }

    @Test
    fun `empty inputs produce no events`() {
        assertTrue(events(emptyList(), emptyList()).isEmpty())
    }
}