package com.vara.mediatracker.ui.smarthub

import com.vara.mediatracker.data.model.MediaItemEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Locks the Smart Hub membership rules to the macOS `MediaFilterPredicates` behaviour.
 */
class SmartCategoryMatcherTest {

    private fun at(text: String): Date =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(text)!!

    private fun item(
        state: String = "Watching",
        taste: String = "None",
        type: String = "TV Show",
        releaseDate: String? = null,
        nextAiringInstant: String? = null,
        network: String? = null
    ) = MediaItemEntity(
        id = "tv_1",
        title = "Title",
        type = type,
        state = state,
        taste = taste,
        releaseDate = releaseDate,
        nextAiringInstant = nextAiringInstant,
        network = network
    )

    private fun matches(
        category: SmartCategory,
        item: MediaItemEntity,
        badge: String? = null,
        runtime: Int? = null,
        epRuntime: Int? = null,
        now: Date = at("2026-08-20")
    ) = SmartCategoryMatcher.matches(category, item, badge, runtime, epRuntime, now)

    // ---------- badge-driven categories ----------

    @Test
    fun `loved matches the love taste`() {
        assertTrue(matches(SmartCategory.LOVED, item(taste = "Love")))
        assertTrue(matches(SmartCategory.LOVED, item(taste = "Loved")))
        assertFalse(matches(SmartCategory.LOVED, item(taste = "Like")))
        assertFalse(matches(SmartCategory.LOVED, item(taste = "None")))
    }

    @Test
    fun `archive matches on hold and dropped`() {
        assertTrue(matches(SmartCategory.ARCHIVE, item(state = "On Hold")))
        assertTrue(matches(SmartCategory.ARCHIVE, item(state = "Dropped")))
        assertFalse(matches(SmartCategory.ARCHIVE, item(state = "Active")))
    }

    @Test
    fun `binge matches binge and binge drop badges`() {
        assertTrue(matches(SmartCategory.BINGE, item(), badge = "BINGE"))
        assertTrue(matches(SmartCategory.BINGE, item(), badge = "BINGE DROP"))
        assertFalse(matches(SmartCategory.BINGE, item(), badge = "NEW"))
    }

    @Test
    fun `catch up matches the behind badge`() {
        assertTrue(matches(SmartCategory.CATCH_UP, item(), badge = "BEHIND"))
        assertFalse(matches(SmartCategory.CATCH_UP, item(), badge = "BINGE"))
    }

    @Test
    fun `premiere radar matches the premiere badge`() {
        assertTrue(matches(SmartCategory.SMART_UPCOMING, item(), badge = "PREMIERE"))
        assertFalse(matches(SmartCategory.SMART_UPCOMING, item(), badge = "NEW"))
    }

    @Test
    fun `soft deleted items never match`() {
        val deleted = item(taste = "Love").copy(isSoftDeleted = true)
        assertFalse(matches(SmartCategory.LOVED, deleted))
    }

    // ---------- release radar needs a radar badge AND an aired date ----------

    @Test
    fun `release radar requires a radar badge`() {
        val aired = item(releaseDate = "2026-08-10")
        assertTrue(matches(SmartCategory.RELEASE_RADAR, aired, badge = "NEW"))
        assertTrue(matches(SmartCategory.RELEASE_RADAR, aired, badge = "FINALE"))
        // SOON is not a radar badge.
        assertFalse(matches(SmartCategory.RELEASE_RADAR, aired, badge = "SOON"))
        assertFalse(matches(SmartCategory.RELEASE_RADAR, aired, badge = null))
    }

    @Test
    fun `release radar requires the air date to be in the past`() {
        val future = item(releaseDate = "2026-09-01")
        assertFalse(matches(SmartCategory.RELEASE_RADAR, future, badge = "NEW"))

        val past = item(releaseDate = "2026-08-10")
        assertTrue(matches(SmartCategory.RELEASE_RADAR, past, badge = "NEW"))
    }

    @Test
    fun `release radar prefers the precise airing instant`() {
        // releaseDate is in the future, but the stored instant has already passed.
        val it = item(releaseDate = "2026-09-01", nextAiringInstant = "2026-08-19T01:00:00Z")
        assertTrue(matches(SmartCategory.RELEASE_RADAR, it, badge = "PREMIERE"))
    }

    // ---------- quick bites ----------

    @Test
    fun `quick bites matches short movies`() {
        val movie = item(type = "Movie")
        assertTrue(matches(SmartCategory.QUICK_BITES, movie, runtime = 80))
        assertTrue(matches(SmartCategory.QUICK_BITES, movie, runtime = 89))
        assertFalse(matches(SmartCategory.QUICK_BITES, movie, runtime = 90))
        assertFalse(matches(SmartCategory.QUICK_BITES, movie, runtime = null))
    }

    @Test
    fun `quick bites matches short-episode shows`() {
        val show = item(type = "TV Show")
        assertTrue(matches(SmartCategory.QUICK_BITES, show, epRuntime = 30))
        assertTrue(matches(SmartCategory.QUICK_BITES, show, epRuntime = 35))
        assertFalse(matches(SmartCategory.QUICK_BITES, show, epRuntime = 40))
    }

    @Test
    fun `quick bites excludes dropped, on hold and disliked`() {
        val movie = item(type = "Movie")
        assertFalse(matches(SmartCategory.QUICK_BITES, movie.copy(state = "Dropped"), runtime = 80))
        assertFalse(matches(SmartCategory.QUICK_BITES, movie.copy(state = "On Hold"), runtime = 80))
        assertFalse(matches(SmartCategory.QUICK_BITES, movie.copy(taste = "Dislike"), runtime = 80))
    }

    // ---------- on this week (macOS cross-year week match) ----------

    @Test
    fun `on this week matches a prior-year release falling in the current week`() {
        // 2026-08-20 is a Thursday; its week includes Aug 20. A 2020 release on the
        // same month/day lands in that week but a different year.
        val releasedLastYears = item(type = "Movie", releaseDate = "2020-08-20")
        assertTrue(matches(SmartCategory.ON_THIS_WEEK, releasedLastYears))
    }

    @Test
    fun `on this week ignores same-year releases`() {
        // The macOS rule has a deliberate cross-year guard.
        val thisYear = item(type = "Movie", releaseDate = "2026-08-20")
        assertFalse(matches(SmartCategory.ON_THIS_WEEK, thisYear))
    }

    @Test
    fun `on this week ignores releases outside the current week`() {
        val farOff = item(type = "Movie", releaseDate = "2020-08-03")
        assertFalse(matches(SmartCategory.ON_THIS_WEEK, farOff))
    }

    @Test
    fun `on this week needs a release date`() {
        assertFalse(matches(SmartCategory.ON_THIS_WEEK, item(type = "Movie", releaseDate = null)))
    }
}
