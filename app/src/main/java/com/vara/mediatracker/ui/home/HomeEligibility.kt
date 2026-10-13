package com.vara.mediatracker.ui.home

import com.vara.mediatracker.data.badge.BadgeResult
import com.vara.mediatracker.data.badge.SmartBadge
import com.vara.mediatracker.data.model.EpisodeEntity
import com.vara.mediatracker.data.model.MediaIds
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.util.AirTimeResolver
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Home section membership, ported from the macOS app.
 *
 * The rules live here as pure functions rather than inside a composable because they
 * are the part of Home that has to be right: they decide what the user sees and, more
 * importantly, what they *don't* see. Mirrors `HomeCategoryProcessor.isHomeEligible`,
 * `homeSortPriority`, and the Coming Soon filter.
 *
 * Continue Watching is evaluated against a `now: Date` and elapsed-time windows, not
 * whole calendar days. The earlier date-string version put a title premiering exactly
 * five days ago in Continue Watching where macOS had already dropped it.
 */
object HomeEligibility {

    /** States that never belong on Home regardless of badges. */
    private val EXCLUDED_STATES = setOf("completed", "dropped", "on hold")

    /** States that mean the user is part-way through. */
    private val ACTIVE_STATES = setOf("active", "re-watching", "in progress")

    private const val DAY_MS = 86_400_000L
    private const val NEW_DROP_WINDOW_DAYS = 5.0
    private const val ACTIVE_WATCH_WINDOW_DAYS = 30.0

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

    /** The per-title state macOS keeps denormalized on `MediaItem`/`TVShowDetails`. */
    data class ShowState(
        val isTv: Boolean,
        val cachedNextAiringDate: Date?,
        val storedIsUpcoming: Boolean,
        val remainingEpisodesCount: Int,
        val storedProgress: Double,
        val latestWatchDate: Date?
    )

