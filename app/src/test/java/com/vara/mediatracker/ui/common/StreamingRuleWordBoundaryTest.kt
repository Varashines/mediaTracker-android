package com.vara.mediatracker.ui.common

import org.junit.Assert.assertEquals
import org.junit.Ignore
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Specification for the streaming-rule matching fix — **not yet implemented**.
 *
 * [DateUtilsTest] asserts today's substring behaviour so the bug stays visible. These
 * tests state the intended behaviour and are ignored until rule matching switches from
 * `query.contains(pattern)` to word-boundary matching (plus network metadata from TMDB
 * instead of scraping the title/overview string).
 *
 * When fixing, delete the two `substring matching currently misroutes...` cases from
 * [DateUtilsTest] and drop the @Ignore annotations here.
 */
class StreamingRuleWordBoundaryTest {

    private fun hourIn(date: java.util.Date, tz: String): Int =
        Calendar.getInstance(TimeZone.getTimeZone(tz)).apply { time = date }.get(Calendar.HOUR_OF_DAY)

    private fun dayIn(date: java.util.Date, tz: String): Int =
        Calendar.getInstance(TimeZone.getTimeZone(tz)).apply { time = date }.get(Calendar.DAY_OF_MONTH)

    @Ignore("Rule matching is still a raw substring test; 'maximum' contains 'max'.")
    @Test
    fun `words merely containing a network name use the default rule`() {
        val result = parseTargetAirDateTime("2026-05-01", "Maximum Overdrive")!!
        assertEquals(20, hourIn(result, "America/New_York")) // default 8pm ET
        assertEquals(1, dayIn(result, "America/New_York")) // no day shift
    }

    @Ignore("Rule matching is still a raw substring test; 'pineapple' contains 'apple'.")
    @Test
    fun `words merely containing apple use the default rule`() {
        val result = parseTargetAirDateTime("2026-05-01", "Pineapple Express")!!
        assertEquals(20, hourIn(result, "America/New_York"))
        assertEquals(1, dayIn(result, "America/New_York"))
    }

    @Ignore("Same root cause: 'maximum' contains 'max'.")
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