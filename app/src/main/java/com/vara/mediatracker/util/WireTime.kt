package com.vara.mediatracker.util

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Date

/**
 * The one place mobile timestamps are produced and bounded.
 *
 * Five separate call sites used to format timestamps as
 * `SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").format(Date())` — which prints **local** wall-clock
 * time and appends a literal `Z`, so every stamp was wrong by the device's UTC offset. The server
 * took those at face value: a row edited here arrived up to hours in the future, which made it
 * permanently newer than anything the Mac did afterwards, so the Mac's later edit could never win
 * and the bogus value was re-pushed on every sync. (Four rows in the cloud had already been
 * poisoned, and the incremental-pull watermark is derived from the same stamps.)
 *
 * `DateTimeFormatter` is immutable and thread-safe, unlike `SimpleDateFormat`.
 */
object WireTime {

    private val formatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /** Current time in UTC, millisecond precision — the wire format every synced stamp uses. */
    fun now(): String = formatter.format(Instant.now())

    /**
     * Bounds a parsed stamp to the present.
     *
     * An edit cannot postdate the write that carried it, so a future value is by definition a bad
     * clock rather than a real edit. Clamping does not "restore" the lost ordering — the server's
     * one-time repair anchored the affected rows to their write time for that — it removes the
     * unbounded advantage, so the next genuine edit on the other device wins normally.
     */
    fun clampToNow(value: Date, now: Date = Date()): Date = if (value.after(now)) now else value
}
