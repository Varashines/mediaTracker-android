package com.vara.mediatracker.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Calendar
import java.util.Date
import java.util.Locale

// java.time formatters are immutable and thread-safe, so they are shared instead of
// allocated per call — these run once per visible card on every countdown tick.
private val MONTH_DAY_FORMAT = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
private val WEEKDAY_DAY_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
private val ISO_DAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

/**
 * Current wall-clock time, republished on a fixed interval.
 *
 * Countdown labels are derived from `Date()`, which is only sampled at composition
 * time. Memoizing them therefore froze the label — "in 5m" would sit on screen until
 * something unrelated forced a recomposition. Hosting a single ticker here and
 * publishing it through a CompositionLocal keeps every label live while still only
 * recomposing the composables that actually read it.
 *
 * Host it once, high in the tree, with [rememberNowMillis]:
 * ```
 * CompositionLocalProvider(LocalNowMillis provides rememberNowMillis()) { App() }
 * ```
 */
val LocalNowMillis = staticCompositionLocalOf { System.currentTimeMillis() }

/**
 * Emits the current epoch millis, re-sampling every [intervalMs].
 *
 * 30s is the default because the coarsest label is "in Xm"; ticking faster would burn
 * recompositions without changing what the user reads.
 */
@Composable
fun rememberNowMillis(intervalMs: Long = 30_000L): Long {
    val millis: State<Long> = produceState(initialValue = System.currentTimeMillis(), intervalMs) {
        while (true) {
            value = System.currentTimeMillis()
            delay(intervalMs)
        }
    }
    return millis.value
}

/**
 * Live countdown label for [targetDate], or null when there is no target or it has passed.
 *
 * Reads [LocalNowMillis], so it recomposes on every tick of the host ticker.
 */
@Composable
fun rememberCountdownText(targetDate: Date?): String? {
    val now = LocalNowMillis.current
    return remember(targetDate, now) {
        targetDate?.let { formatCountdownTimer(it, now) }
    }
}

/**
 * Streaming service broadcast/release timing rule matching macOS DateUtils.
 *
 * Retained as a type alias for callers that still reference the shape; the actual
 * rules and resolution now live in [com.vara.mediatracker.util.AirTimeResolver] so
 * the badge engine and the UI share one implementation.
 */
typealias StreamingServiceRule = com.vara.mediatracker.util.AirTimeResolver.StreamingServiceRule

val defaultStreamingRules = com.vara.mediatracker.util.AirTimeResolver.defaultStreamingRules

/**
 * Resolves exact Date for an airDate or releaseDate string using streaming/network rules or 8 PM ET default.
 */
fun parseTargetAirDateTime(dateStr: String?, networkOrTitle: String?): Date? =
    com.vara.mediatracker.util.AirTimeResolver.resolveAirInstant(
        dateString = dateStr,
        serviceName = networkOrTitle
    )

/**
 * Resolves the real airing instant for an episode, applying TVMaze airstamp/timezone
 * and per-service streaming rules exactly as macOS does. Prefer this over
 * [parseTargetAirDateTime] whenever episode metadata is available.
 */
fun parseAirInstant(
    dateStr: String?,
    airTime: String? = null,
    airstamp: String? = null,
    timezone: String? = null,
    serviceName: String? = null
): Date? = com.vara.mediatracker.util.AirTimeResolver.resolveAirInstant(
    dateString = dateStr,
    time = airTime,
    airstamp = airstamp,
    timezone = timezone,
    serviceName = serviceName
)

/**
 * Human-readable drop time for a release, resolved from the network's streaming rule.
 *
 * Returns e.g. "9:00 PM ET" / "12:00 AM PT", formatted in the rule's own timezone so the
 * label matches what the service advertises. Previously every card hardcoded
 * "(9:00 PM EST)", which was simply wrong for Netflix, Disney, and movies.
 */
fun formatReleaseAirTime(dateStr: String?, networkOrTitle: String?): String? =
    com.vara.mediatracker.util.AirTimeResolver.formatAirTime(dateStr, networkOrTitle)

/**
 * Generates countdown label matching macOS MediaThumbnailView.upcomingCountdownLabel:
 * "in X min", "in X hours", "in X days", or calendar date.
 */
fun formatCountdownTimer(targetDate: Date, nowMillis: Long = System.currentTimeMillis()): String {
    val diffMillis = targetDate.time - nowMillis
    if (diffMillis <= 0) return "Today"
    val diffMinutes = (diffMillis / 60000L).toInt()
    if (diffMinutes < 60) {
        return "in ${maxOf(diffMinutes, 1)}m"
    }
    val diffHours = diffMinutes / 60
    if (diffHours < 24) {
        return if (diffHours == 1) "in 1h" else "in ${diffHours}h"
    }
    val diffDays = diffHours / 24
    if (diffDays <= 60) {
        return if (diffDays == 1) "in 1d" else "in ${diffDays}d"
    }
    return MONTH_DAY_FORMAT.format(Instant.ofEpochMilli(targetDate.time).atZone(ZoneId.systemDefault()))
}
/**
 * Today's date as `yyyy-MM-dd`, recomputed when the calendar day rolls over.
 *
 * An unkeyed `remember { today }` snapshots the date at first composition and keeps
 * it until the composable leaves the tree. That silently freezes every ranking keyed
 * on it — "ready now", release windows, and sort tie-breaks — for the whole time the
 * screen stays alive, including across midnight.
 *
 * The string is memoized on the day index, so this does not reformat on every tick.
 */
@Composable
fun rememberTodayDateString(): String {
    val nowMillis = LocalNowMillis.current
    val dayIndex = Math.floorDiv(nowMillis, DAY_MILLIS)
    return remember(dayIndex) {
        ISO_DAY_FORMAT.format(Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()))
    }
}

/** A [Calendar] pinned to now, for date arithmetic that needs year/month fields. */
@Composable
fun rememberTodayCalendar(): Calendar {
    val nowMillis = LocalNowMillis.current
    val dayIndex = Math.floorDiv(nowMillis, DAY_MILLIS)
    return remember(dayIndex) { Calendar.getInstance().apply { timeInMillis = nowMillis } }
}

private const val DAY_MILLIS = 86_400_000L

/**
 * Human-readable day for a `yyyy-MM-dd` string.
 *
 * The release list previously printed the raw ISO value ("Airs on 2026-10-12"),
 * which reads like a database dump rather than a schedule. Relative labels are only
 * used for today and tomorrow; everything further out is spelled out, because
 * "in 4 days" becomes actively unhelpful as a date approaches.
 */
fun formatReleaseDay(dateString: String?, todayMillis: Long = System.currentTimeMillis()): String {
    if (dateString.isNullOrBlank() || dateString.length < 10) return ""
    val date = runCatching { LocalDate.parse(dateString.take(10), ISO_DAY_FORMAT) }.getOrNull()
        ?: return dateString

    val today = Instant.ofEpochMilli(todayMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    return when (ChronoUnit.DAYS.between(today, date)) {
        0L -> "Today"
        1L -> "Tomorrow"
        else -> WEEKDAY_DAY_FORMAT.format(date)
    }
}
