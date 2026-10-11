package com.vara.mediatracker.ui.home

import com.vara.mediatracker.data.badge.BadgeResult
import com.vara.mediatracker.data.badge.SmartBadge
import com.vara.mediatracker.data.model.MediaItemEntity

/**
 * The three Home rails, as a partition rather than three overlapping queries.
 *
 * Every title appears in at most one rail. Overlap was the previous behaviour and it
 * read as a bug: the same poster could show up under Continue Watching and again under
 * Coming Soon, which made the screen feel like it was repeating itself rather than
 * curating.
 *
 *  - [newReleases] — something just premiered or is about to. Time-sensitive, so it
 *    earns its own rail above the bulk lists.
 *  - [continueWatching] — actively in progress and ready to watch right now.
 *  - [inProgress] — the rest of what the user is part-way through. Not urgent, but
 *    genuinely unfinished, and previously there was nowhere to show it.
 */
data class HomeSections(
    val newReleases: List<MediaItemEntity>,
    val continueWatching: List<MediaItemEntity>,
    val inProgress: List<MediaItemEntity>
) {
    val isEmpty: Boolean
        get() = newReleases.isEmpty() && continueWatching.isEmpty() && inProgress.isEmpty()

    val titleCount: Int
        get() = newReleases.size + continueWatching.size + inProgress.size
}

object HomeSectionsBuilder {

    /** Badges that make a title "new" rather than merely in progress. */
    val NEW_BADGES: Set<SmartBadge> = setOf(SmartBadge.PREMIERE, SmartBadge.NEW)

    const val RAIL_LIMIT = 20

    /**
     * @param readyNow titles that passed Continue Watching's eligibility test
     * @param inProgressAll every actively-watching title, regardless of whether its
     *   next episode has aired — the leftovers land here
     */
    fun build(
        readyNow: List<MediaItemEntity>,
        inProgressAll: List<MediaItemEntity>,
        badges: Map<String, BadgeResult?>,
        limit: Int = RAIL_LIMIT
    ): HomeSections {
        val (newItems, resumable) = readyNow.partition { badges[it.id]?.badge in NEW_BADGES }

        val newReleases = newItems.take(limit)
        val continueWatching = resumable.take(limit)

        val placed = (newReleases + continueWatching).mapTo(HashSet()) { it.id }
        val inProgress = inProgressAll
            .filter { it.id !in placed }
            .take(limit)

        return HomeSections(
            newReleases = newReleases,
            continueWatching = continueWatching,
            inProgress = inProgress
        )
    }

}