    /**
     * Rebuilds macOS's denormalized badge/eligibility inputs from the episode rows:
     * `cachedNextAiringDate`, `storedIsUpcoming`, `remainingEpisodesCount`,
     * `storedProgress`, and the latest watch date.
     */
    fun showState(item: MediaItemEntity, episodes: List<EpisodeEntity>, now: Date): ShowState {
        val isTv = item.type.contains("TV", ignoreCase = true)
        val release = parseDay(item.releaseDate)

        if (!isTv) {
            // Movies: macOS sets cachedNextAiringDate = releaseDate.
            return ShowState(
                isTv = false,
                cachedNextAiringDate = release,
                storedIsUpcoming = release?.after(now) == true,
                remainingEpisodesCount = 0,
                storedProgress = 0.0,
                latestWatchDate = parseDay(item.lastInteractionDate)
            )
        }

        val sorted = episodes.sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))
        val watchedCount = sorted.count { it.isWatched }
        val total = sorted.size
        val aired = sorted.count { ep -> epInstant(ep, item)?.let { !it.after(now) } == true }
        val remaining = maxOf(0, aired - watchedCount)
        val progress = if (total > 0) watchedCount.toDouble() / total.toDouble() else 0.0

        // macOS: firstUnwatched.airDateAsDate ?? tv.nextEpisodeDate.
        val firstUnwatchedAir = sorted.firstOrNull { !it.isWatched }?.let { epInstant(it, item) }
        val cached = firstUnwatchedAir ?: storedInstant(item)

        val latestWatch = sorted.mapNotNull { parseDay(it.watchedDate) }.maxByOrNull { it.time }
            ?: parseDay(item.lastInteractionDate)

        return ShowState(
            isTv = true,
            cachedNextAiringDate = cached,
            storedIsUpcoming = (cached ?: release)?.after(now) == true,
            remainingEpisodesCount = remaining,
            storedProgress = progress,
            latestWatchDate = latestWatch
        )
    }

    /**
     * Continue Watching membership, port of macOS `isHomeEligible`. Uses elapsed-time
     * windows (`now: Date`), not calendar-day counts.
     */
    fun isContinueWatchingCandidate(
        item: MediaItemEntity,
        badge: SmartBadge?,
        episodes: List<EpisodeEntity> = emptyList(),
        now: Date = Date()
    ): Boolean = isHomeEligible(item, badge, showState(item, episodes, now), now)

    private fun isHomeEligible(
        item: MediaItemEntity,
        badge: SmartBadge?,
        st: ShowState,
        now: Date
    ): Boolean {
        if (item.isSoftDeleted) return false
        val state = item.state.lowercase()
        if (state in EXCLUDED_STATES) return false

        // Upcoming titles only stay on Home for 5 days after the airing they point at.
        if (st.storedIsUpcoming) {
            val airDate = st.cachedNextAiringDate ?: parseDay(item.releaseDate) ?: return false
            if (airDate.after(now)) return false
            val daysSinceAir = (now.time - airDate.time).toDouble() / DAY_MS
            if (daysSinceAir > NEW_DROP_WINDOW_DAYS) return false
        }

        // Caught up with something still to air: a Coming Soon concern, not resumable.
        val isCaughtUp = st.remainingEpisodesCount == 0 && st.storedProgress > 0.0
        val nextAir = st.cachedNextAiringDate
        if (isCaughtUp && nextAir != null && nextAir.after(now) && st.isTv) return false

        if (badge in NEW_BADGES) {
            // A never-started watchlist title only earns a slot if it just dropped.
            if (state == "wishlist" && st.storedProgress == 0.0) {
                val release = parseDay(item.releaseDate)
                val anchor = if (st.isTv && release != null && !release.after(now)) {
                    release
                } else {
                    st.cachedNextAiringDate ?: release
                } ?: return false
                val daysSinceRelease = (now.time - anchor.time).toDouble() / DAY_MS
                if (daysSinceRelease < 0.0 || daysSinceRelease > NEW_DROP_WINDOW_DAYS) return false
            }
            return true
        }

        val isActive = state in ACTIVE_STATES || st.storedProgress > 0.0
        if (isActive) {
            // An explicitly-active title with no watched episodes must have recent
            // activity, otherwise a stale "Active" flag keeps it on Home forever.
            if (state in ACTIVE_STATES && st.storedProgress == 0.0) {
                val latest = st.latestWatchDate ?: return false
                if (latest.time < now.time - (ACTIVE_WATCH_WINDOW_DAYS * DAY_MS).toLong()) return false
            }
            return true
        }
        return false
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
        if (item.state.equals("Dropped", ignoreCase = true)) return false
        if (item.state.equals("On Hold", ignoreCase = true)) return false
        if (item.taste.equals("Dislike", ignoreCase = true)) return false
        val air = nextUnairedAirDate ?: return false
        return air > today
    }

    /**
     * Port of macOS `cachedNextAiringDate` (MediaItem+Sync.swift:233):
     * the air date of the FIRST UNWATCHED episode, falling back to the next
     * unaired one only when that episode has no air date.
     */
    fun nextAiringDate(episodes: List<EpisodeEntity>, today: String): String? {
        val ordered = episodes.sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))
        val firstUnwatchedAir = ordered.firstOrNull { !it.isWatched }?.airDate?.take(10)
        if (firstUnwatchedAir != null) return firstUnwatchedAir
        return ordered.asSequence()
            .mapNotNull { it.airDate?.take(10) }
            .filter { it > today }
            .minOrNull()
    }

    /** Whether the show has any aired-but-unwatched episode to resume. */
    fun hasUnwatchedAired(episodes: List<EpisodeEntity>, today: String): Boolean =
        episodes.any { !it.isWatched && (it.airDate?.take(10)?.let { d -> d <= today } ?: false) }

    /**
     * The episode [nextAiringDate] points at, so callers can use its TVMaze `airstamp`
     * and airtime for an exact countdown.
     */
    fun nextAiringEpisode(episodes: List<EpisodeEntity>, today: String): EpisodeEntity? {
        val ordered = episodes.sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))
        ordered.firstOrNull { !it.isWatched }?.let { return it }
        return ordered.asSequence()
            .filter { (it.airDate?.take(10) ?: "") > today }
            .minByOrNull { it.airDate ?: "" }
    }

    /** Everything Continue Watching should offer as a resume action, soonest-to-prioritised. */
    fun resumeCandidates(
        items: List<MediaItemEntity>,
        episodesByShow: Map<String, List<EpisodeEntity>>,
        badges: Map<String, BadgeResult?>,
        now: Date = Date(),
        limit: Int = 20
    ): List<MediaItemEntity> {
        val scored = items.mapNotNull { item ->
            val key = if (item.type.contains("TV", ignoreCase = true)) MediaIds.tvShowKey(item.id) else null
            val eps = key?.let { episodesByShow[it] }.orEmpty()
            val badge = badges[item.id]?.badge
            if (!isContinueWatchingCandidate(item, badge, eps, now)) return@mapNotNull null

            val watched = eps.count { it.isWatched }
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

    // ---- instant helpers ----

    private fun epInstant(ep: EpisodeEntity, item: MediaItemEntity): Date? =
        AirTimeResolver.resolveAirInstant(
            dateString = ep.airDate?.take(10),
            time = ep.airTime,
            airstamp = ep.airstamp,
            timezone = item.timezone,
            serviceName = AirTimeResolver.serviceHint(item.network, item.studios, item.genres)
        )

    private fun storedInstant(item: MediaItemEntity): Date? =
        item.nextAiringInstant?.takeIf { it.isNotBlank() }?.let { iso ->
            AirTimeResolver.resolveAirInstant(
                dateString = iso.take(10),
                airstamp = iso,
                timezone = item.timezone,
                serviceName = AirTimeResolver.serviceHint(item.network, item.studios, item.genres)
            )
        }

    private fun parseDay(dateStr: String?): Date? {
        if (dateStr.isNullOrBlank() || dateStr.length < 10) return null
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateStr.take(10))
        }.getOrNull()
    }
}
