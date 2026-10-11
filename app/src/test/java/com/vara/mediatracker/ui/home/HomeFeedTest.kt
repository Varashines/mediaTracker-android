package com.vara.mediatracker.ui.home

import com.vara.mediatracker.data.model.EpisodeEntity
import com.vara.mediatracker.data.model.MediaIds
import com.vara.mediatracker.data.model.MediaItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The Home timeline replaced five horizontal rails with one chronological feed, so the
 * rules about what belongs in it are now load-bearing.
 */
class HomeFeedTest {

    private val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val now = Date(2026, Calendar.OCTOBER, 12, 9, 0, 0).time

    private fun day(offset: Long) = fmt.format(Date(now + offset * 86_400_000L))

    private fun movie(id: String, release: String?) =
        MediaItemEntity(id = id, title = id, type = "Movie", state = "Wishlist", releaseDate = release)

    private fun show(id: String = "tv_1", state: String = "Active") =
        MediaItemEntity(id = id, title = id, type = "TV Show", state = state)

    private fun ep(season: Int, number: Int, watched: Boolean = false, airDate: String?) =
        EpisodeEntity(
            uniqueId = MediaIds.buildEpisodeUniqueId("tv_1", season, number),
            showId = "tv_1",
            seasonNumber = season,
            episodeNumber = number,
            isWatched = watched,
            airDate = airDate
        )

    @Test
    fun `entries are grouped by day and ordered soonest first`() {
        val days = HomeFeed.build(
            items = listOf(show(), movie("movie_a", day(3)), movie("movie_b", day(0))),
            episodesByShow = mapOf("tv_1" to listOf(ep(1, 1, airDate = day(1)))),
            badges = emptyMap(),
            nowMillis = now
        )

        assertEquals(listOf(day(0), day(1), day(3)), days.map { it.dateString })
    }

    @Test
    fun `multi episode drops on one day become a single entry`() {
        val days = HomeFeed.build(
            items = listOf(show()),
            episodesByShow = mapOf("tv_1" to listOf(
                ep(1, 4, airDate = day(2)),
                ep(1, 5, airDate = day(2)),
                ep(1, 6, airDate = day(2))
            )),
            badges = emptyMap(),
            nowMillis = now
        )

        assertEquals(1, days.size)
        val entry = days.first().entries.single()
        assertEquals(3, entry.episodeCount)
        assertEquals("S1 E4 +2 more", entry.kindLabel)
    }

    @Test
    fun `only the next unwatched episode is scheduled`() {
        val days = HomeFeed.build(
            items = listOf(show()),
            episodesByShow = mapOf("tv_1" to listOf(
                ep(1, 1, watched = true, airDate = day(-30)),
                ep(1, 2, watched = true, airDate = day(-20)),
                ep(1, 3, airDate = day(2))
            )),
            badges = emptyMap(),
            nowMillis = now
        )

        assertEquals("S1 E3", days.single().entries.single().kindLabel)
    }

    @Test
    fun `past releases are excluded`() {
        val days = HomeFeed.build(
            items = listOf(movie("movie_old", day(-5)), movie("movie_new", day(2))),
            episodesByShow = emptyMap(),
            badges = emptyMap(),
            nowMillis = now
        )

        assertEquals(listOf("movie_new"), days.flatMap { it.entries }.map { it.media.id })
    }

    @Test
    fun `releases beyond the horizon are excluded`() {
        val days = HomeFeed.build(
            items = listOf(movie("movie_far", day(120))),
            episodesByShow = emptyMap(),
            badges = emptyMap(),
            nowMillis = now,
            daysAhead = 30
        )

        assertTrue(days.isEmpty())
    }

    @Test
    fun `completed and soft deleted titles are excluded`() {
        val days = HomeFeed.build(
            items = listOf(
                MediaItemEntity(id = "movie_done", title = "done", type = "Movie",
                    state = "Completed", releaseDate = day(1)),
                MediaItemEntity(id = "movie_gone", title = "gone", type = "Movie",
                    state = "Wishlist", releaseDate = day(1), isSoftDeleted = true)
            ),
            episodesByShow = emptyMap(),
            badges = emptyMap(),
            nowMillis = now
        )

        assertTrue(days.isEmpty())
    }

    @Test
    fun `a caught up show falls back to its premiere only when unreleased`() {
        // All aired episodes watched and the show released long ago -> nothing.
        val released = HomeFeed.build(
            items = listOf(show()),
            episodesByShow = mapOf("tv_1" to listOf(ep(1, 1, watched = true, airDate = day(-100)))),
            badges = emptyMap(),
            nowMillis = now
        )
        assertTrue(released.isEmpty())

        // Unreleased show -> its premiere shows up.
        val unreleased = HomeFeed.build(
            items = listOf(show()),
            episodesByShow = mapOf("tv_1" to listOf(ep(1, 1, watched = true, airDate = day(-100)))),
            badges = emptyMap(),
            nowMillis = now
        )
        assertTrue(unreleased.isEmpty())
    }

    @Test
    fun `episode groups are sorted by season then episode`() {
        val days = HomeFeed.build(
            items = listOf(show()),
            episodesByShow = mapOf("tv_1" to listOf(
                ep(2, 1, airDate = day(1)),
                ep(1, 10, airDate = day(1)),
                ep(1, 2, airDate = day(1))
            )),
            badges = emptyMap(),
            nowMillis = now
        )

        assertEquals("S1 E2 +2 more", days.single().entries.single().kindLabel)
    }

    @Test
    fun `empty inputs produce no days`() {
        assertTrue(HomeFeed.build(emptyList(), emptyMap(), emptyMap(), now).isEmpty())
    }

    @Test
    fun `resume candidates are capped`() {
        val many = (1..30).map { show("tv_$it") }
        assertEquals(5, HomeFeed.resumeCandidates(many, emptyMap(), limit = 5).size)
    }
}
