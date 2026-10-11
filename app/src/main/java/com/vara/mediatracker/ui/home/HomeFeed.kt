package com.vara.mediatracker.ui.home

import com.vara.mediatracker.data.badge.BadgeResult
import com.vara.mediatracker.data.badge.SmartBadge
import com.vara.mediatracker.data.model.EpisodeEntity
import com.vara.mediatracker.data.model.MediaIds
import com.vara.mediatracker.data.model.MediaItemEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * One entry in the Home timeline.
 *
 * Deliberately not a poster card. The timeline answers "what happened and what is
 * next", which is a question about *time*, so entries are keyed by when rather than by
 * what they look like.
 */
data class FeedEntry(
    val media: MediaItemEntity,
    val dateString: String,          // "yyyy-MM-dd"
    val kindLabel: String,           // "S2 E4 +2 more", "Movie Premiere"
    val episodeCount: Int,
    val isEpisode: Boolean,
    val nextAiringInstant: String? = null
)

/** A day of the timeline, with its entries. */
data class FeedDay(
    val dateString: String,
    val entries: List<FeedEntry>
)

/**
 * Builds the Home timeline.
 *
 * Home used to be five horizontal rails of identically shaped posters, which meant
 * nothing had priority and the answer to "what aired?" required scanning sideways
 * through shelves. This produces a single chronological feed instead: everything
 * grouped by day, soonest first, so the top of the screen is always the answer to
 * "what's on now".
 *
 * Movies contribute from their release date. TV shows contribute from the air date of
 * their **next unwatched episode**, which is the date that actually matters to someone
 * tracking a show — not the show's premiere, which is often a year old.
 */
object HomeFeed {

    private const val DAY_MS = 86_400_000L

    fun build(
        items: List<MediaItemEntity>,
        episodesByShow: Map<String, List<EpisodeEntity>>,
        badges: Map<String, BadgeResult?>,
        nowMillis: Long = System.currentTimeMillis(),
        daysAhead: Int = 30,
        maxEntries: Int = 80
    ): List<FeedDay> {
        val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val today = dayFormat.format(Date(nowMillis))
        val horizon = dayFormat.format(Date(nowMillis + daysAhead * DAY_MS))

        val entries = ArrayList<FeedEntry>()

        for (item in items) {
            if (item.isSoftDeleted) continue
            if (item.state.equals("Completed", ignoreCase = true)) continue

            val isTv = item.type.contains("TV", ignoreCase = true)

            if (isTv) {
                val showKey = MediaIds.tvShowKey(item.id)
                val showEpisodes = episodesByShow[showKey].orEmpty()

                // The next unwatched episode is the thing the user is waiting on.
                // Grouped so a multi-episode drop is one entry rather than six rows.
                val upcoming = showEpisodes
                    .filter { !it.isWatched }
                    .groupBy { it.airDate?.take(10) }
                    .filterKeys { it != null }

                var added = false
                for ((date, sameDay) in upcoming) {
                    if (date == null || date < today || date > horizon) continue
                    val ordered = sameDay.sortedWith(
                        compareBy({ it.seasonNumber }, { it.episodeNumber })
                    )
                    entries += FeedEntry(
                        media = item,
                        dateString = date,
                        kindLabel = if (ordered.size == 1) {
                            "S${ordered[0].seasonNumber} E${ordered[0].episodeNumber}"
                        } else {
                            "S${ordered[0].seasonNumber} E${ordered[0].episodeNumber} +${ordered.size - 1} more"
                        },
                        episodeCount = ordered.size,
                        isEpisode = true,
                        nextAiringInstant = item.nextAiringInstant
                    )
                    added = true
                }
                if (!added) {
                    // Caught up on everything aired — still worth showing if the show
                    // itself has not released yet.
                    val release = item.releaseDate?.take(10)
                    if (release != null && release in today..horizon) {
                        entries += FeedEntry(
                            media = item, dateString = release,
                            kindLabel = "Series Premiere", episodeCount = 0, isEpisode = false,
                            nextAiringInstant = item.nextAiringInstant
                        )
                    }
                }
            } else {
                val release = item.releaseDate?.take(10) ?: continue
                if (release !in today..horizon) continue
                entries += FeedEntry(
                    media = item,
                    dateString = release,
                    kindLabel = "Movie Premiere",
                    episodeCount = 0,
                    isEpisode = false,
                    nextAiringInstant = item.nextAiringInstant
                )
            }
        }

        return entries
            .sortedWith(compareBy({ it.dateString }, { it.media.title }))
            .take(maxEntries)
            .groupBy { it.dateString }
            .toSortedMap()
            .map { (date, dayEntries) -> FeedDay(date, dayEntries) }
    }

    /** Titles worth offering a resume action for — separate from the event feed. */
    fun resumeCandidates(
        continueWatching: List<MediaItemEntity>,
        badges: Map<String, BadgeResult?>,
        limit: Int = 5
    ): List<MediaItemEntity> = continueWatching.take(limit)
}