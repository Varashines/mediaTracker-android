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

    /** A single viewing logged from two devices can differ by up to a device's timezone offset. */
    private const val SAME_WATCH_WINDOW_MILLIS = 12L * 60 * 60 * 1000

    private val utcDay: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)

    /**
     * Whether two stamps describe the same viewing — the ledger's duplicate rule, mirroring
     * macOS's `SyncTimestamps.isSameWatch`.
     *
     * Same UTC day, or within twelve hours. A copy stamped from local wall-clock time (the bug
     * that made stamps land hours ahead) can cross midnight, so comparing calendar days in the
     * device's zone splits one viewing into two; a deliberate rewatch is a day or more apart and
     * stays distinct.
     */
    fun isSameWatch(a: Date, b: Date): Boolean {
        if (kotlin.math.abs(a.time - b.time) <= SAME_WATCH_WINDOW_MILLIS) return true
        return utcDay.format(a.toInstant()) == utcDay.format(b.toInstant())
    }

    /** Parses a wire stamp, bounding anything in the future to now. */
    fun parseInstant(value: String?): Date? =
        value?.let { runCatching { Date.from(Instant.parse(it)) }.getOrNull() }
            ?.let { clampToNow(it) }

    /**
     * A cloud row's edit time — the stamp every conflict is decided on.
     *
     * An edit cannot postdate the write that carried it, so when the two disagree the write time is
     * the better evidence: the old rule took `last_interaction_date` at face value, which let a
     * device with a fast clock keep its rows permanently newer (the last edit here could never
     * win, and the bogus value was pushed back every sync). Mirrors macOS's
     * `SyncTimestamps.remoteEditTime`, including the write-time cap and the fallback for rows that
     * never carried an edit stamp.
     */
    fun remoteEditTime(lastInteraction: Date?, updatedAt: Date?, now: Date = Date()): Date {
        val candidate = when {
            lastInteraction != null && updatedAt != null -> minOf(lastInteraction, updatedAt)
            lastInteraction != null -> lastInteraction
            updatedAt != null -> updatedAt
            else -> Date(0)
        }
        return clampToNow(candidate, now)
    }

    /** Wire stamps carry millisecond-to-microsecond precision depending on the writer. */
    private const val EDIT_TIME_TOLERANCE_MILLIS = 1000L

    /** Whether two stamps differ by more than a second; `null` against a real stamp differs. */
    fun stampsDiffer(a: Date?, b: Date?): Boolean = when {
        a == null && b == null -> false
        a == null || b == null -> true
        else -> kotlin.math.abs(a.time - b.time) > EDIT_TIME_TOLERANCE_MILLIS
    }

    /**
     * Whether a pulled episode row differs from the local one in a way worth applying.
     *
     * A corrected watch *date* counts, not just the watched flag: the date drives the streak and
     * the monthly numbers, and it used to be editable only on the device that made the change.
     * Mirrors macOS's `SyncTimestamps.episodeNeedsUpdate`.
     */
    fun episodeNeedsUpdate(
        isWatched: Boolean,
        remoteIsWatched: Boolean,
        watchedDate: Date?,
        remoteWatchedDate: Date?
    ): Boolean {
        if (isWatched != remoteIsWatched) return true
        if (!isWatched) return false
        return stampsDiffer(watchedDate, remoteWatchedDate)
    }
}
