package com.vara.mediatracker.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.Date
import java.util.TimeZone

/**
 * Pins the two properties the sync depends on: stamps are real UTC, and a future stamp is bounded.
 *
 * The regression this guards: timestamps were formatted as
 * `SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").format(Date())` — local wall-clock time with a
 * literal `Z`, in five separate places. Every row edited in this app reached the cloud wrong by the
 * device's UTC offset (hours, not minutes), which made it permanently newer than anything the Mac
 * did afterwards and dragged the incremental-pull watermark along with it.
 */
class WireTimeTest {

    private lateinit var originalZone: TimeZone

    @Before
    fun useNonUtcZone() {
        originalZone = TimeZone.getDefault()
        // Any non-zero offset exposes the bug; the old formatter was wrong by exactly this much.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
    }

    @After
    fun restoreZone() {
        TimeZone.setDefault(originalZone)
    }

    @Test
    fun `now emits real UTC even when the device is not in UTC`() {
        val before = Instant.now()
        val parsed = Instant.parse(WireTime.now())
        val after = Instant.now()

        assertFalse("stamp must not be in the future", parsed.isAfter(after.plusSeconds(1)))
        assertFalse(
            "stamp must not be offset by the device timezone",
            parsed.isBefore(before.minusSeconds(1))
        )
    }

    @Test
    fun `now is parseable ISO-8601 with a Z suffix`() {
        val stamp = WireTime.now()
        assertTrue("expected a Z suffix, got $stamp", stamp.endsWith("Z"))
        // Throws if the value is not a valid instant.
        Instant.parse(stamp)
    }

    @Test
    fun `format matches the wire precision used by the sync`() {
        val stamp = WireTime.now()
        assertTrue("expected millisecond precision, got $stamp", Regex("""\.\d{3}Z$""").containsMatchIn(stamp))
    }

    @Test
    fun `clampToNow leaves past values untouched`() {
        val now = Date()
        val past = Date(now.time - 3_600_000)
        assertEquals(past, WireTime.clampToNow(past, now))
    }

    @Test
    fun `clampToNow bounds a future value`() {
        val now = Date()
        val future = Date(now.time + 4 * 3_600_000)
        assertEquals(now, WireTime.clampToNow(future, now))
    }

    /**
     * The Hokum case at the level of the comparison that decided it: the phone stamped an edit four
     * hours ahead, so the Mac's later edit could never win and the bogus value was re-pushed on
     * every sync.
     */
    @Test
    fun `a future stamped edit no longer outranks a later remote edit`() {
        val now = Date()
        val phoneStamp = Date(now.time + 4 * 3_600_000)
        val macEdit = Date(now.time + 60_000)

        assertFalse("before the clamp the remote never won", macEdit.after(phoneStamp))
        assertTrue("after the clamp the later edit wins", macEdit.after(WireTime.clampToNow(phoneStamp, now)))
    }

    @Test
    fun `the same instant is the same watch`() {
        val t = Date()
        assertTrue(WireTime.isSameWatch(t, t))
    }

    @Test
    fun `an offset shift is the same watch`() {
        // The Doctor X pair: the correct stamp and the +5:30 one from the old formatter.
        val correct = Date(1_761_000_000_000)
        val mislabelled = Date(correct.time + (5 * 60 + 30) * 60 * 1000)
        assertTrue(WireTime.isSameWatch(correct, mislabelled))
    }

    @Test
    fun `a rewatch the next day is a different watch`() {
        val first = Date(1_761_000_000_000)
        assertFalse(WireTime.isSameWatch(first, Date(first.time + 24 * 60 * 60 * 1000)))
    }

    @Test
    fun `stampsDiffer tolerates wire precision but catches real differences`() {
        val t = Date(1_761_000_000_000)
        assertFalse(WireTime.stampsDiffer(t, t))
        assertFalse(WireTime.stampsDiffer(t, Date(t.time + 300)))       // ms vs micros rounding
        assertFalse(WireTime.stampsDiffer(t, Date(t.time + 1000)))      // exactly the tolerance
        assertTrue(WireTime.stampsDiffer(t, Date(t.time + 2000)))
    }

    @Test
    fun `stampsDiffer treats null and a real stamp as a difference`() {
        assertFalse(WireTime.stampsDiffer(null, null))
        assertTrue(WireTime.stampsDiffer(null, Date()))
        assertTrue(WireTime.stampsDiffer(Date(), null))
    }

    @Test
    fun `a date-only correction is a change worth applying`() {
        val local = Date(1_761_000_000_000)
        val corrected = Date(1_761_003_600_000)
        assertTrue(
            WireTime.episodeNeedsUpdate(
                isWatched = true, remoteIsWatched = true,
                watchedDate = local, remoteWatchedDate = corrected
            )
        )
    }

    @Test
    fun `an unchanged episode is not worth applying`() {
        val d = Date(1_761_000_000_000)
        assertFalse(
            WireTime.episodeNeedsUpdate(
                isWatched = true, remoteIsWatched = true,
                watchedDate = d, remoteWatchedDate = Date(d.time + 120)
            )
        )
    }

    @Test
    fun `a flag change is always worth applying`() {
        assertTrue(
            WireTime.episodeNeedsUpdate(
                isWatched = false, remoteIsWatched = true, watchedDate = null, remoteWatchedDate = null
            )
        )
        assertTrue(
            WireTime.episodeNeedsUpdate(
                isWatched = true, remoteIsWatched = false, watchedDate = Date(), remoteWatchedDate = null
            )
        )
        assertFalse(
            WireTime.episodeNeedsUpdate(
                isWatched = false, remoteIsWatched = false, watchedDate = null, remoteWatchedDate = null
            )
        )
    }

    @Test
    fun `remoteEditTime prefers the edit stamp`() {
        val now = Date()
        val edit = Date(now.time - 600_000)
        val write = Date(now.time - 30_000)
        assertEquals(edit, WireTime.remoteEditTime(edit, write, now))
    }

    @Test
    fun `remoteEditTime falls back to the write stamp`() {
        val now = Date()
        val write = Date(now.time - 30_000)
        assertEquals(write, WireTime.remoteEditTime(null, write, now))
    }

    @Test
    fun `remoteEditTime is empty when neither stamp exists`() {
        assertEquals(Date(0), WireTime.remoteEditTime(null, null, Date()))
    }

    /** The bug the macOS scenario harness found, in the same rule: a stamp hours ahead used to
     *  outrank a genuinely later local edit. */
    @Test
    fun `a future stamped edit is capped by the row write time`() {
        val now = Date()
        val bogusEdit = Date(now.time + 4 * 3_600_000)
        val write = Date(now.time - 60_000)
        assertEquals(
            "an edit cannot postdate the write that carried it",
            Date(now.time - 60_000),
            WireTime.remoteEditTime(bogusEdit, write, now)
        )
    }

    @Test
    fun `a future stamped edit with no write stamp is only bounded by now`() {
        val now = Date()
        val bogusEdit = Date(now.time + 4 * 3_600_000)
        assertEquals(now, WireTime.remoteEditTime(bogusEdit, null, now))
    }
}
