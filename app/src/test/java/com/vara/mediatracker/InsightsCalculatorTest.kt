package com.vara.mediatracker

import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.data.model.WatchEventEntity
import com.vara.mediatracker.ui.m3e.InsightsCalculator
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightsCalculatorTest {

    private fun item(id: String, taste: String = "None", state: String = "Wishlist", genres: String? = null) =
        MediaItemEntity(id = id, title = id, type = "Movie", state = state, taste = taste, genres = genres)

    private fun event(id: String, watchedAt: String, runtime: Int? = 100, voided: String? = null) =
        WatchEventEntity(
            id = id,
            cycleId = "c",
            mediaId = "m",
            watchedAt = watchedAt,
            runtimeMinutes = runtime,
            voidedAt = voided,
            deduplicationKey = id
        )

    @Test
    fun `voided events do not count`() {
        val result = InsightsCalculator.compute(
            items = emptyList(),
            events = listOf(event("a", "2026-10-01T10:00:00Z"), event("b", "2026-10-02T10:00:00Z", voided = "x")),
            today = YearMonth.of(2026, 10)
        )
        // The voided one is gone; the live one is a film event, so it counts as time, not an episode.
        assertEquals(0, result.episodesWatched)
        assertEquals(100, result.minutesWatched)
    }

    @Test
    fun `genres are split and counted once per title`() {
        val result = InsightsCalculator.compute(
            items = listOf(
                item("a", genres = "Drama, Thriller"),
                item("b", genres = "Drama"),
                item("c", genres = "Drama, Drama")
            ),
            events = emptyList()
        )
        assertEquals(listOf("Drama" to 3, "Thriller" to 1), result.topGenres.map { it.name to it.count })
    }

    @Test
    fun `months bucket by the day watched`() {
        val result = InsightsCalculator.compute(
            items = emptyList(),
            events = listOf(
                event("a", "2026-09-30T23:00:00Z"),
                event("b", "2026-10-01T01:00:00Z"),
                event("c", "2025-01-01T01:00:00Z")
            ),
            months = 3,
            today = YearMonth.of(2026, 10)
        )
        assertEquals(3, result.byMonth.size)
        assertEquals(1, result.byMonth.last().watches)
        assertEquals(1, result.byMonth[result.byMonth.size - 2].watches)
        assertTrue(result.byMonth.none { it.watches > 1 })
    }
}
