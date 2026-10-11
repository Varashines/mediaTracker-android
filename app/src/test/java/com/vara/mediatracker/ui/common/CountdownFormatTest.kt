package com.vara.mediatracker.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Countdown label formatting.
 *
 * The function takes an explicit `nowMillis` precisely so these are deterministic —
 * the production bug this guards against was `Date()` being sampled once inside a
 * `remember` block and then frozen on screen.
 */
class CountdownFormatTest {

    private val now = Date()
    private val sdf = SimpleDateFormat("MMM d", Locale.getDefault())

    private fun inMinutes(minutes: Long): String =
        formatCountdownTimer(Date(now.time + minutes * 60_000L), now.time)

    private fun inHours(hours: Long): String =
        formatCountdownTimer(Date(now.time + hours * 3_600_000L), now.time)

    private fun inDays(days: Long): String =
        formatCountdownTimer(Date(now.time + days * 86_400_000L), now.time)

    @Test
    fun `under an hour renders in minutes`() {
        assertEquals("in 1m", inMinutes(1))
        assertEquals("in 5m", inMinutes(5))
        assertEquals("in 59m", inMinutes(59))
    }

    @Test
    fun `a target seconds away never rounds down to zero`() {
        // Zero millis ahead has already arrived, so it reads Today rather than "in 0m".
        assertEquals("Today", inMinutes(0))
        assertEquals("in 1m", formatCountdownTimer(Date(now.time + 1), now.time))
        assertEquals("in 1m", formatCountdownTimer(Date(now.time + 59_000), now.time))
    }

    @Test
    fun `under a day renders in hours`() {
        assertEquals("in 1h", inHours(1))
        assertEquals("in 2h", inHours(2))
        assertEquals("in 23h", inHours(23))
    }

    @Test
    fun `under sixty days renders in days`() {
        assertEquals("in 1d", inDays(1))
        assertEquals("in 2d", inDays(2))
        assertEquals("in 59d", inDays(59))
    }

    @Test
    fun `sixty one days out falls back to a calendar date`() {
        val target = Date(now.time + 61L * 86_400_000L)
        assertEquals(sdf.format(target), formatCountdownTimer(target, now.time))
    }

    @Test
    fun `a past or present target renders as Today`() {
        assertEquals("Today", formatCountdownTimer(Date(now.time - 1), now.time))
        assertEquals("Today", formatCountdownTimer(Date(now.time), now.time))
    }

    @Test
    fun `labels advance monotonically as time passes`() {
        val target = Date(now.time + 90L * 60_000L)
        assertEquals("in 1h", formatCountdownTimer(target, now.time))
        assertEquals("in 1h", formatCountdownTimer(target, now.time + 30L * 60_000L))
        assertEquals("in 30m", formatCountdownTimer(target, now.time + 60L * 60_000L))
        assertEquals("Today", formatCountdownTimer(target, now.time + 120L * 60_000L))
    }

    @Test
    fun `minute and hour boundaries use the coarser unit at the boundary`() {
        // 60 minutes is exactly one hour, not "60m"
        assertEquals("in 1h", inMinutes(60))
        // 24 hours is exactly one day, not "24h"
        assertEquals("in 1d", inHours(24))
    }
}