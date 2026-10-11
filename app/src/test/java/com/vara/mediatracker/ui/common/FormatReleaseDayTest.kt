package com.vara.mediatracker.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class FormatReleaseDayTest {

    private val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun at(text: String): Long = sdf.parse(text)!!.time

    @Test
    fun `today and tomorrow are labelled relatively`() {
        val now = at("2026-10-12")
        assertEquals("Today", formatReleaseDay("2026-10-12", now))
        assertEquals("Tomorrow", formatReleaseDay("2026-10-13", now))
    }

    @Test
    fun `yesterday is not labelled relatively`() {
        // Only the two near-future days get relative labels; a past date reads better
        // spelled out alongside the release list.
        assertEquals("Fri 9 Oct", formatReleaseDay("2026-10-09", at("2026-10-12")))
    }

    @Test
    fun `distant dates are spelled out`() {
        val now = at("2026-10-12")
        assertEquals("Wed 14 Oct", formatReleaseDay("2026-10-14", now))
        assertEquals("Mon 2 Nov", formatReleaseDay("2026-11-02", now))
    }

    @Test
    fun `blank and malformed input yields an empty label`() {
        val now = at("2026-10-12")
        assertEquals("", formatReleaseDay(null, now))
        assertEquals("", formatReleaseDay("", now))
        assertEquals("", formatReleaseDay("2026", now))
    }

    @Test
    fun `trailing timestamp content is tolerated`() {
        val now = at("2026-10-12")
        assertEquals("Today", formatReleaseDay("2026-10-12T18:00:00Z", now))
    }
}
