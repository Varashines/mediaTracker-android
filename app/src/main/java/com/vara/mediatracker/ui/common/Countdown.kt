package com.vara.mediatracker.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

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
 */
data class StreamingServiceRule(
    val patterns: List<String>,
    val releaseTime: String, // "HH:mm"
    val timeZoneId: String,
    val dayOffset: Int
)

val defaultStreamingRules = listOf(
    // Apple TV Originals drop at 9 PM ET on the eve of release date
    StreamingServiceRule(listOf("apple"), "21:00", "America/New_York", -1),
    // Disney+ Marvel / Star Wars drops at 9 PM ET
    StreamingServiceRule(listOf("star wars", "marvel"), "21:00", "America/New_York", 0),
    // Disney+ standard Midnight PT
    StreamingServiceRule(listOf("disney"), "00:00", "America/Los_Angeles", 0),
    // Netflix: Midnight PT
    StreamingServiceRule(listOf("netflix"), "00:00", "America/Los_Angeles", 0),
    // Prime / Amazon / MGM+: Midnight PT
    StreamingServiceRule(listOf("amazon", "prime", "mgm"), "00:00", "America/Los_Angeles", 0),
    // Hulu / Peacock / Paramount+: Midnight ET
    StreamingServiceRule(listOf("hulu", "peacock", "paramount"), "00:00", "America/New_York", 0),
    // FX / FXX: Broadcasts evening / drops next morning on Hulu
    StreamingServiceRule(listOf("fx", "fxx"), "00:00", "America/New_York", 1),
    // HBO / Max: 9 PM ET
    StreamingServiceRule(listOf("max", "hbo"), "21:00", "America/New_York", 0)
)

/**
 * Resolves exact Date for an airDate or releaseDate string using streaming/network rules or 8 PM ET default.
 */
fun parseTargetAirDateTime(dateStr: String?, networkOrTitle: String?): Date? {
    if (dateStr.isNullOrBlank()) return null
    val cleanDate = dateStr.trim().take(10) // "yyyy-MM-dd"
    if (cleanDate.length < 10) return null

    val (timePart, tzId, dayOffset) = resolveReleaseRule(networkOrTitle)

    return try {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
            timeZone = TimeZone.getTimeZone(tzId)
        }
        val baseDate = sdf.parse("$cleanDate $timePart") ?: return null
        if (dayOffset != 0) {
            val cal = Calendar.getInstance(TimeZone.getTimeZone(tzId)).apply {
                time = baseDate
                add(Calendar.DAY_OF_YEAR, dayOffset)
            }
            cal.time
        } else {
            baseDate
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * Human-readable drop time for a release, resolved from the network's streaming rule.
 *
 * Returns e.g. "9:00 PM ET" / "12:00 AM PT", formatted in the rule's own timezone so the
 * label matches what the service advertises. Previously every card hardcoded
 * "(9:00 PM EST)", which was simply wrong for Netflix, Disney, and movies.
 */
fun formatReleaseAirTime(dateStr: String?, networkOrTitle: String?): String? {
    if (dateStr.isNullOrBlank()) return null
    val cleanDate = dateStr.trim().take(10)
    if (cleanDate.length < 10) return null

    val (timePart, tzId, _) = resolveReleaseRule(networkOrTitle)
    val tz = TimeZone.getTimeZone(tzId)
    return try {
        val base = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
            timeZone = tz
        }.parse("$cleanDate $timePart") ?: return null
        val time = SimpleDateFormat("h:mm a", Locale.US).apply { timeZone = tz }.format(base)
        val zone = tz.getDisplayName(false, TimeZone.SHORT, Locale.US)
        "$time $zone"
    } catch (_: Exception) {
        null
    }
}

private fun resolveReleaseRule(networkOrTitle: String?): Triple<String, String, Int> {
    val query = (networkOrTitle ?: "").lowercase(Locale.US)
    val rule = defaultStreamingRules.firstOrNull { r ->
        r.patterns.any { pattern -> query.contains(pattern) }
    }
    return if (rule != null) {
        Triple(rule.releaseTime, rule.timeZoneId, rule.dayOffset)
    } else {
        Triple("20:00", "America/New_York", 0)
    }
}

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
    return SimpleDateFormat("MMM d", Locale.getDefault()).format(targetDate)
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
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMillis))
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
    val parsed = runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateString.take(10))
    }.getOrNull() ?: return dateString

    val cal = Calendar.getInstance().apply { time = parsed }
    val todayCal = Calendar.getInstance().apply { timeInMillis = todayMillis }

    fun daysBetween(): Long {
        val a = cal.clone() as Calendar
        val b = todayCal.clone() as Calendar
        listOf(a, b).forEach {
            it.set(Calendar.HOUR_OF_DAY, 0)
            it.set(Calendar.MINUTE, 0)
            it.set(Calendar.SECOND, 0)
            it.set(Calendar.MILLISECOND, 0)
        }
        return (a.timeInMillis - b.timeInMillis) / 86_400_000L
    }

    return when (daysBetween()) {
        0L -> "Today"
        1L -> "Tomorrow"
        else -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(parsed)
    }
}
