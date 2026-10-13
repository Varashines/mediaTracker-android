package com.vara.mediatracker.ui.calendar

import com.vara.mediatracker.data.model.EpisodeEntity
import com.vara.mediatracker.data.model.MediaIds
import com.vara.mediatracker.data.model.MediaItemEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Builds the release events shown in a single calendar month.
 *
 * Extracted from the composable so the grouping rules can be unit tested without
 * a Compose runtime — the screen is a thin renderer over this result.
 *
 * Two rules drive the shape of the output:
 *  - A show dropping several episodes on one day yields a *single* event, not one
 *    per episode. Streaming drops arrive in bundles, so the previous behaviour
 *    buried everything else under a wall of near-identical cards.
 *  - Movies are always standalone, since a film release never shares its date
 *    with anything else in the library.
 */
fun buildMonthReleaseEvents(
    items: List<MediaItemEntity>,
    episodes: List<EpisodeEntity>,
    year: Int,
    month: Int,
    dateParser: (String) -> Calendar? = { parseCalendarDate(it) }
): List<CalendarReleaseEvent> {

    val events = mutableListOf<CalendarReleaseEvent>()
    val itemsById = items.associateBy { it.id }

    fun inMonth(datePart: String): Boolean {
        val cal = dateParser(datePart) ?: return false
        return cal.get(Calendar.YEAR) == year && cal.get(Calendar.MONTH) == month
    }

    // 1. Movie releases
    for (item in items) {
        if (item.isSoftDeleted) continue
        if (item.type.contains("TV", ignoreCase = true)) continue
        val release = item.releaseDate ?: continue
        if (release.length < 10) continue
        val datePart = release.take(10)
        if (!inMonth(datePart)) continue
        events += CalendarReleaseEvent(
            mediaItem = item,
            dateString = datePart,
            isEpisode = false
        )
    }

    // 2. Episode drops, grouped by (show, day)
    val groups = LinkedHashMap<Pair<String, String>, MutableList<EpisodeEntity>>()
    for (episode in episodes) {
        val air = episode.airDate ?: continue
        if (air.length < 10) continue
        val datePart = air.take(10)
        if (!inMonth(datePart)) continue

        val show = resolveShow(itemsById, items, episode) ?: continue
        if (show.isSoftDeleted) continue

        groups.getOrPut(show.id to datePart) { mutableListOf() } += episode
    }

    for ((key, sameDay) in groups) {
        val show = itemsById[key.first] ?: continue
        val ordered = sameDay.sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))
        val first = ordered.firstOrNull()
        events += CalendarReleaseEvent(
            mediaItem = show,
            dateString = key.second,
            isEpisode = true,
            episodeCount = ordered.size,
            episodeLabels = ordered.map { "S${it.seasonNumber} E${it.episodeNumber}" },
            airTime = first?.airTime,
            airstamp = first?.airstamp
        )
    }

    return events.sortedWith(
        compareBy<CalendarReleaseEvent>({ it.dateString }, { !it.isEpisode }, { it.mediaItem.title })
    )
}

/**
 * Maps an episode back to its show.
 *
 * Episodes arrive with `showId` from several sources (Supabase pull, TMDB
 * enrichment), so the id may already be canonical or may be a bare TMDB id.
 */
private fun resolveShow(
    itemsById: Map<String, MediaItemEntity>,
    items: List<MediaItemEntity>,
    episode: EpisodeEntity
): MediaItemEntity? =
    itemsById[MediaIds.tvShowKey(episode.showId)]
        ?: itemsById[episode.showId]
        ?: items.firstOrNull { it.id.endsWith(episode.showId) }

private val monthDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

internal fun parseCalendarDate(datePart: String): Calendar? = try {
    monthDateFormat.parse(datePart.take(10))?.let { Calendar.getInstance().apply { time = it } }
} catch (_: Exception) {
    null
}