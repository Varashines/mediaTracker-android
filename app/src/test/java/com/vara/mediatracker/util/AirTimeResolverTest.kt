package com.vara.mediatracker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

/**
 * Locks the shared air-time resolver to the macOS `DateUtils.parseEpisodeDate` order.
 *
 * Each case names the branch it exercises; if a branch is reordered the failure should
 * say which one, not just produce a surprising instant.
 */
class AirTimeResolverTest {

    private fun hourIn(date: Date, tz: String): Int =
        Calendar.getInstance(TimeZone.getTimeZone(tz)).apply { time = date }.get(Calendar.HOUR_OF_DAY)

    private fun dayIn(date: Date, tz: String): Int =
        Calendar.getInstance(TimeZone.getTimeZone(tz)).apply { time = date }.get(Calendar.DAY_OF_MONTH)

    private fun monthIn(date: Date, tz: String): Int =
        Calendar.getInstance(TimeZone.getTimeZone(tz)).apply { time = date }.get(Calendar.MONTH)

    // ---------- branch 1: YouTube airstamp wins ----------

    @Test
    fun `youtube uses the airstamp verbatim`() {
        val d = AirTimeResolver.resolveAirInstant(
            dateString = "2026-08-24",
            airstamp = "2026-08-24T12:00:00+00:00",
            serviceName = "YouTube"
        )!!
        assertEquals(12, hourIn(d, "UTC"))
    }

    // ---------- branch 2: streaming-service rules ----------

    @Test
    fun `apple tv drops 9pm ET the evening before`() {
        val d = AirTimeResolver.resolveAirInstant("2026-01-15", serviceName = "Apple TV+")!!
        assertEquals(21, hourIn(d, "America/New_York"))
        assertEquals(14, dayIn(d, "America/New_York")) // eve of the 15th
        assertEquals(Calendar.JANUARY, monthIn(d, "America/New_York"))
    }

    @Test
    fun `marvel drops 9pm ET on the listed date`() {
        val d = AirTimeResolver.resolveAirInstant("2026-03-10", serviceName = "Marvel Studios")!!
        assertEquals(21, hourIn(d, "America/New_York"))
        assertEquals(10, dayIn(d, "America/New_York"))
    }

    @Test
    fun `netflix drops midnight pacific`() {
        val d = AirTimeResolver.resolveAirInstant("2026-06-01", serviceName = "Netflix")!!
        assertEquals(0, hourIn(d, "America/Los_Angeles"))
        assertEquals(1, dayIn(d, "America/Los_Angeles"))
    }

    @Test
    fun `fx drops the following morning`() {
        val d = AirTimeResolver.resolveAirInstant("2026-02-10", serviceName = "FX")!!
        assertEquals(0, hourIn(d, "America/New_York"))
        assertEquals(11, dayIn(d, "America/New_York"))
    }

    @Test
    fun `max and hbo both drop 9pm ET`() {
        assertEquals(21, hourIn(AirTimeResolver.resolveAirInstant("2026-05-01", serviceName = "HBO")!!, "America/New_York"))
        assertEquals(21, hourIn(AirTimeResolver.resolveAirInstant("2026-05-01", serviceName = "Max")!!, "America/New_York"))
    }

    // ---------- branch 3: real TVMaze airtime + show timezone ----------

    @Test
    fun `real airtime is interpreted in the show timezone`() {
        val d = AirTimeResolver.resolveAirInstant(
            dateString = "2026-08-24",
            time = "21:00",
            timezone = "America/New_York",
            // A rule that would otherwise claim this show must be ignored: a genuine
            // broadcast airtime takes precedence over the streaming heuristic.
            serviceName = "HBO"
        )!!
        assertEquals(25, dayIn(d, "Asia/Tokyo")) // 2026-08-25 10:00 JST
        assertEquals(10, hourIn(d, "Asia/Tokyo"))
    }

    @Test
    fun `real airstamp for a broadcast show is used when no airtime`() {
        val d = AirTimeResolver.resolveAirInstant(
            dateString = "2026-08-24",
            airstamp = "2026-08-24T01:00:00+00:00",
            serviceName = "Some Broadcast Net"
        )!!
        assertEquals(1, hourIn(d, "UTC"))
    }

    // ---------- branch 4: noon placeholder is skipped ----------

    @Test
    fun `noon UTC placeholder falls through to the service rule`() {
        // TVMaze stamps streaming originals at noon UTC; that is not the real time.
        val d = AirTimeResolver.resolveAirInstant(
            dateString = "2026-08-24",
            airstamp = "2026-08-24T12:00:00+00:00",
            serviceName = "Netflix"
        )!!
        assertEquals(0, hourIn(d, "America/Los_Angeles"))
        assertEquals(24, dayIn(d, "America/Los_Angeles"))
    }

    @Test
    fun `placeholder without a rule falls back to 8pm ET`() {
        val d = AirTimeResolver.resolveAirInstant(
            dateString = "2026-08-24",
            airstamp = "2026-08-24T12:00:00+00:00"
        )!!
        assertEquals(20, hourIn(d, "America/New_York"))
    }

    // ---------- branch 5: timezone + time fallback ----------

    @Test
    fun `timezone fallback defaults to 8pm local when no time`() {
        val d = AirTimeResolver.resolveAirInstant(
            dateString = "2026-08-24",
            timezone = "Europe/London"
        )!!
        assertEquals(20, hourIn(d, "Europe/London"))
        assertEquals(24, dayIn(d, "Europe/London"))
    }

    // ---------- branch 6: US 8pm ET default ----------

    @Test
    fun `unknown service falls back to 8pm ET`() {
        val d = AirTimeResolver.resolveAirInstant("2026-04-20", serviceName = "Some Indie Film")!!
        assertEquals(20, hourIn(d, "America/New_York"))
        assertEquals(20, dayIn(d, "America/New_York"))
    }

    // ---------- input handling ----------

    @Test
    fun `blank and short dates return null`() {
        assertNull(AirTimeResolver.resolveAirInstant(null))
        assertNull(AirTimeResolver.resolveAirInstant(""))
        assertNull(AirTimeResolver.resolveAirInstant("   "))
        assertNull(AirTimeResolver.resolveAirInstant("2026"))
    }

    @Test
    fun `date is derived from the airstamp when the date string is missing`() {
        val d = AirTimeResolver.resolveAirInstant(
            dateString = null,
            airstamp = "2026-08-24T01:00:00+00:00"
        )!!
        assertEquals(24, dayIn(d, "UTC"))
    }

    @Test
    fun `resolution is independent of the device timezone`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            val tokyo = AirTimeResolver.resolveAirInstant("2026-01-15", serviceName = "Marvel")!!
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            val la = AirTimeResolver.resolveAirInstant("2026-01-15", serviceName = "Marvel")!!
            assertEquals(tokyo.time, la.time)
        } finally {
            TimeZone.setDefault(original)
        }
    }

    // ---------- formatAirTime ----------

    @Test
    fun `formatAirTime renders the service drop time and zone`() {
        val label = AirTimeResolver.formatAirTime("2026-01-15", "Netflix")!!
        assertTrue("expected midnight PT, got $label", label.contains("12:00 AM"))
        val apple = AirTimeResolver.formatAirTime("2026-01-15", "Apple TV+")!!
        assertTrue("expected 9 PM ET, got $apple", apple.contains("9:00 PM"))
    }

    @Test
    fun `formatAirTime returns null for a missing date`() {
        assertNull(AirTimeResolver.formatAirTime(null, "Netflix"))
        assertNull(AirTimeResolver.formatAirTime("2026", "Netflix"))
    }
}
