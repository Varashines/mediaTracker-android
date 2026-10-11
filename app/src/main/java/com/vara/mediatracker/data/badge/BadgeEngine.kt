package com.vara.mediatracker.data.badge

import androidx.compose.ui.graphics.Color
import com.vara.mediatracker.data.model.EpisodeEntity
import com.vara.mediatracker.data.model.MediaItemEntity
import java.text.SimpleDateFormat
import java.util.*

enum class SmartBadge(
    val label: String,
    val backgroundColor: Color,
    val textColor: Color = Color.White
) {
    PREMIERE("PREMIERE", Color(0xFFD946EF)),    // Vivid Magenta
    FINALE("FINALE", Color(0xFFF43F5E)),        // Deep Rose
    BINGE_DROP("BINGE DROP", Color(0xFF06B6D4)),// Cyan
    NEW("NEW", Color(0xFF10B981)),              // Emerald Green
    SOON("SOON", Color(0xFFF59E0B)),            // Amber / Orange
    HOOKED("HOOKED", Color(0xFFFB7185)),        // Fiery Coral
    BEHIND("BEHIND", Color(0xFF6366F1)),        // Indigo
    BINGE("BINGE", Color(0xFF8B5CF6));          // Purple

    companion object {
        val radarBadges: Set<SmartBadge> = setOf(NEW, BINGE_DROP, PREMIERE, FINALE, HOOKED)

        fun fromLabel(label: String?): SmartBadge? {
            return values().firstOrNull { it.label.equals(label, ignoreCase = true) }
        }
    }
}

data class BadgeResult(
    val badge: SmartBadge,
    val isSparkle: Boolean = false
)

object BadgeEngine {
    private const val MS_PER_DAY = 86_400_000L

    // Windows strictly matching macOS BadgeEngine.swift:
    // - moviePremiereWindow: -TimeInterval.days3...Double.greatestFiniteMagnitude
    //   Note: timeToAir = airDate.timeIntervalSince(now).
    //   So release in the future has timeToAir > 0.
    //   Release in the past up to 3 days has timeToAir between -3 days and 0.
    // - premiereDaysWindow: -Double.greatestFiniteMagnitude...3
    //   Note: daysSinceAir = now.timeIntervalSince(airDate) / .secondsInDay.
    //   So up to 3 days after premiere, daysSinceAir <= 3.0.
    private const val RECENT_WATCH_CUTOFF_MS = 2 * MS_PER_DAY
    private const val THREE_DAYS_MS = 3 * MS_PER_DAY
    private const val NEW_WINDOW_MS = 14 * MS_PER_DAY
    private const val SOON_WINDOW_MS = 2 * MS_PER_DAY
    private const val PREMIERE_DAYS_MAX = 3.0
    private const val FINALE_DAYS_MIN = -7.0
    private const val FINALE_DAYS_MAX = 14.0
    private const val MILESTONE_DAYS = 14.0
    private const val BEHIND_DAYS = 7.0
    private const val BINGE_ENGAGEMENT_COUNT = 3
    private const val BINGE_PROGRESS_RATIO = 0.20

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun parseDate(dateStr: String?): Date? {
        if (dateStr.isNullOrBlank()) return null
        return try {
            dateFormat.parse(dateStr.take(10))
        } catch (_: Exception) {
            null
        }
    }

    data class EpisodeScan(
        val nextEpisodeNumber: Int,
        val nextSeasonEpisodeCount: Int,
        val nextAirDate: Date?,
        val airedOnSameDayCount: Int,
        val recentlyWatchedCount: Int,
        val totalEpisodes: Int,
        val watchedEpisodes: Int,
        val airedEpisodesCount: Int,
        val remainingAiredEpisodesCount: Int,
        val nextUpcomingAirDate: Date? // First future airing episode date
    )

    fun calculateBadge(
        item: MediaItemEntity,
        episodes: List<EpisodeEntity> = emptyList(),
        now: Date = Date()
    ): BadgeResult? {
        // Dropped items do not get smart badges
        if (item.state.equals("Dropped", ignoreCase = true)) return null

        val isTv = item.type.contains("TV", ignoreCase = true)
        val scan = if (isTv) scanEpisodes(episodes, now) else null

        // Pipeline stage 1: Milestone Badges (Premiere, Finale, Binge Drop)
        if (scan != null) {
            milestoneBadge(scan, now)?.let { return it }
        }

        // Pipeline stage 2: Release Window Badges (Premiere, New, Soon)
        releaseWindowBadge(item, scan?.nextAirDate, now)?.let { return it }

        // Pipeline stage 3: Engagement Badges (Hooked, Behind, Binge)
        if (scan != null) {
            engagementBadge(item, scan, now)?.let { return it }
        }

        return null
    }

