package com.vara.mediatracker.data.badge

import com.vara.mediatracker.data.db.AppDatabase
import com.vara.mediatracker.data.model.EpisodeEntity
import com.vara.mediatracker.data.model.MediaBadgeEntity
import com.vara.mediatracker.data.model.MediaIds
import com.vara.mediatracker.data.model.MediaItemEntity
import java.util.Date

/**
 * Recomputes and persists smart-badge state for the whole library.
 *
 * Badges are a function of *time*, not just of data: a show's next episode moves
 * from SOON to airing to NEW to BINGE as the clock advances, with no new data
 * arriving at all. That is why this is driven by its own schedule rather than only
 * being recomputed after a cloud sync.
 *
 * Running it off the UI path also matters for performance: the read-time version ran
 * BadgeEngine across every title and every episode inside a `remember` block on the
 * main thread, which on a library of this size is thousands of scans per composition.
 */
object BadgeComputer {

    /**
     * @return number of titles carrying a badge after the pass.
     */
    suspend fun recompute(
        db: AppDatabase,
        now: Date = Date()
    ): Int {
        val mediaDao = db.mediaDao()
        val badgeDao = db.badgeDao()

        val media = mediaDao.getAllMediaList().filter { !it.isSoftDeleted }
        if (media.isEmpty()) {
            badgeDao.deleteAllBadges()
            return 0
        }

        val episodes = mediaDao.getAllEpisodesList()
        val byShow = episodes.groupBy { it.showId }

        // TMDB's declared season lengths, so FINALE does not depend on how many
        // episode rows happen to be stored locally.
        val declaredByShow = db.seasonDao().getAllSeasons()
            .filter { it.episodeCount > 0 }
            .groupBy { it.showId }
            .mapValues { (_, rows) -> rows.associate { it.seasonNumber to it.episodeCount } }

        val computedAt = now.time
        val badges = media.mapNotNull { item ->
            badgeFor(item, byShow, declaredByShow, now, computedAt)
        }

        badgeDao.upsertBadges(badges)
        // Titles that stopped qualifying, or vanished from the library, must not keep
        // a stale row — otherwise the UI would keep rendering last week's badge.
        badgeDao.pruneOrphanedBadges()

        return badgeDao.badgeCount()
    }

    private fun badgeFor(
        item: MediaItemEntity,
        byShow: Map<String, List<EpisodeEntity>>,
        declaredByShow: Map<String, Map<Int, Int>>,
        now: Date,
        computedAt: Long
    ): MediaBadgeEntity? {
        val isTv = item.type.contains("TV", ignoreCase = true)
        val showKey = if (isTv) MediaIds.tvShowKey(item.id) else null
        val showEpisodes = showKey?.let { byShow[it].orEmpty() }.orEmpty()
        val declared = showKey?.let { declaredByShow[it] }.orEmpty()

        val result = BadgeEngine.calculateBadge(item, showEpisodes, now, declared) ?: return null

        return MediaBadgeEntity(
            mediaId = item.id,
            badgeLabel = result.badge.label,
            isSparkle = result.isSparkle,
            targetDate = targetDateFor(item, showEpisodes, isTv),
            computedAt = computedAt
        )
    }

    /**
     * The air/release date the badge is measured against.
     *
     * For TV this is the next unwatched episode's air date rather than the show's
     * original release date, which is what makes "newly SOON" distinguishable from
     * "still SOON" when deciding whether to notify.
     */
    private fun targetDateFor(
        item: MediaItemEntity,
        episodes: List<EpisodeEntity>,
        isTv: Boolean
    ): String? {
        if (!isTv) return item.releaseDate
        return episodes
            .filter { !it.isWatched }
            .sortedWith(compareBy({ it.airDate == null }, { it.airDate }))
            .firstOrNull { !it.airDate.isNullOrBlank() }
            ?.airDate
    }
}