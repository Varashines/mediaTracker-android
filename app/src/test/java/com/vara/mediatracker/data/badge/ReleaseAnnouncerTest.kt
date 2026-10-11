package com.vara.mediatracker.data.badge

import com.vara.mediatracker.data.sync.ReleaseAnnouncer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deduplication for release notifications.
 *
 * The badge table refreshes on a 15-minute schedule; without de-duplication every
 * tick would re-alert on anything still sitting in a badge window.
 */
class ReleaseAnnouncerTest {

    @Test
    fun keyIsStableForTheSameBadgeWindow() {
        val a = ReleaseAnnouncer.key("tv_1399", SmartBadge.SOON, "2026-03-01")
        val b = ReleaseAnnouncer.key("tv_1399", SmartBadge.SOON, "2026-03-01")
        assertEquals(a, b)
    }

    @Test
    fun keyDistinguishesDifferentBadges() {
        val soon = ReleaseAnnouncer.key("tv_1399", SmartBadge.SOON, "2026-03-01")
        val new = ReleaseAnnouncer.key("tv_1399", SmartBadge.NEW, "2026-03-01")
        assertTrue(soon != new)
    }

    @Test
    fun keyDistinguishesDifferentTargetDates() {
        val first = ReleaseAnnouncer.key("tv_1399", SmartBadge.SOON, "2026-03-01")
        val next = ReleaseAnnouncer.key("tv_1399", SmartBadge.SOON, "2026-03-08")
        assertTrue(first != next)
    }

    @Test
    fun alreadyAnnouncedItemsAreSuppressed() {
        val candidates = setOf("a", "b", "c")
        val already = setOf("b")

        assertEquals(setOf("a", "c"), ReleaseAnnouncer.filterNew(candidates, already))
    }

    @Test
    fun nothingIsAnnouncedTwiceInARow() {
        val fresh = setOf("a", "b")

        val remembered = ReleaseAnnouncer.remember(emptySet(), fresh)

        assertTrue(ReleaseAnnouncer.filterNew(fresh, remembered).isEmpty())
    }

    @Test
    fun rememberGrowsTheHistory() {
        val remembered = ReleaseAnnouncer.remember(setOf("a"), setOf("b", "c"))
        assertEquals(setOf("a", "b", "c"), remembered)
    }

    @Test
    fun historyIsBounded() {
        val huge = (1..5_000).map { "key_$it" }.toSet()

        val remembered = ReleaseAnnouncer.remember(emptySet(), huge)

        assertTrue("history must stay bounded, was ${remembered.size}", remembered.size <= 400)
    }

    @Test
    fun onlyTimeSensitiveBadgesNotify() {
        assertEquals(
            setOf(SmartBadge.SOON, SmartBadge.PREMIERE, SmartBadge.NEW),
            ReleaseAnnouncer.NOTIFIABLE
        )
        // Engagement and milestone badges are context, not events — they must not alert.
        assertTrue(SmartBadge.BINGE !in ReleaseAnnouncer.NOTIFIABLE)
        assertTrue(SmartBadge.HOOKED !in ReleaseAnnouncer.NOTIFIABLE)
        assertTrue(SmartBadge.FINALE !in ReleaseAnnouncer.NOTIFIABLE)
    }
}