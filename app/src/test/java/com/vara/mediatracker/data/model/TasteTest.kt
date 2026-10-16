package com.vara.mediatracker.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the single taste vocabulary both apps must speak.
 *
 * Android previously wrote and pushed "Loved"/"Liked"/"Disliked" while macOS's `TasteValue`
 * enum only recognises "Love"/"Like"/"Dislike" — so a like set here reached the Mac as an
 * unparseable string and every taste-driven feature there ignored it (badge gating, Loved
 * filters, affinity). These tests lock the canonical values, the legacy aliases that must keep
 * reading correctly, and the signal the one-off heal uses.
 */
class TasteTest {

    @Test
    fun `canonical values match the macOS enum and the column default`() {
        assertEquals("None", Taste.NONE)
        assertEquals("Like", Taste.LIKE)
        assertEquals("Love", Taste.LOVE)
        assertEquals("Dislike", Taste.DISLIKE)
    }

    @Test
    fun `normalize maps legacy participles onto canonical values`() {
        assertEquals(Taste.LOVE, Taste.normalize("Loved"))
        assertEquals(Taste.LIKE, Taste.normalize("Liked"))
        assertEquals(Taste.DISLIKE, Taste.normalize("Disliked"))
    }

    @Test
    fun `normalize is case and whitespace insensitive`() {
        assertEquals(Taste.LOVE, Taste.normalize("loved"))
        assertEquals(Taste.LOVE, Taste.normalize("LOVE"))
        assertEquals(Taste.LIKE, Taste.normalize("  Like  "))
    }

    @Test
    fun `normalize leaves canonical values untouched`() {
        assertEquals(Taste.NONE, Taste.normalize("None"))
        assertEquals(Taste.LIKE, Taste.normalize("Like"))
        assertEquals(Taste.LOVE, Taste.normalize("Love"))
        assertEquals(Taste.DISLIKE, Taste.normalize("Dislike"))
    }

    @Test
    fun `normalize treats unknown and null input as unrated`() {
        assertEquals(Taste.NONE, Taste.normalize(null))
        assertEquals(Taste.NONE, Taste.normalize(""))
        assertEquals(Taste.NONE, Taste.normalize("Favourite"))
    }

    @Test
    fun `predicates accept both spellings`() {
        assertTrue(Taste.isLove("Loved"))
        assertTrue(Taste.isLove("Love"))
        assertFalse(Taste.isLove("Liked"))

        assertTrue(Taste.isLike("Liked"))
        assertTrue(Taste.isLike("Like"))
        assertFalse(Taste.isLike("Loved"))

        assertTrue(Taste.isDislike("Disliked"))
        assertTrue(Taste.isDislike("Dislike"))

        assertTrue(Taste.isLikedOrLoved("Loved"))
        assertTrue(Taste.isLikedOrLoved("Like"))
        assertFalse(Taste.isLikedOrLoved("Disliked"))
        assertFalse(Taste.isLikedOrLoved("None"))
    }

    @Test
    fun `isLegacy only flags values the heal must rewrite`() {
        assertTrue(Taste.isLegacy("Loved"))
        assertTrue(Taste.isLegacy("Liked"))
        assertTrue(Taste.isLegacy("Disliked"))
        assertFalse(Taste.isLegacy("Love"))
        assertFalse(Taste.isLegacy("None"))
        assertFalse(Taste.isLegacy(null))
    }

    @Test
    fun `every legacy value normalizes to something different`() {
        for (legacy in Taste.LEGACY_VALUES) {
            assertTrue("$legacy should be healed", Taste.isLegacy(legacy))
        }
    }
}
