package com.vara.mediatracker.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Resolves the real instant an episode airs, ported from the macOS
 * `DateUtils.parseEpisodeDate`.
 *
 * TMDB only ever gives a local calendar day for an episode (`air_date`); the season
 * endpoint has no `air_time` at all (see [com.vara.mediatracker.data.api.TVMazeClient]).
 * A bare date therefore parses to midnight, which is up to a day before the real
 * airing and is enough to push `daysSinceAir` across BadgeEngine's 3-day PREMIERE
 * boundary. macOS avoids this because it fetches TVMaze's per-episode `airstamp` and
 * applies per-service streaming rules; this is that same pipeline.
 *
 * Resolution order, deliberately identical to macOS:
 *  1. YouTube — TVMaze's airstamp is the true release time.
 *  2. Streaming-service rule — used only when TVMaze has no real broadcast airtime,
 *     because streaming originals carry a placeholder noon-UTC stamp.
 *  3. Real TVMaze airtime, interpreted in the show's own timezone.
 *  4. Real ISO airstamp, skipping the noon-UTC placeholder.
 *  5. Show timezone + time (defaulting to 20:00).
 *  6. US 8 PM ET fallback.
 */
object AirTimeResolver {

    data class StreamingServiceRule(
        val patterns: List<String>,
        val releaseTime: String, // "HH:mm"
        val timeZoneId: String,
        val dayOffset: Int
    )

    /**
     * Mirrors macOS `StreamingServiceRule.defaults` one-for-one. Keep the two in sync:
     * these values are the whole reason a Netflix drop shows at midnight PT and an
     * Apple TV+ drop on the evening before its listed date.
     */
    val defaultStreamingRules: List<StreamingServiceRule> = listOf(
        // Apple TV Originals: 9 PM ET the evening before the listed date.
        StreamingServiceRule(listOf("apple"), "21:00", "America/New_York", -1),
        // Disney+ flagships (Marvel / Star Wars): 9 PM ET on the listed date.
        StreamingServiceRule(listOf("star wars", "marvel"), "21:00", "America/New_York", 0),
        // Disney+ standard: midnight PT.
        StreamingServiceRule(listOf("disney"), "00:00", "America/Los_Angeles", 0),
        // Netflix: midnight PT.
        StreamingServiceRule(listOf("netflix"), "00:00", "America/Los_Angeles", 0),
        // Prime / Amazon / MGM+: midnight PT.
        StreamingServiceRule(listOf("amazon", "prime", "mgm"), "00:00", "America/Los_Angeles", 0),
        // Hulu / Peacock / Paramount+: midnight ET.
        StreamingServiceRule(listOf("hulu", "peacock", "paramount"), "00:00", "America/New_York", 0),
        // FX / FXX: evening broadcast, drops next morning on Hulu.
        StreamingServiceRule(listOf("fx", "fxx"), "00:00", "America/New_York", 1),
        // HBO Max / Max Originals: 9 PM ET.
        StreamingServiceRule(listOf("max"), "21:00", "America/New_York", 0),
        // HBO linear: 9 PM ET.
        StreamingServiceRule(listOf("hbo"), "21:00", "America/New_York", 0)
    )

    /** The first rule whose pattern appears in [serviceName], or null when none match. */
    fun ruleFor(serviceName: String?): StreamingServiceRule? {
        if (serviceName.isNullOrBlank()) return null
        val query = serviceName.lowercase(Locale.US)
        return defaultStreamingRules.firstOrNull { rule ->
            rule.patterns.any { query.contains(it) }
        }
    }

    /** Parses an ISO-8601 instant, tolerating malformed input. */
    fun parseIsoInstant(airstamp: String?): Date? {
        if (airstamp.isNullOrBlank()) return null
        return try {
            Date.from(java.time.Instant.parse(airstamp.trim()))
        } catch (_: Exception) {
            null
        }
    }

    fun resolveAirInstant(
        dateString: String?,
        time: String? = null,
        airstamp: String? = null,
        timezone: String? = null,
        serviceName: String? = null
    ): Date? {
        val service = (serviceName ?: "").lowercase(Locale.US)
        val resolvedTime = time?.trim()?.takeIf { it.isNotEmpty() }?.take(5)

        // 1. YouTube publishes the real release time in airstamp.
        if (service == "youtube") {
            parseIsoInstant(airstamp)?.let { return it }
        }

        val resolvedDateString = dateString?.takeIf { it.isNotBlank() }?.take(10)
            ?: airstamp?.takeIf { it.length >= 10 }?.take(10)
        val hasRealAirtime = resolvedTime != null

        // 2. Streaming-service rule (only when there is no genuine broadcast airtime).
        if (!hasRealAirtime && resolvedDateString != null) {
            ruleFor(service)?.let { rule ->
                parseInZone(resolvedDateString, rule.releaseTime, rule.timeZoneId)?.let { base ->
                    return offsetDays(base, rule.dayOffset, rule.timeZoneId)
                }
            }
        }

        // 3. Real TVMaze airtime, in the show's timezone.
        if (hasRealAirtime && resolvedDateString != null && !timezone.isNullOrBlank()) {
            parseInZone(resolvedDateString, resolvedTime, timezone)?.let { return it }
        }

        // 4. Real ISO airstamp; skip TVMaze's noon-UTC placeholder for streaming titles.
        if (airstamp != null && !airstamp.contains("T12:00:00+00:00")) {
            parseIsoInstant(airstamp)?.let { return it }
        }

        val day = resolvedDateString ?: return null

        // 5. Timezone + time fallback.
        if (!timezone.isNullOrBlank()) {
            parseInZone(day, resolvedTime ?: "20:00", timezone)?.let { return it }
        }

        // 6. US 8 PM ET fallback.
        return parseInZone(day, "20:00", "America/New_York")
    }

    /**
     * Human-readable drop time for a release, resolved from the service rule and
     * formatted in the rule's own timezone, e.g. "9:00 PM ET" / "12:00 AM PT".
     */
    fun formatAirTime(dateString: String?, serviceName: String?): String? {
        if (dateString.isNullOrBlank()) return null
        val day = dateString.trim().take(10)
        if (day.length < 10) return null

        val rule = ruleFor(serviceName)
        val timePart = rule?.releaseTime ?: "20:00"
        val zoneId = rule?.timeZoneId ?: "America/New_York"
        val tz = TimeZone.getTimeZone(zoneId)
        return try {
            val base = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                .apply { timeZone = tz }
                .parse("$day $timePart") ?: return null
            val time = SimpleDateFormat("h:mm a", Locale.US).apply { timeZone = tz }.format(base)
            val zone = tz.getDisplayName(false, TimeZone.SHORT, Locale.US)
            "$time $zone"
        } catch (_: Exception) {
            null
        }
    }

    private fun parseInZone(day: String, time: String, zoneId: String): Date? = try {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone(zoneId) }
            .parse("$day $time")
    } catch (_: Exception) {
        null
    }

    private fun offsetDays(base: Date, dayOffset: Int, zoneId: String): Date {
        if (dayOffset == 0) return base
        return Calendar.getInstance(TimeZone.getTimeZone(zoneId)).apply {
            time = base
            add(Calendar.DAY_OF_YEAR, dayOffset)
        }.time
    }
}
