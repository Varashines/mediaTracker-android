package com.vara.mediatracker.ui.home

import com.vara.mediatracker.data.badge.BadgeResult
import com.vara.mediatracker.data.badge.SmartBadge
import com.vara.mediatracker.data.model.MediaItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Home rails must partition the library — no title in two rails at once, which
 * was the previous behaviour and read as the screen repeating itself.
 */
class HomeSectionsTest {

    private fun item(id: String, state: String = "Watching") =
        MediaItemEntity(id = id, title = id, type = "TV Show", state = state)

    private fun badges(vararg pairs: Pair<String, SmartBadge>): Map<String, BadgeResult> =
        pairs.associate { (id, badge) -> id to BadgeResult(badge) }

    @Test
    fun `premiere and new badges go to the new rail`() {
        val readyNow = listOf(item("tv_a"), item("tv_b"), item("tv_c"))
        val badges = badges("tv_a" to SmartBadge.PREMIERE, "tv_b" to SmartBadge.NEW)

        val sections = HomeSectionsBuilder.build(readyNow, readyNow, badges)

        assertEquals(listOf("tv_a", "tv_b"), sections.newReleases.map { it.id })
        assertEquals(listOf("tv_c"), sections.continueWatching.map { it.id })
    }

    @Test
    fun `other badges stay out of the new rail`() {
        val readyNow = listOf(item("tv_a"), item("tv_b"), item("tv_c"))
        val badges = badges(
            "tv_a" to SmartBadge.SOON,
            "tv_b" to SmartBadge.BINGE,
            "tv_c" to SmartBadge.FINALE
        )

        val sections = HomeSectionsBuilder.build(readyNow, readyNow, badges)

        assertTrue(sections.newReleases.isEmpty())
        assertEquals(3, sections.continueWatching.size)
    }

    @Test
    fun `leftover in progress titles fill the third rail`() {
        val readyNow = listOf(item("tv_ready_1"), item("tv_ready_2"))
        val allInProgress = readyNow + item("tv_waiting_1") + item("tv_waiting_2")
        val badges = badges("tv_ready_1" to SmartBadge.PREMIERE)

        val sections = HomeSectionsBuilder.build(readyNow, allInProgress, badges)

        assertEquals(listOf("tv_ready_1"), sections.newReleases.map { it.id })
        assertEquals(listOf("tv_ready_2"), sections.continueWatching.map { it.id })
        assertEquals(listOf("tv_waiting_1", "tv_waiting_2"), sections.inProgress.map { it.id })
    }

    @Test
    fun `no title appears in more than one rail`() {
        val all = (1..30).map { item("tv_$it") }
        val badges = badges(
            "tv_1" to SmartBadge.PREMIERE,
            "tv_2" to SmartBadge.NEW,
            "tv_3" to SmartBadge.FINALE,
            "tv_4" to SmartBadge.SOON
        )

        val sections = HomeSectionsBuilder.build(all, all, badges)
        val ids = (sections.newReleases + sections.continueWatching + sections.inProgress).map { it.id }

        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `rails respect the cap`() {
        val all = (1..50).map { item("tv_$it") }
        val badges = badges("tv_1" to SmartBadge.PREMIERE)

        val sections = HomeSectionsBuilder.build(all, all, badges, limit = 10)

        assertTrue(sections.newReleases.size <= 10)
        assertTrue(sections.continueWatching.size <= 10)
        assertTrue(sections.inProgress.size <= 10)
    }

    @Test
    fun `an empty library produces empty rails`() {
        val sections = HomeSectionsBuilder.build(emptyList(), emptyList(), emptyMap())
        assertTrue(sections.isEmpty)
        assertEquals(0, sections.titleCount)
    }

    @Test
    fun `a library with nothing ready still shows in progress`() {
        val waiting = listOf(item("tv_1"), item("tv_2"))

        val sections = HomeSectionsBuilder.build(emptyList(), waiting, emptyMap())

        assertTrue(sections.continueWatching.isEmpty())
        assertEquals(2, sections.inProgress.size)
    }

    @Test
    fun `an item placed in the new rail is not repeated in progress`() {
        val readyNow = listOf(item("tv_premiere"))
        val badges = badges("tv_premiere" to SmartBadge.PREMIERE)

        val sections = HomeSectionsBuilder.build(readyNow, readyNow, badges)

        assertEquals(listOf("tv_premiere"), sections.newReleases.map { it.id })
        assertTrue(sections.inProgress.none { it.id == "tv_premiere" })
    }
}
