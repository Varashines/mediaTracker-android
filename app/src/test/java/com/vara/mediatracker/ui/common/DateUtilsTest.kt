package com.vara.mediatracker.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Streaming-service release timing rules.
 *
 * The rules ported from macOS DateUtils are timezone-aware, so these tests pin both
 * the rule selection and the resulting UTC instant — a hardcoded offset would pass a
 * naive "date only" check but fail these.
 */
class DateUtilsTest {

    private fun utcHourOf(date: Date): Int {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { time = date }
        return cal.get(Calendar.HOUR_OF_DAY)
    }

    // ---------- rule selection ----------

    @Test
    fun `apple content drops at 9pm ET on the eve of release`() {
        val d = parseTargetAirDateTime("2026-01-15", "Apple TV+ original")!!
        val cal = Calendar.getInstance(TimeZone.getTimeZone("America/New_York")).apply { time = d }
        assertEquals(21, cal.get(Calendar.HOUR_OF_DAY)) // still 9pm, just the previous day
        assertEquals(Calendar.JANUARY, cal.get(Calendar.MONTH))
        assertEquals(14, cal.get(Calendar.DAY_OF_MONTH)) // eve of the 15th
    }

    @Test
    fun `marvel drops at 9pm ET on the release date`() {
        val d = parseTargetAirDateTime("2026-03-10", "A Marvel Studios production")!!
        val cal = Calendar.getInstance(TimeZone.getTimeZone("America/New_York")).apply { time = d }
        assertEquals(21, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(10, cal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `netflix drops at midnight pacific`() {
        val d = parseTargetAirDateTime("2026-06-01", "Streaming on Netflix")!!
        val cal = Calendar.getInstance(TimeZone.getTimeZone("America/Los_Angeles")).apply { time = d }
        assertEquals(0, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(1, cal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `fx content airs the following morning`() {
        val d = parseTargetAirDateTime("2026-02-10", "Airing on FX")!!
        val cal = Calendar.getInstance(TimeZone.getTimeZone("America/New_York")).apply { time = d }
        assertEquals(0, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(11, cal.get(Calendar.DAY_OF_MONTH)) // dayOffset +1
    }

    @Test
    fun `unknown network falls back to 8pm ET`() {
        val d = parseTargetAirDateTime("2026-04-20", "Some indie film")!!
        val cal = Calendar.getInstance(TimeZone.getTimeZone("America/New_York")).apply { time = d }
        assertEquals(20, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(20, cal.get(Calendar.DAY_OF_MONTH))
    }

    // ---------- DST correctness ----------

    @Test
    fun `9pm ET is 02 UTC hours in winter and 01 in summer`() {
        // Standard time (EST, UTC-5) vs daylight time (EDT, UTC-4). A hardcoded
        // offset would return the same UTC hour for both dates.
        val winter = parseTargetAirDateTime("2026-01-15", "Marvel")!!
        val summer = parseTargetAirDateTime("2026-07-15", "Marvel")!!

        assertEquals(2, utcHourOf(winter))
        assertEquals(1, utcHourOf(summer))
    }

    @Test
    fun `midnight PT is 08 UTC hours in winter and 07 in summer`() {
        val winter = parseTargetAirDateTime("2026-01-15", "Netflix")!!
        val summer = parseTargetAirDateTime("2026-07-15", "Netflix")!!

        assertEquals(8, utcHourOf(winter))
        assertEquals(7, utcHourOf(summer))
    }

    @Test
    fun `parsing is independent of the device timezone`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            val inTokyo = parseTargetAirDateTime("2026-01-15", "Marvel")!!
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            val inLa = parseTargetAirDateTime("2026-01-15", "Marvel")!!
            assertEquals(inTokyo.time, inLa.time)
        } finally {
            TimeZone.setDefault(original)
        }
    }

    // ---------- input handling ----------

    @Test
    fun `null and blank dates return null`() {
        assertNull(parseTargetAirDateTime(null, "Marvel"))
        assertNull(parseTargetAirDateTime("", "Marvel"))
        assertNull(parseTargetAirDateTime("   ", "Marvel"))
    }

    @Test
    fun `short date strings return null`() {
        assertNull(parseTargetAirDateTime("2026", "Marvel"))
        assertNull(parseTargetAirDateTime("2026-01", "Marvel"))
    }

    @Test
    fun `trailing timestamp content is truncated to the date`() {
        val withTime = parseTargetAirDateTime("2026-01-15T18:45:00Z", "Marvel")!!
        val plain = parseTargetAirDateTime("2026-01-15", "Marvel")!!
        assertEquals(plain.time, withTime.time)
    }

    @Test
    fun `null network text still resolves via the default rule`() {
        assertEquals(
            parseTargetAirDateTime("2026-01-15", "Some Title")!!.time,
            parseTargetAirDateTime("2026-01-15", null)!!.time
        )
    }

    @Test
    fun `matching ignores case`() {
        assertEquals(14, Calendar.getInstance(TimeZone.getTimeZone("America/New_York")).apply {
            time = parseTargetAirDateTime("2026-01-15", "APPLE TV+")!!
        }.get(Calendar.DAY_OF_MONTH))
    }
}