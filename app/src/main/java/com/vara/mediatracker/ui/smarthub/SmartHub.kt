package com.vara.mediatracker.ui.smarthub

import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.util.AirTimeResolver
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The system smart collections shown on the Smart Hub, mirroring the macOS
 * `NavigationCategory.isSmartCategory` set.
 */
enum class SmartCategory(
    val title: String,
    val description: String
) {
    RELEASE_RADAR(
        "Release Radar",
        "New releases, premieres, finales, and full-season drops available now."
    ),
    SMART_UPCOMING(
        "Premiere Radar",
        "Upcoming premieres and newly released debuts in your library."
    ),
    CATCH_UP(
        "Catch Up",
        "Liked shows with episodes to catch up on before the next release."
    ),
    LOVED(
        "Loved",
        "Your absolute favorites, marked with a heart."
    ),
    BINGE(
        "Binge Ready",
        "Shows ready for your next binge, including full-season drops."
    ),
    QUICK_BITES(
        "Quick Bites",
        "Movies under 90 minutes and shows with episodes 35 minutes or less."
    ),
    ARCHIVE(
        "Shelved",
        "On hold or dropped items you've shelved."
    ),
    ON_THIS_WEEK(
        "Released This Week",
        "Movies and new series premieres released in the current week."
    )
}

/**
 * Pure membership rules for the Smart Hub categories, ported from macOS
 * `MediaFilterPredicates` and the `MediaFilterActor` refinement pass.
 *
 * Everything here is a function of values already stored on the item (state, taste,
 * badge, release date) plus two optional runtime hints, so it needs no database.
 */
object SmartCategoryMatcher {

    /** Badge labels that mean "this just happened", matching macOS `radarBadges`. */
    private val RADAR_BADGES = setOf("NEW", "BINGE DROP", "PREMIERE", "FINALE")

    fun matches(
        category: SmartCategory,
        item: MediaItemEntity,
        badgeLabel: String?,
        runtimeMinutes: Int? = null,
        episodeRuntimeMinutes: Int? = null,
        now: Date = Date()
    ): Boolean {
        if (item.isSoftDeleted) return false
        val state = item.state.lowercase(Locale.US)
        val taste = item.taste.lowercase(Locale.US)

        return when (category) {
            SmartCategory.LOVED -> taste == "love" || taste == "loved"

            SmartCategory.ARCHIVE -> state == "on hold" || state == "dropped"

            SmartCategory.BINGE -> badgeLabel == "BINGE" || badgeLabel == "BINGE DROP"

            SmartCategory.CATCH_UP -> badgeLabel == "BEHIND"

            SmartCategory.SMART_UPCOMING -> badgeLabel == "PREMIERE"

            // Radar badges, but only once the title has already aired.
            SmartCategory.RELEASE_RADAR ->
                badgeLabel != null && badgeLabel in RADAR_BADGES && airDateOnOrBefore(item, now)

            SmartCategory.QUICK_BITES ->
                isQuickBite(item, state, taste, runtimeMinutes, episodeRuntimeMinutes)

            SmartCategory.ON_THIS_WEEK ->
                item.releaseDate?.let { sameWeek(it, now) } == true
        }
    }

    private fun isQuickBite(
        item: MediaItemEntity,
        state: String,
        taste: String,
        runtimeMinutes: Int?,
        episodeRuntimeMinutes: Int?
    ): Boolean {
        if (state == "dropped" || state == "on hold") return false
        if (taste == "dislike" || taste == "disliked") return false
        val isTv = item.type.contains("TV", ignoreCase = true)
        return if (!isTv) {
            (runtimeMinutes ?: 0) in 1..89
        } else {
            (episodeRuntimeMinutes ?: 0) in 1..35
        }
    }

    /** The stored TVMaze instant, else the release date, is at or before [now]. */
    private fun airDateOnOrBefore(item: MediaItemEntity, now: Date): Boolean {
        val instant = item.nextAiringInstant?.takeIf { it.isNotBlank() }?.let { iso ->
            AirTimeResolver.resolveAirInstant(
                dateString = iso.take(10),
                airstamp = iso,
                timezone = item.timezone,
                serviceName = item.network
            )
        } ?: item.releaseDate?.let { release ->
            AirTimeResolver.resolveAirInstant(release, serviceName = item.network)
        }
        return instant != null && instant.time <= now.time
    }

    /**
     * Port of macOS `DateUtils.sameWeek`: true when the release's month/day falls on a
     * day of [now]'s week **in a different year** (a "this week in a prior year" rule).
     * Note the deliberate cross-year guard — a title released this year does not match.
     */
    internal fun sameWeek(
        releaseDate: String,
        now: Date,
        calendar: Calendar = Calendar.getInstance()
    ): Boolean {
        if (releaseDate.length < 10) return false
        val release = runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(releaseDate.take(10))
        }.getOrNull() ?: return false

        val a = (calendar.clone() as Calendar).apply { time = release }
        val b = (calendar.clone() as Calendar).apply { time = now }

        if (a.get(Calendar.YEAR) == b.get(Calendar.YEAR)) return false

        val targetMonth = a.get(Calendar.MONTH)
        val targetDay = a.get(Calendar.DAY_OF_MONTH)

        val weekStart = (b.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        while (weekStart.get(Calendar.DAY_OF_WEEK) != weekStart.firstDayOfWeek) {
            weekStart.add(Calendar.DAY_OF_YEAR, -1)
        }

        for (offset in 0..6) {
            val day = (weekStart.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, offset) }
            if (day.get(Calendar.MONTH) == targetMonth && day.get(Calendar.DAY_OF_MONTH) == targetDay) {
                return true
            }
        }
        return false
    }
}
