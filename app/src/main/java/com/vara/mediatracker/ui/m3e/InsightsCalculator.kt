package com.vara.mediatracker.ui.m3e

import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.data.model.Taste
import com.vara.mediatracker.data.model.WatchEventEntity
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A name and how many titles carry it. */
data class FacetCount(val name: String, val count: Int)

/** One month of watch activity. */
data class MonthBucket(val month: YearMonth, val watches: Int)

/**
 * What the library adds up to.
 *
 * The Mac's `LibraryStatsActor` is the reference, but this is computed from data both devices
 * already sync — titles, and the live watch events — rather than from projections only the Mac
 * keeps. A statistic that needs a column the other device does not have is a statistic that
 * disagrees between the two, which is worse than one that is missing.
 *
 * Watch time is summed from event runtimes, so it counts what was actually watched and only when
 * the runtime was known: an hour invented for a film with no runtime would be a made-up number in a
 * screen that is entirely about numbers.
 */
object InsightsCalculator {
    fun compute(
        items: List<MediaItemEntity>,
        events: List<WatchEventEntity>,
        months: Int = 12,
        today: YearMonth = YearMonth.now()
    ): Insights {
        val live = items.filterNot { it.isSoftDeleted }
        val liveEvents = events.filter { it.voidedAt == null }

        val completed = live.filter { it.state.equals("Completed", true) }
        val inProgress = live.filter {
            it.state.equals("Active", true) || it.state.equals("Re-watching", true)
        }
        val wishlist = live.filter { it.state.equals("Wishlist", true) }

        val buckets = (months - 1 downTo 0).map { offset ->
            val month = today.minusMonths(offset.toLong())
            MonthBucket(month, 0)
        }
        val counts = buckets.associate { it.month to 0 }.toMutableMap()
        for (event in liveEvents) {
            val month = runCatching { YearMonth.from(java.time.LocalDate.parse(event.watchedAt.take(10))) }
                .getOrNull() ?: continue
            if (counts.containsKey(month)) counts[month] = counts.getValue(month) + 1
        }

        return Insights(
            titles = live.size,
            movies = live.count { !it.type.contains("TV", true) },
            shows = live.count { it.type.contains("TV", true) },
            completed = completed.size,
            inProgress = inProgress.size,
            wishlist = wishlist.size,
            episodesWatched = liveEvents.count { it.episodeId != null },
            minutesWatched = liveEvents.sumOf { it.runtimeMinutes ?: 0 },
            byMonth = counts.map { MonthBucket(it.key, it.value) }.sortedBy { it.month },
            topGenres = facets(live) { it.genres }.take(6),
            topNetworks = facets(live.filter { it.type.contains("TV", true) }) { it.network }.take(6),
            topLanguages = facets(live) { it.language }.take(6),
            taste = live.groupingBy { Taste.normalize(it.taste) }.eachCount().filterValues { it > 0 }
        )
    }

    /** Comma-separated values counted per title, most common first. */
    fun facets(items: List<MediaItemEntity>, selector: (MediaItemEntity) -> String?): List<FacetCount> {
        val counts = linkedMapOf<String, Int>()
        for (item in items) {
            val raw = selector(item) ?: continue
            raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet().forEach { name ->
                counts[name] = (counts[name] ?: 0) + 1
            }
        }
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { FacetCount(it.key, it.value) }
    }
}

data class Insights(
    val titles: Int,
    val movies: Int,
    val shows: Int,
    val completed: Int,
    val inProgress: Int,
    val wishlist: Int,
    val episodesWatched: Int,
    val minutesWatched: Int,
    val byMonth: List<MonthBucket>,
    val topGenres: List<FacetCount>,
    val topNetworks: List<FacetCount>,
    val topLanguages: List<FacetCount>,
    val taste: Map<String, Int>
) {
    val hours: Int get() = minutesWatched / 60
    val busiest: MonthBucket? get() = byMonth.maxByOrNull { it.watches }
}

fun MonthBucket.label(): String =
    month.format(DateTimeFormatter.ofPattern("MMM", Locale.US))