    fun scanEpisodes(episodes: List<EpisodeEntity>, now: Date): EpisodeScan {
        val cutoff = now.time - RECENT_WATCH_CUTOFF_MS
        val sorted = episodes.sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))

        var nextEpNum = 0
        var nextSeasonEpCount = 0
        var nextAir: Date? = null
        var sameDayCount = 0
        var recentlyWatched = 0
        var foundNext = false
        var airedCount = 0
        var watchedCount = 0
        var nextUpcomingAirDate: Date? = null

        // Group by season to know episode count per season
        val seasonCounts = sorted.groupBy { it.seasonNumber }.mapValues { it.value.size }

        for (ep in sorted) {
            val epDate = parseDate(ep.airDate)
            val isAired = epDate != null && epDate <= now

            if (ep.isWatched) {
                watchedCount++
                val watchedDate = parseDate(ep.watchedDate) ?: parseDate(ep.updatedAt)
                if (watchedDate != null && watchedDate.time >= cutoff) {
                    recentlyWatched++
                }
            } else {
                if (!foundNext) {
                    nextEpNum = ep.episodeNumber
                    nextSeasonEpCount = seasonCounts[ep.seasonNumber] ?: 0
                    nextAir = epDate
                    foundNext = true
                    sameDayCount = 1
                } else if (epDate != null && nextAir != null) {
                    val cal1 = Calendar.getInstance().apply { time = epDate }
                    val cal2 = Calendar.getInstance().apply { time = nextAir }
                    if (cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                        cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)) {
                        sameDayCount++
                    }
                }
            }

            if (isAired) {
                airedCount++
            } else if (epDate != null && epDate > now && nextUpcomingAirDate == null) {
                nextUpcomingAirDate = epDate
            }
        }

        // Remaining aired episodes = already aired but unwatched
        val remainingAired = maxOf(0, airedCount - watchedCount)

        return EpisodeScan(
            nextEpisodeNumber = nextEpNum,
            nextSeasonEpisodeCount = nextSeasonEpCount,
            nextAirDate = nextAir,
            airedOnSameDayCount = sameDayCount,
            recentlyWatchedCount = recentlyWatched,
            totalEpisodes = sorted.size,
            watchedEpisodes = watchedCount,
            airedEpisodesCount = airedCount,
            remainingAiredEpisodesCount = remainingAired,
            nextUpcomingAirDate = nextUpcomingAirDate
        )
    }

    private fun milestoneBadge(scan: EpisodeScan, now: Date): BadgeResult? {
        val airDate = scan.nextAirDate ?: return null
        val daysSinceAir = (now.time - airDate.time).toDouble() / MS_PER_DAY.toDouble()

        // S1 E1 or Season Premiere:
        // daysSinceAir <= 3 days (matches macOS premiereDaysWindow: -Double.greatestFiniteMagnitude...3)
        // This covers both upcoming premieres and premieres that aired within the last 3 days!
        if (scan.nextEpisodeNumber == 1 && daysSinceAir <= PREMIERE_DAYS_MAX) {
            return BadgeResult(SmartBadge.PREMIERE, isSparkle = true)
        }

        // Season Finale: next unwatched episode is last episode of the season (-7 to 14 days)
        val isFinaleWindow = daysSinceAir >= FINALE_DAYS_MIN && daysSinceAir <= FINALE_DAYS_MAX
        if (scan.nextSeasonEpisodeCount > 0 && scan.nextEpisodeNumber == scan.nextSeasonEpisodeCount && isFinaleWindow) {
            return BadgeResult(SmartBadge.FINALE, isSparkle = true)
        }

        // Binge Drop: multiple episodes aired on the exact same day
        val inMilestone = Math.abs(daysSinceAir) <= MILESTONE_DAYS
        if (inMilestone && scan.airedOnSameDayCount > 1) {
            return BadgeResult(SmartBadge.BINGE_DROP, isSparkle = true)
        }

        return null
    }

    private fun releaseWindowBadge(item: MediaItemEntity, nextAir: Date?, now: Date): BadgeResult? {
        val airDate = nextAir ?: parseDate(item.releaseDate) ?: return null
        val timeToAir = airDate.time - now.time

        // Movies: PREMIERE badge when released within the past 3 days (matches macOS premiere window -3 days..0)
        // or upcoming premiere if releasing in future
        if (!item.type.contains("TV", ignoreCase = true)) {
            val daysSinceRelease = (now.time - airDate.time).toDouble() / MS_PER_DAY.toDouble()
            if (daysSinceRelease in 0.0..3.0) {
                return BadgeResult(SmartBadge.PREMIERE, isSparkle = true)
            }
        }

        // NEW: released within past 14 days (airDate in the past up to 14 days)
        if (timeToAir in -NEW_WINDOW_MS..0) {
            return BadgeResult(SmartBadge.NEW, isSparkle = true)
        }

        // SOON: releasing in next 48 hours
        if (timeToAir in 0..SOON_WINDOW_MS) {
            return BadgeResult(SmartBadge.SOON, isSparkle = false)
        }

        return null
    }

    private fun engagementBadge(item: MediaItemEntity, scan: EpisodeScan, now: Date): BadgeResult? {
        // Must have episodes already aired that haven't been watched yet!
        // If remainingAiredEpisodesCount == 0, the user is completely caught up!
        if (scan.remainingAiredEpisodesCount <= 0) return null

        // HOOKED: watched 3+ episodes in past 48 hours
        if (scan.recentlyWatchedCount >= BINGE_ENGAGEMENT_COUNT) {
            return BadgeResult(SmartBadge.HOOKED, isSparkle = true)
        }

        val isLikedOrLoved = item.taste.equals("Loved", ignoreCase = true) ||
                             item.taste.equals("Love", ignoreCase = true) ||
                             item.taste.equals("Liked", ignoreCase = true) ||
                             item.taste.equals("Like", ignoreCase = true)

        if (!isLikedOrLoved) return null

        // BEHIND: User has unwatched aired episodes AND a new episode airs within 7 days
        val nextUpcomingAir = scan.nextUpcomingAirDate
        if (nextUpcomingAir != null) {
            val daysToAir = (nextUpcomingAir.time - now.time).toDouble() / MS_PER_DAY.toDouble()
            if (daysToAir in 0.0..BEHIND_DAYS) {
                return BadgeResult(SmartBadge.BEHIND, isSparkle = false)
            }
        }

        // BINGE: user has made steady 20%+ progress through the show
        if (scan.totalEpisodes > 0) {
            val progress = scan.watchedEpisodes.toDouble() / scan.totalEpisodes.toDouble()
            if (progress >= BINGE_PROGRESS_RATIO) {
                return BadgeResult(SmartBadge.BINGE, isSparkle = false)
            }
        }

        return null
    }
}
