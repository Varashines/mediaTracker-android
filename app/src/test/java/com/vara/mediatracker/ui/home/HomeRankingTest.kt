package com.vara.mediatracker.ui.home

import com.vara.mediatracker.data.badge.BadgeResult
import com.vara.mediatracker.data.badge.SmartBadge
import com.vara.mediatracker.data.model.MediaItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Continue Watching ranking and the hero-row split.
 *
 * The reported symptom was "the first 5 are fixed and never change even when the
 * overall order changes". Since the hero row is a plain `take` of the ranked list,
 * a pinned row can only mean the ranking is stale — so the ranking is pinned here
 * instead.
 */
class HomeRankingTest {

    private fun item(id: String, lastInteraction: String? = null, state: String = "Watching") =
        MediaItemEntity(
            id = id,
            title = id,
            type = "TV Show",
            state = state,
            lastInteractionDate = lastInteraction
        )

    private fun badges(vararg pairs: Pair<String, SmartBadge>): Map<String, BadgeResult> =
        pairs.associate { (id, badge) -> id to BadgeResult(badge) }

    @Test
    fun `badge priority orders time sensitive badges above engagement ones`() {
        assertTrue(HomeRanking.badgePriority(SmartBadge.PREMIERE) > HomeRanking.badgePriority(SmartBadge.NEW))
        assertTrue(HomeRanking.badgePriority(SmartBadge.NEW) > HomeRanking.badgePriority(SmartBadge.FINALE))
        assertTrue(HomeRanking.badgePriority(SmartBadge.FINALE) > HomeRanking.badgePriority(SmartBadge.BINGE_DROP))
        assertTrue(HomeRanking.badgePriority(SmartBadge.BINGE_DROP) > HomeRanking.badgePriority(SmartBadge.HOOKED))
        assertTrue(HomeRanking.badgePriority(SmartBadge.HOOKED) > HomeRanking.badgePriority(SmartBadge.BEHIND))
        assertTrue(HomeRanking.badgePriority(SmartBadge.BEHIND) > HomeRanking.badgePriority(SmartBadge.BINGE))
        assertTrue(HomeRanking.badgePriority(SmartBadge.BINGE) > HomeRanking.badgePriority(SmartBadge.SOON))
        assertTrue(HomeRanking.badgePriority(SmartBadge.SOON) > HomeRanking.badgePriority(null))
    }

    @Test
    fun `premiere outranks everything else`() {
        val candidates = listOf(
            item("tv_binge", "2026-10-01T00:00:00Z"),
            item("tv_premiere", "2020-01-01T00:00:00Z")
        )
        val badges = badges("tv_binge" to SmartBadge.BINGE, "tv_premiere" to SmartBadge.PREMIERE)

        assertEquals("tv_premiere", HomeRanking.rank(candidates, badges).first().id)
    }

    @Test
    fun `unbadged titles fall back to recency rather than input order`() {
        // Everything ties at priority 60, so the tie-break has to decide. If it did
        // not, the hero row would look arbitrarily stuck.
        val candidates = listOf(
            item("tv_old", "2026-01-01T00:00:00Z"),
            item("tv_new", "2026-10-01T00:00:00Z"),
            item("tv_mid", "2026-05-01T00:00:00Z")
        )

        assertEquals(
            listOf("tv_new", "tv_mid", "tv_old"),
            HomeRanking.rank(candidates, emptyMap()).map { it.id }
        )
    }

    @Test
    fun `items with no timestamps still order deterministically`() {
        val candidates = listOf(item("tv_c"), item("tv_a"), item("tv_b"))

        val ranked = HomeRanking.rank(candidates, emptyMap()).map { it.id }

        assertEquals(3, ranked.size)
        assertEquals(ranked, HomeRanking.rank(candidates, emptyMap()).map { it.id })
    }

    @Test
    fun `hero row re-ranks when a badge changes`() {
        val candidates = listOf(
            item("tv_a", "2026-10-01T00:00:00Z"),
            item("tv_b", "2026-09-01T00:00:00Z"),
            item("tv_c", "2026-08-01T00:00:00Z"),
            item("tv_d", "2026-07-01T00:00:00Z"),
            item("tv_e", "2026-06-01T00:00:00Z"),
            item("tv_f", "2026-05-01T00:00:00Z")
        )

        val before = HomeRanking.split(HomeRanking.rank(candidates, emptyMap())).first
        assertEquals("tv_a", before.first().id)

        // tv_e takes a PREMIERE badge and must jump to the front of the hero row.
        val after = HomeRanking.split(
            HomeRanking.rank(candidates, badges("tv_e" to SmartBadge.PREMIERE))
        ).first
        assertEquals("tv_e", after.first().id)
        assertTrue("the previous leader should have been pushed out", before.first().id != after.first().id)
    }

