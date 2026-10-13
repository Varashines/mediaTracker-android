package com.vara.mediatracker.ui.home

import com.vara.mediatracker.data.badge.BadgeResult
import com.vara.mediatracker.data.badge.SmartBadge
import com.vara.mediatracker.data.model.EpisodeEntity
import com.vara.mediatracker.data.model.MediaIds
import com.vara.mediatracker.data.model.MediaItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Ported macOS Home rules. These decide what the user sees *and* what they never see,
 * so the edge cases are pinned individually.
 */
class HomeEligibilityTest {

    // Fixed instant so the elapsed-time windows are deterministic.
    private val now: Date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .parse("2026-10-12 12:00")!!
    private val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)

    private fun day(offset: Long) =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now.time + offset * 86_400_000L))

    private fun item(
        id: String = "tv_1",
        state: String = "Active",
        type: String = "TV Show",
        release: String? = null,
        softDeleted: Boolean = false,
        taste: String = "Like",
        lastInteraction: String? = null
    ) = MediaItemEntity(
        id = id, title = id, type = type, state = state, taste = taste,
        releaseDate = release, isSoftDeleted = softDeleted,
        lastInteractionDate = lastInteraction
    )

    private fun ep(
        season: Int = 1, number: Int = 1, watched: Boolean = false, airDate: String? = null
    ) = EpisodeEntity(
        uniqueId = MediaIds.buildEpisodeUniqueId("tv_1", season, number),
        showId = "tv_1", seasonNumber = season, episodeNumber = number,
        isWatched = watched, airDate = airDate
    )

    private fun eligible(
        item: MediaItemEntity,
        badge: SmartBadge? = null,
        eps: List<EpisodeEntity> = emptyList()
    ) = HomeEligibility.isContinueWatchingCandidate(item, badge, eps, now)

    // ---------- New badge set ----------

    @Test
    fun `new set is new premiere and binge drop only`() {
        assertEquals(
            setOf(SmartBadge.NEW, SmartBadge.PREMIERE, SmartBadge.BINGE_DROP),
            HomeEligibility.NEW_BADGES
        )
        // FINALE and HOOKED are not "new" — finale says a season is ending,
        // hooked describes binge behaviour.
        assertFalse(SmartBadge.FINALE in HomeEligibility.NEW_BADGES)
        assertFalse(SmartBadge.HOOKED in HomeEligibility.NEW_BADGES)
    }

    // ---------- CW priority ----------

    @Test
    fun `priority matches the macOS weights`() {
        assertEquals(110, HomeEligibility.cwPriority(item(), SmartBadge.PREMIERE, false))
        assertEquals(100, HomeEligibility.cwPriority(item(), SmartBadge.NEW, false))
        assertEquals(95, HomeEligibility.cwPriority(item(), SmartBadge.FINALE, false))
        assertEquals(90, HomeEligibility.cwPriority(item(), SmartBadge.BINGE_DROP, false))
        assertEquals(85, HomeEligibility.cwPriority(item(), SmartBadge.HOOKED, false))
        assertEquals(80, HomeEligibility.cwPriority(item(), null, true))
        assertEquals(80, HomeEligibility.cwPriority(item(state = "Active"), null, false))
        assertEquals(0, HomeEligibility.cwPriority(item(state = "Wishlist"), null, false))
    }

    // ---------- CW eligibility ----------

    @Test
    fun `finished states are excluded`() {
        listOf("Completed", "Dropped", "On Hold").forEach { st ->
            assertFalse("$st must be excluded", eligible(item(state = st), SmartBadge.NEW))
        }
    }

    @Test
    fun `active show with something to resume qualifies`() {
        // macOS requires recent activity for an explicitly-Active title with no
        // watched episodes, so it carries a recent interaction date.
        assertTrue(eligible(item(lastInteraction = "2026-10-10T00:00:00Z"), null, listOf(ep(watched = false, airDate = day(-3)))))
    }

    @Test
    fun `caught up show waiting on a future episode is excluded`() {
        // Every aired episode watched, and something still to air: that is a Coming
        // Soon concern, not something to resume.
        val eps = listOf(
            ep(1, 1, watched = true, airDate = day(-30)),
            ep(1, 2, watched = true, airDate = day(-20)),
            ep(1, 3, watched = false, airDate = day(5))
        )
        assertFalse(eligible(item(), null, eps))
    }

    @Test
    fun `caught up show with nothing unaired still qualifies`() {
        val eps = listOf(ep(1, 1, watched = true, airDate = day(-30)))
        assertTrue(eligible(item(), null, eps))
    }

    @Test
    fun `never started wishlist title only qualifies just after dropping`() {
        val justDropped = item(state = "Wishlist", release = day(-2))
        assertTrue(eligible(justDropped, SmartBadge.NEW))

        val stale = item(state = "Wishlist", release = day(-20))
        assertFalse(eligible(stale, SmartBadge.NEW))

        val future = item(state = "Wishlist", release = day(10))
        assertFalse(eligible(future, SmartBadge.NEW))
    }

    @Test
    fun `active wishlist title with no badge and nothing watched does not qualify`() {
        assertFalse(eligible(item(state = "Wishlist"), null))
    }

    @Test
    fun `soft deleted titles never qualify`() {
        assertFalse(eligible(item(softDeleted = true), SmartBadge.PREMIERE))
    }

    @Test
    fun `unreleased movie is not resumable`() {
        assertFalse(eligible(item(id = "movie_1", state = "Wishlist", type = "Movie", release = day(30)), null))
    }

    // ---------- Coming Soon ----------

    @Test
    fun `coming soon needs a future air date`() {
        assertTrue(HomeEligibility.isComingSoon(item(), day(3), today))
        assertFalse(HomeEligibility.isComingSoon(item(), day(-3), today))
        assertFalse(HomeEligibility.isComingSoon(item(), null, today))
    }

    @Test
    fun `completed titles are never coming soon`() {
        assertFalse(HomeEligibility.isComingSoon(item(state = "Completed"), day(3), today))
    }

    @Test
    fun `soft deleted titles are never coming soon`() {
        assertFalse(HomeEligibility.isComingSoon(item(softDeleted = true), day(3), today))
    }

    @Test
    fun `next airing date is the first unwatched episode not the next unaired`() {
        // Regression: "Between Steps" had an unwatched episode that already aired
        // plus later unaired ones. Taking the next *unaired* date put it in
        // Coming Soon; macOS takes the first unwatched episode, whose date has
        // passed, so the title stays in In Progress.
        val eps = listOf(
            ep(1, 1, watched = true, airDate = day(-20)),
            ep(1, 2, watched = false, airDate = day(-5)),
            ep(1, 3, watched = false, airDate = day(4))
        )
        assertEquals(day(-5), HomeEligibility.nextAiringDate(eps, today))
        assertFalse(HomeEligibility.isComingSoon(item(), HomeEligibility.nextAiringDate(eps, today), today))
    }

    @Test
    fun `caught up show falls through to the next unaired episode`() {
        // Nothing unwatched, so the unaired fallback applies and it belongs in
        // Coming Soon.
        val eps = listOf(
            ep(1, 1, watched = true, airDate = day(-20)),
            ep(1, 2, watched = false, airDate = day(6))
        )
        assertEquals(day(6), HomeEligibility.nextAiringDate(eps, today))
        assertTrue(HomeEligibility.isComingSoon(item(), HomeEligibility.nextAiringDate(eps, today), today))
    }

    @Test
    fun `unwatched episode with no air date falls back to the unaired one`() {
        val eps = listOf(
            ep(1, 1, watched = true, airDate = day(-20)),
            ep(1, 2, watched = false, airDate = null),
            ep(1, 3, watched = false, airDate = day(9))
        )
        assertEquals(day(9), HomeEligibility.nextAiringDate(eps, today))
    }

    @Test
    fun `next airing date is null when everything has aired and is watched`() {
        val eps = listOf(ep(1, 1, watched = true, airDate = day(-10)))
        assertNull(HomeEligibility.nextAiringDate(eps, today))
    }

    @Test
    fun `unwatched aired detection ignores future episodes`() {
        val eps = listOf(
            ep(1, 1, watched = false, airDate = day(-5)),
            ep(1, 2, watched = false, airDate = day(9))
        )
        assertTrue(HomeEligibility.hasUnwatchedAired(eps, today))

        val allFuture = listOf(ep(1, 1, watched = false, airDate = day(9)))
        assertFalse(HomeEligibility.hasUnwatchedAired(allFuture, today))
    }

    // ---------- assembly ----------

    @Test
    fun `coming soon excludes dropped on hold and disliked`() {
        // macOS applies these when building the Home pool, so such a title never
        // reaches Coming Soon even when it has a future air date.
        val soon = day(10)
        fun cs(state: String = "Active", taste: String = "Like") =
            HomeEligibility.isComingSoon(item(state = state, taste = taste), soon, day(0))
        assertFalse(cs(state = "Dropped"))
        assertFalse(cs(state = "On Hold"))
        assertFalse(cs(state = "Completed"))
        assertFalse(cs(taste = "Dislike"))
        assertTrue(cs(state = "Wishlist"))
        assertTrue(cs(state = "Active"))
    }

    @Test
    fun `continue watching splits cleanly into new and in progress`() {
        val items = listOf(
            item(id = "tv_premiere"),
            item(id = "tv_hooked"),
            item(id = "tv_plain"),
            item(id = "tv_binge")
        )
        val badges = mapOf(
            "tv_premiere" to BadgeResult(SmartBadge.PREMIERE),
            "tv_hooked" to BadgeResult(SmartBadge.HOOKED)
        )

        val (newItems, inProgress) = HomeEligibility.splitNewAndInProgress(items, badges)

        assertEquals(listOf("tv_premiere"), newItems.map { it.id })
        // HOOKED is not "new", so it lands in In Progress.
        assertEquals(setOf("tv_hooked", "tv_plain", "tv_binge"), inProgress.map { it.id }.toSet())
    }

    @Test
    fun `resume candidates are ordered by priority then recency`() {
        val eps = listOf(ep(watched = false, airDate = day(-2)))
        val items = listOf(
            item(id = "tv_plain", lastInteraction = "2026-10-10T00:00:00Z"),
            item(id = "tv_new", lastInteraction = "2026-01-01T00:00:00Z")
        )
        val badges = mapOf("tv_new" to BadgeResult(SmartBadge.NEW))

        val ranked = HomeEligibility.resumeCandidates(items, mapOf("tv_1" to eps), badges, now)

        // NEW outranks a plain active title despite far older interaction.
        assertEquals(listOf("tv_new", "tv_plain"), ranked.map { it.id })
    }

    @Test
    fun `resume candidates exclude ineligible titles`() {
        val items = listOf(item(state = "Completed"), item(state = "Wishlist"))
        assertTrue(HomeEligibility.resumeCandidates(items, emptyMap(), emptyMap(), now).isEmpty())
    }

    @Test
    fun `resume candidates are capped`() {
        val eps = listOf(ep(watched = false, airDate = day(-2)))
        val items = (1..40).map { item(id = "tv_$it", lastInteraction = "2026-10-11T00:00:00Z") }
        val byShow = (1..40).associate { "tv_$it" to eps }
        assertEquals(20, HomeEligibility.resumeCandidates(items, byShow, emptyMap(), now).size)
    }
}