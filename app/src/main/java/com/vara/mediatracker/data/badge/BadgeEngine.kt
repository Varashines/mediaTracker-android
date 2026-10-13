package com.vara.mediatracker.data.badge

import androidx.compose.ui.graphics.Color
import com.vara.mediatracker.data.model.EpisodeEntity
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.util.AirTimeResolver
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
    private const val BEHIND_SEASON_DAYS = 7.0
    private const val BEHIND_EPISODE_DAYS = 2.0
    private const val BINGE_ENGAGEMENT_COUNT = 3
    private const val BINGE_PROGRESS_RATIO = 0.20

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun parseDate(dateStr: String?): Date? {
        if (dateStr.isNullOrBlank()) return null
        return try {
            // SimpleDateFormat captures the default zone at construction, so a timezone
            // change mid-session would otherwise be ignored. Re-reading it per parse is a
            // field write, far cheaper than rebuilding the formatter for every episode.
            dateFormat.timeZone = TimeZone.getDefault()
            dateFormat.parse(dateStr.take(10))
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Resolves an episode's real airing instant, delegating to [AirTimeResolver] so the
     * resolution order matches macOS `DateUtils.parseEpisodeDate`: airstamp, else the
     * show timezone + airtime, else 8 PM ET. The previous Android fallback parsed a bare
     * date to the *device's* local midnight, which shifted the PREMIERE/NEW boundaries.
     */
    fun parseAirInstant(
        airDate: String?,
        airTime: String?,
        airstamp: String? = null,
        timezone: String? = null,
        serviceName: String? = null
    ): Date? = AirTimeResolver.resolveAirInstant(
        dateString = airDate?.take(10),
        time = airTime,
        airstamp = airstamp,
        timezone = timezone,
        serviceName = serviceName
    )

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
        val nextUpcomingAirDate: Date?,        // first future airing episode date
        val nextUpcomingEpisodeNumber: Int     // its episode number (1 == season premiere)
    )

    fun calculateBadge(
        item: MediaItemEntity,
        episodes: List<EpisodeEntity> = emptyList(),
        now: Date = Date(),
        declaredSeasonCounts: Map<Int, Int> = emptyMap()
    ): BadgeResult? {
        // Dropped items do not get smart badges
        if (item.state.equals("Dropped", ignoreCase = true)) return null

        val isTv = item.type.contains("TV", ignoreCase = true)
        val scan = if (isTv) {
            scanEpisodes(episodes, now, declaredSeasonCounts, item.timezone, AirTimeResolver.serviceHint(item.network, item.studios, item.genres))
        } else {
            null
        }

        // Pipeline stage 1: Milestone Badges (Premiere, Finale, Binge Drop)
        if (scan != null) {
            milestoneBadge(scan, now)?.let { return it }
        }

        // Pipeline stage 2: Release Window Badges (Premiere, New, Soon)
        releaseWindowBadge(item, isTv, scan?.nextAirDate, now)?.let { return it }

        // Pipeline stage 3: Engagement Badges (Hooked, Behind, Binge)
        if (scan != null) {
            engagementBadge(item, scan, now)?.let { return it }
        }

        return null
    }

    /**
     * @param declaredSeasonCounts season number -> TMDB's declared episode count.
     *   macOS reads this from `TVSeason.episodeCount` when evaluating FINALE; without
     *   it a partially-fetched season makes a mid-season episode look like a finale.
     *   Falls back to local row counts for seasons that have never been fetched.
     */
    fun scanEpisodes(
        episodes: List<EpisodeEntity>,
        now: Date,
        declaredSeasonCounts: Map<Int, Int> = emptyMap(),
        timezone: String? = null,
        serviceName: String? = null
    ): EpisodeScan {
        val cutoff = now.time - RECENT_WATCH_CUTOFF_MS

        // macOS excludes Specials (season 0) from the badge scan entirely — both
        // `batchScan` and `relationshipScan` filter `seasonNumber > 0` before building
        // seasonSources. Counting specials here inflated `remainingAired` and diluted
        // the BINGE progress ratio, since TVShowDetails.calculateProgress likewise
        // excludes them from total/watched.
        val sorted = episodes
            .filter { it.seasonNumber > 0 }
            .sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))

        var nextEpNum = 0
        var nextSeasonEpCount = 0
        var nextAir: Date? = null
        var sameDayCount = 0
        var recentlyWatched = 0
        var foundNext = false
        var airedCount = 0
        var watchedCount = 0
        var nextUpcomingAirDate: Date? = null
        var nextUpcomingEpNum = 0

        // Group by season to know episode count per season.
        val seasonRows = sorted.groupBy { it.seasonNumber }
        val seasonCounts = seasonRows.mapValues { it.value.size }

        // macOS totalEpisodesCount = Σ max(declared season length, stored rows) over the
        // seasons we hold rows for (TVShowDetails.calculateProgress). Counting rows alone
        // over-reported BINGE progress whenever a season was only partially fetched.
        val totalEpisodeCount = seasonRows.entries.sumOf { (season, eps) ->
            maxOf(declaredSeasonCounts[season] ?: 0, eps.size)
        }

        for (ep in sorted) {
            val epDate = parseAirInstant(ep.airDate, ep.airTime, ep.airstamp, timezone, serviceName)
            val isAired = epDate != null && epDate <= now

            if (ep.isWatched) {
                watchedCount++
                // macOS tests `ep.lastWatchedDate >= cutoff`. Falling back to updatedAt
                // here counted any episode touched by a metadata sync as "recently
                // watched", which could fabricate the HOOKED badge.
                val watchedDate = parseDate(ep.watchedDate)
                if (watchedDate != null && watchedDate.time >= cutoff) {
                    recentlyWatched++
                }
            } else {
                if (!foundNext) {
                    nextEpNum = ep.episodeNumber
                    // Prefer TMDB's declared length; fall back to rows we actually hold.
                    nextSeasonEpCount = declaredSeasonCounts[ep.seasonNumber]
                        ?: seasonCounts[ep.seasonNumber]
                        ?: 0
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
                nextUpcomingEpNum = ep.episodeNumber
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
            totalEpisodes = totalEpisodeCount,
            watchedEpisodes = watchedCount,
            airedEpisodesCount = airedCount,
            remainingAiredEpisodesCount = remainingAired,
            nextUpcomingAirDate = nextUpcomingAirDate,
            nextUpcomingEpisodeNumber = nextUpcomingEpNum
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

    /**
     * Resolves the instant a title's next airing happens.
     *
     * Matches macOS `cachedNextAiringDate` (`MediaItem+Sync`): the **first unwatched**
     * episode's air instant first, then the show's stored TVMaze next-episode instant,
     * then the release date. Preferring the TVMaze network episode (the old order) made
     * a show with a backlog report SOON off a future episode where macOS reports NEW off
     * the most recent unwatched aired episode.
     */
    private fun nextAiringInstant(item: MediaItemEntity, nextAir: Date?): Date? =
        nextAir ?: storedInstant(item) ?: parseDate(item.releaseDate)

    /** The persisted TVMaze instant, resolved through the shared air-time rules. */
    private fun storedInstant(item: MediaItemEntity): Date? =
        item.nextAiringInstant?.takeIf { it.isNotBlank() }?.let { iso ->
            AirTimeResolver.resolveAirInstant(
                dateString = iso.take(10),
                airstamp = iso,
                timezone = item.timezone,
                serviceName = AirTimeResolver.serviceHint(item.network, item.studios, item.genres)
            )
        }

    private fun releaseWindowBadge(
        item: MediaItemEntity,
        isTv: Boolean,
        nextAir: Date?,
        now: Date
    ): BadgeResult? {
        val airDate = nextAiringInstant(item, nextAir) ?: return null
        val timeToAir = airDate.time - now.time

        // Movies: PREMIERE badge when released within the past 3 days (matches macOS moviePremiereWindow
        // = -days3...greatestFiniteMagnitude) or airing in the future.
        //
        // The window is expressed against timeToAir, not against daysSinceRelease. The two are
        // negatives of each other, and checking `daysSinceRelease in 0..3` silently dropped the
        // entire future half of the range, so an upcoming movie in the Watchlist rendered no badge
        // at all. Compare timeToAir directly, as macOS does.
        // macOS guards on `item.type == .movie`. `isTv` is passed in rather than
        // recomputed here so the two call sites cannot drift apart.
        if (!isTv) {
            if (timeToAir >= -THREE_DAYS_MS) {
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
        // Must have aired episodes that haven't been watched yet; otherwise caught up.
        if (scan.remainingAiredEpisodesCount <= 0) return null

        // HOOKED: watched 3+ episodes in the last 48 hours.
        if (scan.recentlyWatchedCount >= BINGE_ENGAGEMENT_COUNT) {
            return BadgeResult(SmartBadge.HOOKED, isSparkle = true)
        }

        // BEHIND: the next release is close and there is a backlog to clear. A season
        // premiere earns a week's notice; a regular episode only two days, since episodes
        // air frequently. Taste-agnostic — a backlog already signals the user is engaged.
        val nextUpcoming = scan.nextUpcomingAirDate ?: storedInstant(item)
        if (nextUpcoming != null) {
            val daysToAir = (nextUpcoming.time - now.time).toDouble() / MS_PER_DAY.toDouble()
            val threshold =
                if (scan.nextUpcomingEpisodeNumber == 1) BEHIND_SEASON_DAYS else BEHIND_EPISODE_DAYS
            if (daysToAir > 0.0 && daysToAir <= threshold) {
                return BadgeResult(SmartBadge.BEHIND, isSparkle = false)
            }
        }

        // BINGE stays gated on taste so a backlog alone does not nag every show.
        val isLikedOrLoved = item.taste.equals("Loved", ignoreCase = true) ||
                             item.taste.equals("Love", ignoreCase = true) ||
                             item.taste.equals("Liked", ignoreCase = true) ||
                             item.taste.equals("Like", ignoreCase = true)

        if (!isLikedOrLoved) return null

        // BINGE: steady 20%+ progress through the show.
        if (scan.totalEpisodes > 0) {
            val progress = scan.watchedEpisodes.toDouble() / scan.totalEpisodes.toDouble()
            if (progress >= BINGE_PROGRESS_RATIO) {
                return BadgeResult(SmartBadge.BINGE, isSparkle = false)
            }
        }

        return null
    }
}
