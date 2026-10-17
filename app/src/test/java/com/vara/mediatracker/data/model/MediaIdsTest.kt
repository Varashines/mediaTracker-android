package com.vara.mediatracker.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the canonical identifier format.
 *
 * Two incompatible episode formats existed historically, which let the same episode be
 * inserted twice. These tests pin the single format every producer must emit.
 */
class MediaIdsTest {

    @Test
    fun `buildEpisodeUniqueId emits canonical format`() {
        assertEquals("tv_1399_s1_e1", MediaIds.buildEpisodeUniqueId("tv_1399", 1, 1))
    }

    @Test
    fun `buildEpisodeUniqueId accepts a bare tmdb show id`() {
        assertEquals("tv_1399_s1_e1", MediaIds.buildEpisodeUniqueId("1399", 1, 1))
    }

    @Test
    fun `buildEpisodeUniqueId handles double digit season and episode`() {
        assertEquals("tv_1399_s10_e10", MediaIds.buildEpisodeUniqueId("tv_1399", 10, 10))
        assertEquals("tv_1399_s2_e22", MediaIds.buildEpisodeUniqueId("tv_1399", 2, 22))
    }

    @Test
    fun `tvShowKey normalizes both accepted forms`() {
        assertEquals("tv_1399", MediaIds.tvShowKey("tv_1399"))
        assertEquals("tv_1399", MediaIds.tvShowKey("1399"))
    }

    @Test
    fun `tmdbIdOf extracts the numeric id`() {
        assertEquals(1399, MediaIds.tmdbIdOf("tv_1399"))
        assertEquals(550, MediaIds.tmdbIdOf("movie_550"))
        assertEquals(1399, MediaIds.tmdbIdOf("1399"))
    }

    @Test
    fun `tmdbIdOf returns zero when unparseable`() {
        assertEquals(0, MediaIds.tmdbIdOf("not-an-id"))
        assertEquals(0, MediaIds.tmdbIdOf(""))
    }

    @Test
    fun `isCanonicalEpisodeId accepts only the canonical shape`() {
        assertTrue(MediaIds.isCanonicalEpisodeId("tv_1399_s1_e1"))
        assertFalse(MediaIds.isCanonicalEpisodeId("1399_1_1")) // legacy
        assertFalse(MediaIds.isCanonicalEpisodeId("tv_1399_s1_e")) // truncated
        assertFalse(MediaIds.isCanonicalEpisodeId("tv_1399_e1_s1")) // swapped
        assertFalse(MediaIds.isCanonicalEpisodeId("nonsense"))
        assertFalse(MediaIds.isCanonicalEpisodeId(""))
    }

    @Test
    fun `parseEpisodeUniqueId reads the canonical format`() {
        assertEquals(EpisodeKey("tv_1399", 2, 5), MediaIds.parseEpisodeUniqueId("tv_1399_s2_e5"))
    }

    @Test
    fun `parseEpisodeUniqueId reads the legacy format`() {
        assertEquals(EpisodeKey("tv_1399", 1, 1), MediaIds.parseEpisodeUniqueId("1399_1_1"))
        assertEquals(EpisodeKey("tv_1399", 10, 10), MediaIds.parseEpisodeUniqueId("1399_10_10"))
    }

    @Test
    fun `parseEpisodeUniqueId rejects unrecognized shapes`() {
        assertNull(MediaIds.parseEpisodeUniqueId("weird_id"))
        assertNull(MediaIds.parseEpisodeUniqueId("tv_abc_s1_e1"))
        assertNull(MediaIds.parseEpisodeUniqueId(""))
    }

    @Test
    fun `legacy ids round trip to the same canonical id`() {
        listOf("1399_1_1", "1399_10_10", "1399_2_22").forEach { legacy ->
            val parsed = MediaIds.parseEpisodeUniqueId(legacy)!!
            val canonical = MediaIds.buildEpisodeUniqueId(parsed.showId, parsed.seasonNumber, parsed.episodeNumber)
            assertEquals("round trip mismatch for $legacy", parsed, MediaIds.parseEpisodeUniqueId(canonical))
            assertTrue(MediaIds.isCanonicalEpisodeId(canonical))
        }
    }

    @Test
    fun `canonical ids round trip unchanged`() {
        listOf("tv_1399_s1_e1", "tv_1399_s10_e10", "tv_603_s3_e7").forEach { canonical ->
            val parsed = MediaIds.parseEpisodeUniqueId(canonical)!!
            assertEquals(canonical, MediaIds.buildEpisodeUniqueId(parsed.showId, parsed.seasonNumber, parsed.episodeNumber))
        }
    }

    @Test
    fun `canonicalEpisodeIdOrNull collapses both spellings`() {
        assertEquals("tv_293610_s1_e3", MediaIds.canonicalEpisodeIdOrNull("293610_1_3"))
        assertEquals("tv_293610_s1_e3", MediaIds.canonicalEpisodeIdOrNull("tv_293610_s1_e3"))
    }

    @Test
    fun `canonicalEpisodeIdOrNull keeps null and unknown values`() {
        assertNull(MediaIds.canonicalEpisodeIdOrNull(null))
        assertEquals("movie_550", MediaIds.canonicalEpisodeIdOrNull("movie_550"))
    }
}
