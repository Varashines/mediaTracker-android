package com.vara.mediatracker.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Rule matching is anchored to whole words: a title merely *containing* a network name
 * ("Maximum Overdrive", "Pineapple Express") must fall back to the default 8 PM ET rule,
 * while a genuine mention ("HBO Max", "Apple TV+") still selects its rule.
 */
class StreamingRuleWordBoundaryTest {

    private fun hourIn(date: java.util.Date, tz: String): Int =
        Calendar.getInstance(TimeZone.getTimeZone(tz)).apply { time = date }.get(Calendar.HOUR_OF_DAY)

    private fun dayIn(date: java.util.Date, tz: String): Int =
        Calendar.getInstance(TimeZone.getTimeZone(tz)).apply { time = date }.get(Calendar.DAY_OF_MONTH)

    @Test
    fun `words merely containing a network name use the default rule`() {
        val result = parseTargetAirDateTime("2026-05-01", "Maximum Overdrive")!!
        assertEquals(20, hourIn(result, "America/New_York")) // default 8pm ET
        assertEquals(1, dayIn(result, "America/New_York")) // no day shift
    }

    @Test
    fun `words merely containing apple use the default rule`() {
        val result = parseTargetAirDateTime("2026-05-01", "Pineapple Express")!!
        assertEquals(20, hourIn(result, "America/New_York"))
        assertEquals(1, dayIn(result, "America/New_York"))
    }

    @Test
    fun `genuine network mentions still resolve`() {
        assertEquals(
            21,
            hourIn(parseTargetAirDateTime("2026-05-01", "HBO Max original")!!, "America/New_York")
        )
        assertEquals(
            21,
            hourIn(parseTargetAirDateTime("2026-05-01", "Streaming on Apple TV+")!!, "America/New_York")
        )
    }
}