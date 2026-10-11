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
 * Home section membership, ported from the macOS app.
 *
 * The rules live here as pure functions rather than inside a composable because they
 * are the part of Home that has to be right: they decide what the user sees and, more
 * importantly, what they *don't* see. Mirrors `HomeCategoryProcessor.isHomeEligible`,
 * `homeSortPriority`, and the Coming Soon filter.
 */
object HomeEligibility {

    /** States that never belong on Home regardless of badges. */
    private val EXCLUDED_STATES = setOf("completed", "dropped", "on hold")

    /** States that mean the user is part-way through. */
    private val ACTIVE_STATES = setOf("active", "re-watching", "in progress")

    /**
     * Badges that mean "this just happened".
     *
     * Deliberately narrower than macOS's `radarBadges`, which also contains FINALE and
     * HOOKED. Neither of those is new — FINALE says a season is ending and HOOKED says
     * binge behaviour — so they belong in In Progress, not New.
     */
    val NEW_BADGES: Set<SmartBadge> = setOf(
        SmartBadge.NEW,
        SmartBadge.PREMIERE,
        SmartBadge.BINGE_DROP
    )

    /**
     * Sort weights inside Continue Watching, matching `homeSortPriority`:
     * time-sensitive first, then anything actively in progress.
     */
    fun cwPriority(item: MediaItemEntity, badge: SmartBadge?, hasWatched: Boolean): Int = when (badge) {
        SmartBadge.PREMIERE -> 110
        SmartBadge.NEW -> 100
        SmartBadge.FINALE -> 95
        SmartBadge.BINGE_DROP -> 90
        SmartBadge.HOOKED -> 85
        else -> if (item.state.lowercase() in ACTIVE_STATES || hasWatched) 80 else 0
    }

    /**
     * The Continue Watching candidate pool, ported from `isHomeEligible`.
     *
     * Beyond the obvious state exclusions this drops two categories that the simpler
     * "is it active" check keeps:
     *   - titles flagged upcoming whose release is still in the future, and
     *   - shows that are fully caught up and waiting on a future episode, which are a
     *     Coming Soon concern rather than something you can resume.
     */
    fun isContinueWatchingCandidate(
        item: MediaItemEntity,
        badge: SmartBadge?,
        hasUnwatchedAired: Boolean,
        watchedCount: Int,
        nextUnairedAirDate: String?,
        today: String
    ): Boolean {
        if (item.isSoftDeleted) return false
        val state = item.state.lowercase()
        if (state in EXCLUDED_STATES) return false

        val isTv = item.type.contains("TV", ignoreCase = true)
        val releaseDate = item.releaseDate?.take(10)

        // Upcoming and not yet released: belongs in Coming Soon, not here.
        if (releaseDate != null && releaseDate > today && !isTv) return false
        if (releaseDate != null && releaseDate > today && badge == null) return false

        // Caught up with something still to air.
        val isCaughtUp = !hasUnwatchedAired && watchedCount > 0
        if (isCaughtUp && isTv && nextUnairedAirDate != null && nextUnairedAirDate > today) return false

        if (cwPriority(item, badge, watchedCount > 0) <= 0) return false

        // A never-started watchlist title only earns a slot if it just dropped.
        if (state == "wishlist" && watchedCount == 0 && badge in NEW_BADGES) {
            val air = if (isTv && releaseDate != null && releaseDate <= today) releaseDate else releaseDate
            if (air == null) return false
            val daysSince = daysBetween(air, today)
            if (daysSince < 0 || daysSince > 3) return false
        }

        return true
    }

    /**
     * Coming Soon, matching macOS exactly:
     * `airDate > now && state != completed`, soonest first.
     *
     * For a film the air date is its release date. For a show it is the next episode
     * that has not aired yet — macOS gets this from TVMaze's `nextepisode`, which
     * ignores watched state, so a show with a backlog and an unaired episode still
     * counts as upcoming.
     */
    fun isComingSoon(
        item: MediaItemEntity,
        nextUnairedAirDate: String?,
        today: String
    ): Boolean {
        if (item.isSoftDeleted) return false
        if (item.state.equals("Completed", ignoreCase = true)) return false
        val air = nextUnairedAirDate ?: return false
        return air > today
    }

    /** Earliest air date among episodes that have not aired yet. */
    fun nextUnairedAirDate(episodes: List<EpisodeEntity>, today: String): String? =
        episodes.asSequence()
            .mapNotNull { it.airDate?.take(10) }
            .filter { it > today }
            .minOrNull()

    /** Whether the show has any aired-but-unwatched episode to resume. */
    fun hasUnwatchedAired(episodes: List<EpisodeEntity>, today: String): Boolean =
        episodes.any { !it.isWatched && (it.airDate?.take(10)?.let { d -> d <= today } ?: false) }

    /** Everything Continue Watching should offer as a resume action, soonest-to-prioritised. */
    fun resumeCandidates(
        items: List<MediaItemEntity>,
        episodesByShow: Map<String, List<EpisodeEntity>>,
        badges: Map<String, BadgeResult?>,
        today: String,
        limit: Int = 20
    ): List<MediaItemEntity> {
        val scored = items.mapNotNull { item ->
            val key = if (item.type.contains("TV", ignoreCase = true)) MediaIds.tvShowKey(item.id) else null
            val eps = key?.let { episodesByShow[it] }.orEmpty()
            val badge = badges[item.id]?.badge
            val watched = eps.count { it.isWatched }
            if (!isContinueWatchingCandidate(
                    item, badge,
                    hasUnwatchedAired = hasUnwatchedAired(eps, today),
                    watchedCount = watched,
                    nextUnairedAirDate = nextUnairedAirDate(eps, today),
                    today = today
                )
            ) return@mapNotNull null

            val recency = item.lastInteractionDate ?: item.lastStateChangeDate ?: ""
            item to (cwPriority(item, badge, watched > 0) to recency)
        }
        return scored
            .sortedWith(compareByDescending<Pair<MediaItemEntity, Pair<Int, String>>> { it.second.first }
                .thenByDescending { it.second.second })
            .map { it.first }
            .take(limit)
    }

    /** New and In Progress are the two halves of Continue Watching. */
    fun splitNewAndInProgress(
        resume: List<MediaItemEntity>,
        badges: Map<String, BadgeResult?>
    ): Pair<List<MediaItemEntity>, List<MediaItemEntity>> {
        val (newItems, rest) = resume.partition { badges[it.id]?.badge in NEW_BADGES }
        return newItems to rest
    }

    private fun daysBetween(from: String, to: String): Long {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val a = fmt.parse(from) ?: return 0
        val b = fmt.parse(to) ?: return 0
        return (b.time - a.time) / 86_400_000L
    }
}