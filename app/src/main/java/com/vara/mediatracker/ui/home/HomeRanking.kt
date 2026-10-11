package com.vara.mediatracker.ui.home

import com.vara.mediatracker.data.badge.BadgeResult
import com.vara.mediatracker.data.badge.SmartBadge
import com.vara.mediatracker.data.model.MediaItemEntity

/**
 * Ranking rules for the Continue Watching rail.
 *
 * Extracted from the composable so the ordering can be tested directly. The top row
 * is a plain `take` of whatever this returns, which means the top five can only ever
 * be pinned if the ranking itself is stale — so the ranking is what gets tested.
 */
object HomeRanking {

    /** How many titles occupy the large top row before the remainder scrolls below. */
    const val TOP_ROW_SIZE = 5

    /** Upper bound on the rail so a large library cannot render hundreds of cards. */
    const val RAIL_LIMIT = 20

    /**
     * Badge priority. Higher wins.
     *
     * Time-sensitive badges outrank engagement badges so a premiere never loses its
     * slot to a show that merely has steady progress.
     */
    fun badgePriority(badge: SmartBadge?): Int = when (badge) {
        SmartBadge.PREMIERE -> 110
        SmartBadge.NEW -> 100
        SmartBadge.FINALE -> 95
        SmartBadge.BINGE_DROP -> 90
        SmartBadge.HOOKED -> 85
        SmartBadge.BEHIND -> 80
        SmartBadge.BINGE -> 75
        SmartBadge.SOON -> 70
        null -> 60
    }

    private val ACTIVE_STATES = setOf("watching", "active", "in progress")

    /**
     * Titles the user is actually part-way through.
     *
     * A title only qualifies for the rail through its badge if it is unreleased or
     * just released, which means a Wishlist film with a PREMIERE badge competes on
     * equal terms with something the user is mid-season on. Ranked purely by badge
     * that crowded genuinely-watched titles off the rail — on a library of this size
     * 66 in-progress titles were competing for the 9 slots left after the badged
     * entries.
     */
    fun isActivelyInProgress(item: MediaItemEntity): Boolean =
        item.state.lowercase() in ACTIVE_STATES

    /**
     * Orders the rail: badge priority first, then most recent interaction.
     *
     * The tie-break matters. Without it, titles with no badge all tie at 60 and the
     * order falls back to input order, which makes the top row look arbitrarily stuck
     * even though nothing is wrong with the badge data.
     */
    fun rank(
        candidates: List<MediaItemEntity>,
        badges: Map<String, BadgeResult?>
    ): List<MediaItemEntity> = candidates
        .sortedWith(
            // In-progress titles first, so a hot badge on a Watchlist item can never
            // evict something the user is part-way through.
            compareByDescending<MediaItemEntity> { if (isActivelyInProgress(it)) 1 else 0 }
                .thenByDescending { badgePriority(badges[it.id]?.badge) }
                .thenByDescending { it.lastInteractionDate ?: it.updatedAt ?: it.id }
        )
        .take(RAIL_LIMIT)

    /**
     * Splits a ranked list into the fixed-size hero row and the remainder.
     *
     * Both halves are derived from the same ranked list on every recomposition, so an
     * item that changes rank moves between rows rather than being pinned.
     */
    fun split(ranked: List<MediaItemEntity>): Pair<List<MediaItemEntity>, List<MediaItemEntity>> =
        ranked.take(TOP_ROW_SIZE) to ranked.drop(TOP_ROW_SIZE)
}
/** What the rail needs to know about a title's next unwatched episode. */
data class NextEpisodeInfo(
    val airDate: String?,
    val exists: Boolean
)

/**
 * Continue Watching eligibility.
 *
 * Pulled out of the composable because the two rules interact confusingly: the pool
 * admits a title on state or a radar badge, and the ready-now pass can then drop it
 * again. Keeping them in one place makes that sequence testable.
 */
object ContinueWatchingFilter {

    private val EXCLUDED_STATES = setOf("Completed", "Dropped", "On Hold", "Paused")
    private val ACTIVE_STATES = setOf("Watching", "Active", "In Progress")

    /** Badges that pull an unreleased-or-just-released title onto the rail. */
    private val RADAR_BADGES = setOf(
        SmartBadge.PREMIERE,
        SmartBadge.NEW,
        SmartBadge.BINGE_DROP,
        SmartBadge.FINALE
    )

    /**
     * Stage 1 — is this title a candidate at all?
     *
     * Actively-watching titles always qualify. Everything else needs a time-sensitive
     * badge and a release date that has already passed.
     */
    fun isCandidate(item: MediaItemEntity, badge: SmartBadge?, today: String): Boolean {
        if (item.isSoftDeleted) return false
        if (item.state.lowercase() in EXCLUDED_STATES.map { it.lowercase() }) return false

        if (item.state.lowercase() in ACTIVE_STATES.map { it.lowercase() }) return true

        if (badge !in RADAR_BADGES) return false
        val release = item.releaseDate?.take(10) ?: return false
        return release <= today
    }

    /**
     * Stage 2 — is the next thing to watch actually available yet?
     *
     * This is the stage that decides "Continue Watching" versus "Coming Soon". A title
     * whose next unwatched episode has not aired yet belongs in Coming Soon, not here.
     */
    fun isReadyNow(
        item: MediaItemEntity,
        next: NextEpisodeInfo,
        today: String
    ): Boolean {
        if (!item.type.contains("TV", ignoreCase = true)) {
            // Movies: already released, or no date known at all.
            val release = item.releaseDate?.take(10)
            return release == null || release <= today
        }
        if (!next.exists) {
            // No episodes stored yet; fall back to whether the show has released.
            val release = item.releaseDate?.take(10) ?: return false
            return release <= today
        }
        val air = next.airDate?.take(10) ?: return true
        return air <= today
    }
}