    @Test
    fun `split gives exactly five to the hero row and the rest below`() {
        val candidates = (1..12).map { item("tv_$it") }

        val (top, rest) = HomeRanking.split(HomeRanking.rank(candidates, emptyMap()))

        assertEquals(HomeRanking.TOP_ROW_SIZE, top.size)
        assertEquals(7, rest.size)
        assertEquals(12, top.size + rest.size)
    }

    @Test
    fun `split never overlaps the two rows`() {
        val candidates = (1..9).map { item("tv_$it") }

        val (top, rest) = HomeRanking.split(HomeRanking.rank(candidates, emptyMap()))

        assertTrue("no title may appear in both rows", top.none { t -> rest.any { it.id == t.id } })
    }

    @Test
    fun `titles moving between rows on a re-rank are not duplicated or dropped`() {
        val candidates = (1..20).map { item("tv_$it", "2026-01-01T00:00:00.000%02dZ".format(it)) }

        val before = HomeRanking.split(HomeRanking.rank(candidates, emptyMap()))
        val after = HomeRanking.split(
            HomeRanking.rank(candidates, badges("tv_20" to SmartBadge.PREMIERE))
        )

        assertEquals(candidates.map { it.id }.toSet(), (before.first + before.second).map { it.id }.toSet())
        assertEquals(candidates.map { it.id }.toSet(), (after.first + after.second).map { it.id }.toSet())
    }

    @Test
    fun `a title promoted past the hero row boundary leaves the lower row`() {
        val candidates = (1..10).map { item("tv_$it") }
        val before = HomeRanking.split(HomeRanking.rank(candidates, emptyMap()))

        // tv_10 was last, therefore in the lower row.
        assertTrue(before.second.any { it.id == "tv_10" })

        val after = HomeRanking.split(
            HomeRanking.rank(candidates, badges("tv_10" to SmartBadge.PREMIERE))
        )

        assertTrue(after.first.any { it.id == "tv_10" })
        assertTrue(after.second.none { it.id == "tv_10" })
    }

    @Test
    fun `rail is capped`() {
        val candidates = (1..100).map { item("tv_$it") }

        assertEquals(HomeRanking.RAIL_LIMIT, HomeRanking.rank(candidates, emptyMap()).size)
    }

    @Test
    fun `empty input produces empty rows`() {
        val (top, rest) = HomeRanking.split(HomeRanking.rank(emptyList(), emptyMap()))
        assertTrue(top.isEmpty())
        assertTrue(rest.isEmpty())
    }

    @Test
    fun `fewer than five titles puts them all in the hero row`() {
        val candidates = listOf(item("tv_1"), item("tv_2"))

        val (top, rest) = HomeRanking.split(HomeRanking.rank(candidates, emptyMap()))

        assertEquals(2, top.size)
        assertTrue(rest.isEmpty())
    }
    @Test
    fun `in progress titles outrank badge only watchlist entries`() {
        // Regression: a Wishlist item with a PREMIERE badge used to outrank everything
        // the user was actually watching, evicting in-progress titles from the rail.
        val candidates = listOf(
            item("tv_wishlist_premiere", "2026-10-10T00:00:00Z", state = "Wishlist"),
            item("tv_watching", "2026-01-01T00:00:00Z", state = "Active")
        )
        val badges = badges("tv_wishlist_premiere" to SmartBadge.PREMIERE)

        val ranked = HomeRanking.rank(candidates, badges)

        assertEquals("tv_watching", ranked.first().id)
    }

    @Test
    fun `badge priority still breaks ties within the in progress group`() {
        val candidates = listOf(
            item("tv_a", "2026-10-10T00:00:00Z"),
            item("tv_b", "2026-10-01T00:00:00Z")
        )
        val badges = badges("tv_b" to SmartBadge.PREMIERE)

        assertEquals("tv_b", HomeRanking.rank(candidates, badges).first().id)
    }

    @Test
    fun `in progress group still orders by recency`() {
        val candidates = listOf(
            item("tv_old", "2026-01-01T00:00:00Z"),
            item("tv_new", "2026-10-01T00:00:00Z")
        )

        assertEquals("tv_new", HomeRanking.rank(candidates, emptyMap()).first().id)
    }

    @Test
    fun `active state detection covers the macOS state names`() {
        listOf("Watching", "watching", "Active", "ACTIVE", "In Progress", "in progress")
            .forEach { assertTrue("'$it' should count as in progress", HomeRanking.isActivelyInProgress(item("tv_1", state = it))) }
        listOf("Wishlist", "Completed", "Dropped", "On Hold", "Paused", "Re-watching")
            .forEach { assertTrue("'$it' should not count as in progress", !HomeRanking.isActivelyInProgress(item("tv_1", state = it))) }
    }
}
