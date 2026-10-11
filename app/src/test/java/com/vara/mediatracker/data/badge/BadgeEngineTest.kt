package com.vara.mediatracker.data.badge

import com.vara.mediatracker.data.model.EpisodeEntity
import com.vara.mediatracker.data.model.MediaIds
import com.vara.mediatracker.data.model.MediaItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Locks the three-stage badge pipeline to the macOS BadgeEngine windows.
 *
 * Stage order is significant: milestone beats release-window beats engagement, so
 * several of these cases deliberately construct episodes whose air dates would
 * otherwise qualify for an earlier stage.
 */
class BadgeEngineTest {

    private val now = Date()
    private val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** "yyyy-MM-dd" for `now` shifted by [days]. */
    private fun day(days: Long): String {
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, days.toInt()) }
        return sdf.format(cal.time)
    }

    private fun movie(
        releaseDate: String? = null,
        state: String = "Watching",
        taste: String = "None",
        type: String = "Movie"
    ) = MediaItemEntity(
        id = "movie_550",
        title = "Some Movie",
        type = type,
        state = state,
        taste = taste,
        releaseDate = releaseDate
    )

    private fun show(state: String = "Watching", taste: String = "None") =
        MediaItemEntity(id = "tv_1399", title = "Some Show", type = "TV Show", state = state, taste = taste)

    private fun ep(
        season: Int,
        number: Int,
        watched: Boolean = false,
        airDate: String? = null,
        watchedDate: String? = null
    ) = EpisodeEntity(
        uniqueId = MediaIds.buildEpisodeUniqueId("tv_1399", season, number),
        showId = "tv_1399",
        seasonNumber = season,
        episodeNumber = number,
        isWatched = watched,
        airDate = airDate,
        watchedDate = watchedDate
    )

    private fun badgeFor(item: MediaItemEntity, episodes: List<EpisodeEntity> = emptyList()): SmartBadge? =
        BadgeEngine.calculateBadge(item, episodes, now)?.badge

    // ---------- movie release window ----------

    @Test
    fun `dropped items never receive a badge`() {
        assertNull(badgeFor(movie(releaseDate = day(5), state = "Dropped")))
    }

    @Test
    fun `movie releasing in the future is PREMIERE`() {
        assertEquals(SmartBadge.PREMIERE, badgeFor(movie(releaseDate = day(5))))
    }

    @Test
    fun `movie stays PREMIERE for three days after release`() {
        // macOS moviePremiereWindow is -3 days ... infinity
        assertEquals(SmartBadge.PREMIERE, badgeFor(movie(releaseDate = day(-2))))
    }

    @Test
    fun `movie four days past release drops out of PREMIERE into NEW`() {
        assertEquals(SmartBadge.NEW, badgeFor(movie(releaseDate = day(-4))))
    }

    @Test
    fun `movie ten days past release is NEW`() {
        assertEquals(SmartBadge.NEW, badgeFor(movie(releaseDate = day(-10))))
    }

    @Test
    fun `movie older than the NEW window gets no badge`() {
        assertNull(badgeFor(movie(releaseDate = day(-30))))
    }

    @Test
    fun `movies never reach SOON because the premiere window swallows it`() {
        // timeToAir of +48h satisfies `>= -3 days`, so PREMIERE wins before SOON is considered.
        assertEquals(SmartBadge.PREMIERE, badgeFor(movie(releaseDate = day(2))))
    }

    // ---------- TV milestone stage ----------

    @Test
    fun `next episode one is PREMIERE regardless of how far out it airs`() {
        val episodes = listOf(
            ep(1, 1, watched = true, airDate = day(-30), watchedDate = day(-30))
        ) + ep(2, 1, airDate = day(20))
        // next unwatched is S2E1 -> season premiere, daysSinceAir is negative
        assertEquals(SmartBadge.PREMIERE, badgeFor(show(), episodes))
    }

    @Test
    fun `premiere that aired recently is still PREMIERE`() {
        val episodes = listOf(ep(1, 1, airDate = day(-1)))
        assertEquals(SmartBadge.PREMIERE, badgeFor(show(), episodes))
    }

    @Test
    fun `premiere loses PREMIERE after three days`() {
        // Season of three so E1 is the next unwatched but not the season's last,
        // which would otherwise resolve as FINALE instead.
        val episodes = listOf(
            ep(1, 1, airDate = day(-5)),
            ep(1, 2, airDate = day(20)),
            ep(1, 3, airDate = day(20))
        )
        // No milestone; falls through to the release window (NEW covers up to 14 days)
        assertEquals(SmartBadge.NEW, badgeFor(show(), episodes))
    }

    @Test
    fun `last episode of a season within the finale window is FINALE`() {
        val episodes = listOf(
            ep(1, 1, watched = true, airDate = day(-30), watchedDate = day(-30)),
            ep(1, 2, watched = true, airDate = day(-30), watchedDate = day(-30)),
            ep(1, 3, airDate = day(5))
        )
        // next is S1E3 and the season has 3 episodes
        assertEquals(SmartBadge.FINALE, badgeFor(show(), episodes))
    }

    @Test
    fun `finale outside its window falls through`() {
        val episodes = listOf(
            ep(1, 1, watched = true, airDate = day(-90), watchedDate = day(-90)),
            ep(1, 2, watched = true, airDate = day(-90), watchedDate = day(-90)),
            ep(1, 3, airDate = day(-40))
        )
        // daysSinceAir = 40 is past FINALE_DAYS_MAX (14), and past NEW (14)
        assertNull(badgeFor(show(), episodes))
    }

    @Test
    fun `multiple episodes airing the same day is BINGE DROP`() {
        val episodes = listOf(
            ep(1, 1, watched = true, airDate = day(-30), watchedDate = day(-30)),
            ep(1, 2, airDate = day(5)),
            ep(1, 3, airDate = day(5)),
            ep(1, 4, watched = true, airDate = day(-30), watchedDate = day(-30))
        )
        // next is S1E2 (not a premiere, not the last of 4), two episodes share day(5)
        assertEquals(SmartBadge.BINGE_DROP, badgeFor(show(), episodes))
    }

    @Test
    fun `same day drops outside the milestone window fall through`() {
        val episodes = listOf(
            ep(1, 1, watched = true, airDate = day(-90), watchedDate = day(-90)),
            ep(1, 2, airDate = day(-60)),
            ep(1, 3, airDate = day(-60)),
            ep(1, 4, watched = true, airDate = day(-90), watchedDate = day(-90))
        )
        assertNull(badgeFor(show(), episodes))
    }

    // ---------- TV release window ----------

    @Test
    fun `tv airing inside 48 hours is SOON`() {
        // Five episodes so the next unwatched one is E4: not a premiere, not the
        // season's last episode, and no same-day cluster.
        val episodes = listOf(
            ep(1, 1, watched = true, airDate = day(-30), watchedDate = day(-30)),
            ep(1, 2, watched = true, airDate = day(-30), watchedDate = day(-30)),
            ep(1, 3, watched = true, airDate = day(-30), watchedDate = day(-30)),
            ep(1, 4, airDate = day(1)),
            ep(1, 5, airDate = day(20))
        )
        assertEquals(SmartBadge.SOON, badgeFor(show(), episodes))
    }

    @Test
    fun `tv aired inside the NEW window is NEW`() {
        val episodes = listOf(
            ep(1, 1, watched = true, airDate = day(-30), watchedDate = day(-30)),
            ep(1, 2, watched = true, airDate = day(-30), watchedDate = day(-30)),
            ep(1, 3, watched = true, airDate = day(-30), watchedDate = day(-30)),
            ep(1, 4, airDate = day(-5)),
            ep(1, 5, airDate = day(20))
        )
        assertEquals(SmartBadge.NEW, badgeFor(show(), episodes))
    }

    // ---------- engagement stage ----------

    @Test
    fun `three episodes watched in the last two days is HOOKED`() {
        val episodes = listOf(
            ep(1, 1, watched = true, airDate = day(-40), watchedDate = day(0)),
            ep(1, 2, watched = true, airDate = day(-40), watchedDate = day(0)),
            ep(1, 3, watched = true, airDate = day(-40), watchedDate = day(0)),
            ep(1, 4, airDate = day(-20)), // next unwatched, well outside NEW/SOON
            ep(1, 5, airDate = day(30))
        )
        assertEquals(SmartBadge.HOOKED, badgeFor(show(), episodes))
    }

    @Test
    fun `two recent episodes is not enough for HOOKED`() {
        val episodes = listOf(
            ep(1, 1, watched = true, airDate = day(-40), watchedDate = day(0)),
            ep(1, 2, watched = true, airDate = day(-40), watchedDate = day(0)),
            ep(1, 3, airDate = day(-20)),
            ep(1, 4, airDate = day(30))
        )
        assertNull(badgeFor(show(), episodes))
    }

    @Test
    fun `liked show with an episode airing within a week is BEHIND`() {
        val episodes = listOf(
            ep(1, 1, watched = true, airDate = day(-60), watchedDate = day(-60)),
            ep(1, 2, watched = true, airDate = day(-60), watchedDate = day(-60)),
            ep(1, 3, airDate = day(-20)), // next unwatched, outside NEW/SOON
            ep(1, 4, airDate = day(3))    // first future airing
        )
        assertEquals(SmartBadge.BEHIND, badgeFor(show(taste = "Liked"), episodes))
    }

    @Test
    fun `unliked show with an episode airing soon is not BEHIND`() {
        val episodes = listOf(
            ep(1, 1, watched = true, airDate = day(-60), watchedDate = day(-60)),
            ep(1, 2, watched = true, airDate = day(-60), watchedDate = day(-60)),
            ep(1, 3, airDate = day(-20)),
            ep(1, 4, airDate = day(3))
        )
        assertNull(badgeFor(show(taste = "None"), episodes))
    }

    @Test
    fun `steady progress on a liked show is BINGE`() {
        val episodes = listOf(
            ep(1, 1, watched = true, airDate = day(-30), watchedDate = day(-30)),
            ep(1, 2, watched = true, airDate = day(-30), watchedDate = day(-30)),
            ep(1, 3, airDate = day(-30)),
            ep(1, 4, airDate = day(-30)),
            ep(1, 5, airDate = day(-30))
        )
        // 2/5 watched = 40% >= 20%, nothing airing in the future so no BEHIND
        assertEquals(SmartBadge.BINGE, badgeFor(show(taste = "Loved"), episodes))
    }

    @Test
    fun `fully caught up show gets no engagement badge`() {
        val episodes = listOf(
            ep(1, 1, watched = true, airDate = day(-30), watchedDate = day(-30)),
            ep(1, 2, watched = true, airDate = day(-30), watchedDate = day(-30)),
            ep(1, 3, watched = true, airDate = day(-30), watchedDate = day(-30))
        )
        // remainingAiredEpisodesCount == 0, so engagement stage short circuits
        assertNull(badgeFor(show(taste = "Loved"), episodes))
    }

    // ---------- scanEpisodes ----------

    @Test
    fun `scan counts aired, watched and remaining correctly`() {
        val episodes = listOf(
            ep(1, 1, watched = true, airDate = day(-40), watchedDate = day(-40)),
            ep(1, 2, watched = true, airDate = day(-40), watchedDate = day(-40)),
            ep(1, 3, airDate = day(-10)),  // aired, unwatched
            ep(1, 4, airDate = day(10))   // future
        )
        val scan = BadgeEngine.scanEpisodes(episodes, now)
        assertEquals(4, scan.totalEpisodes)
        assertEquals(2, scan.watchedEpisodes)
        assertEquals(3, scan.airedEpisodesCount)
        assertEquals(1, scan.remainingAiredEpisodesCount)
        assertEquals(3, scan.nextEpisodeNumber)
        assertNotNull(scan.nextUpcomingAirDate)
    }

    @Test
    fun `scan handles an empty episode list`() {
        val scan = BadgeEngine.scanEpisodes(emptyList(), now)
        assertEquals(0, scan.totalEpisodes)
        assertEquals(0, scan.remainingAiredEpisodesCount)
    }

    @Test
    fun `scan orders seasons and episodes correctly`() {
        val shuffled = listOf(
            ep(2, 1, airDate = day(10)),
            ep(1, 2, airDate = day(10)),
            ep(1, 1, watched = true, airDate = day(-30), watchedDate = day(-30))
        )
        val scan = BadgeEngine.scanEpisodes(shuffled, now)
        assertEquals(2, scan.nextEpisodeNumber)
        assertEquals(2, scan.nextSeasonEpisodeCount)
    }

    @Test
    fun `parseDate tolerates malformed and blank input`() {
        assertNull(BadgeEngine.parseDate(null))
        assertNull(BadgeEngine.parseDate(""))
        assertNull(BadgeEngine.parseDate("not-a-date"))
        assertNotNull(BadgeEngine.parseDate("2026-01-15"))
        // extra trailing content is truncated to the date portion
        assertNotNull(BadgeEngine.parseDate("2026-01-15T10:30:00Z"))
    }

    @Test
    fun `radar badges exclude engagement-only badges`() {
        assertEquals(
            setOf(SmartBadge.NEW, SmartBadge.BINGE_DROP, SmartBadge.PREMIERE, SmartBadge.FINALE, SmartBadge.HOOKED),
            SmartBadge.radarBadges
        )
    }

    @Test
    fun `fromLabel is case insensitive`() {
        assertEquals(SmartBadge.BINGE_DROP, SmartBadge.fromLabel("binge drop"))
        assertEquals(SmartBadge.BINGE_DROP, SmartBadge.fromLabel("BINGE DROP"))
        assertNull(SmartBadge.fromLabel("NOT A BADGE"))
        assertNull(SmartBadge.fromLabel(null))
    }
}
/**
 * Parity with the macOS BadgeEngine, which is the source of truth.
 *
 * Each test here corresponds to a specific divergence found while diffing
 * `Sources/MediaTracker/Services/BadgeEngine.swift` against this engine.
 */
class BadgeEngineMacParityTest {

    private val now = Date()
    private val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun day(offset: Long): String {
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, offset.toInt()) }
        return sdf.format(cal.time)
    }

    private fun show(state: String = "Watching", taste: String = "None") =
        MediaItemEntity(id = "tv_1399", title = "Show", type = "TV Show", state = state, taste = taste)

    private fun ep(
        season: Int,
        number: Int,
        watched: Boolean = false,
        airDate: String? = null,
        watchedDate: String? = null,
        updatedAt: String? = null
    ) = EpisodeEntity(
        uniqueId = MediaIds.buildEpisodeUniqueId("tv_1399", season, number),
        showId = "tv_1399",
        seasonNumber = season,
        episodeNumber = number,
        isWatched = watched,
        airDate = airDate,
        watchedDate = watchedDate,
        updatedAt = updatedAt
    )

    @Test
    fun `specials season 0 is excluded from the scan`() {
        // macOS filters `seasonNumber > 0` in both batchScan and relationshipScan.
        val episodes = listOf(
            ep(0, 1, watched = false, airDate = day(-5)),
            ep(0, 2, watched = false, airDate = day(-5))
        )

        val scan = BadgeEngine.scanEpisodes(episodes, now)

        assertEquals("specials must not count toward totals", 0, scan.totalEpisodes)
        assertEquals(0, scan.nextEpisodeNumber)
        assertNull(scan.nextAirDate)
    }

    @Test
    fun `specials cannot satisfy the premiere rule`() {
        val episodes = listOf(ep(0, 1, watched = false, airDate = day(1)))

        assertNull(BadgeEngine.calculateBadge(show(), episodes, now))
    }

    @Test
    fun `watched specials do not dilute binge progress`() {
        // 1 of 2 non-special episodes watched = 50%, which clears the 20% threshold.
        // Counting two watched specials would have dragged it to 25% of four.
        val episodes = listOf(
            ep(1, 1, watched = true, airDate = day(-30), watchedDate = day(-30)),
            ep(1, 2, watched = false, airDate = day(-30)),
            ep(0, 1, watched = true, airDate = day(-30), watchedDate = day(-30)),
            ep(0, 2, watched = true, airDate = day(-30), watchedDate = day(-30))
        )

        val scan = BadgeEngine.scanEpisodes(episodes, now)

        assertEquals(2, scan.totalEpisodes)
        assertEquals(1, scan.watchedEpisodes)
        assertEquals(0.5, scan.watchedEpisodes.toDouble() / scan.totalEpisodes, 0.001)
    }

    @Test
    fun `updatedAt is not treated as a watch date`() {
        // A metadata sync bumps updatedAt on every row. Using it as a fallback made
        // long-finished shows look "recently watched" and fabricated HOOKED.
        val episodes = listOf(
            ep(1, 1, watched = true, airDate = day(-90), watchedDate = day(-90), updatedAt = day(0)),
            ep(1, 2, watched = true, airDate = day(-90), watchedDate = day(-90), updatedAt = day(0)),
            ep(1, 3, watched = true, airDate = day(-90), watchedDate = day(-90), updatedAt = day(0)),
            ep(1, 4, watched = false, airDate = day(-40)),
            ep(1, 5, watched = false, airDate = day(30))
        )

        val scan = BadgeEngine.scanEpisodes(episodes, now)

        assertEquals("updatedAt must not count as a recent watch", 0, scan.recentlyWatchedCount)
        assertNull(BadgeEngine.calculateBadge(show(), episodes, now))
    }

    @Test
    fun `watchedDate within two days still counts toward HOOKED`() {
        val episodes = listOf(
            ep(1, 1, watched = true, airDate = day(-90), watchedDate = day(0)),
            ep(1, 2, watched = true, airDate = day(-90), watchedDate = day(0)),
            ep(1, 3, watched = true, airDate = day(-90), watchedDate = day(0)),
            ep(1, 4, watched = false, airDate = day(-40)),
            ep(1, 5, watched = false, airDate = day(30))
        )

        assertEquals(SmartBadge.HOOKED, BadgeEngine.calculateBadge(show(), episodes, now)?.badge)
    }

    @Test
    fun `remaining count ignores specials`() {
        // One aired unwatched regular episode plus a watched special. macOS computes
        // remaining as aired - watched over non-special seasons only.
        val episodes = listOf(
            ep(1, 1, watched = true, airDate = day(-30), watchedDate = day(-30)),
            ep(1, 2, watched = false, airDate = day(-30)),
            ep(0, 1, watched = true, airDate = day(-30), watchedDate = day(-30))
        )

        val scan = BadgeEngine.scanEpisodes(episodes, now)

        assertEquals(2, scan.airedEpisodesCount)
        assertEquals(1, scan.watchedEpisodes)
        assertEquals(1, scan.remainingAiredEpisodesCount)
    }

    @Test
    fun `movie premiere window still covers future releases`() {
        // Regression guard: the window is -3 days ... +infinity on macOS.
        val movie = MediaItemEntity(
            id = "movie_1", title = "Film", type = "Movie",
            state = "Wishlist", releaseDate = day(45)
        )
        assertEquals(SmartBadge.PREMIERE, BadgeEngine.calculateBadge(movie, emptyList(), now)?.badge)
    }

    @Test
    fun `tv item with movie-style release date does not take the movie branch`() {
        // isTv is threaded from calculateBadge, so a TV show can never be treated as
        // a movie even when it carries a releaseDate.
        val tv = MediaItemEntity(
            id = "tv_1399", title = "Show", type = "TV Show",
            state = "Watching", releaseDate = day(45)
        )
        assertNull(BadgeEngine.calculateBadge(tv, emptyList(), now))
    }
}

/**
 * FINALE must use TMDB's declared season length, matching macOS's
 * `scan.nextEpisodeNumber == season.episodeCount`.
 *
 * Counting local rows instead turns any mid-season episode into a false finale as
 * soon as a show's seasons are only partially fetched.
 */
class BadgeEngineFinaleParityTest {

    private val now = Date()
    private val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun day(offset: Long): String {
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, offset.toInt()) }
        return sdf.format(cal.time)
    }

    private fun show(taste: String = "None") =
        MediaItemEntity(id = "tv_1399", title = "Show", type = "TV Show", state = "Watching", taste = taste)

    private fun ep(season: Int, number: Int, watched: Boolean = false, airDate: String? = null) =
        EpisodeEntity(
            uniqueId = MediaIds.buildEpisodeUniqueId("tv_1399", season, number),
            showId = "tv_1399",
            seasonNumber = season,
            episodeNumber = number,
            isWatched = watched,
            airDate = airDate
        )

    @Test
    fun `partially fetched season does not fake a finale`() {
        // Only 3 of 10 episodes are stored. The next unwatched is E3, which is NOT the
        // season's last episode.
        val episodes = listOf(
            ep(1, 1, watched = true, airDate = day(-40)),
            ep(1, 2, watched = true, airDate = day(-40)),
            ep(1, 3, airDate = day(3))
        )

        val badge = BadgeEngine.calculateBadge(
            item = show(),
            episodes = episodes,
            now = now,
            declaredSeasonCounts = mapOf(1 to 10)
        )?.badge

        // Falls through to the release window: SOON (airing in 3 days... >2, so NEW/SOON
        // depending on window) — never FINALE.
        assertNotEquals(SmartBadge.FINALE, badge)
    }

    @Test
    fun `declared count still produces a finale on the real last episode`() {
        val episodes = listOf(
            ep(1, 1, watched = true, airDate = day(-40)),
            ep(1, 2, watched = true, airDate = day(-40)),
            ep(1, 3, airDate = day(5))
        )

        val badge = BadgeEngine.calculateBadge(
            item = show(),
            episodes = episodes,
            now = now,
            declaredSeasonCounts = mapOf(1 to 3)
        )?.badge

        assertEquals(SmartBadge.FINALE, badge)
    }

    @Test
    fun `falls back to local row count when no declared count is known`() {
        // Nothing persisted for the season: the previous behaviour is the only option,
        // and it must still resolve the genuine last episode.
        val episodes = listOf(
            ep(1, 1, watched = true, airDate = day(-40)),
            ep(1, 2, airDate = day(5))
        )

        val badge = BadgeEngine.calculateBadge(item = show(), episodes = episodes, now = now)?.badge

        assertEquals(SmartBadge.FINALE, badge)
    }

    @Test
    fun `a mid season premiere is still a premiere not a finale`() {
        // Season 2 episode 1 of 8, next unwatched. premiereDaysWindow has no lower
        // bound, so this resolves to PREMIERE before FINALE is ever considered.
        val episodes = listOf(
            ep(2, 1, watched = true, airDate = day(-40)),
            ep(2, 1, airDate = day(6))
        ).distinctBy { it.uniqueId }

        val badge = BadgeEngine.calculateBadge(
            item = show(),
            episodes = listOf(ep(1, 1, watched = true, airDate = day(-40)), ep(2, 1, airDate = day(6))),
            now = now,
            declaredSeasonCounts = mapOf(1 to 8, 2 to 8)
        )?.badge

        assertEquals(SmartBadge.PREMIERE, badge)
    }

    @Test
    fun `season counts do not leak across shows`() {
        // Declared counts are keyed by season number and scoped per-show by the caller;
        // the engine only sees one show's map, so nothing to assert beyond isolation.
        val scan = BadgeEngine.scanEpisodes(
            episodes = listOf(ep(1, 3, airDate = day(3))),
            now = now,
            declaredSeasonCounts = mapOf(1 to 8)
        )
        assertEquals(8, scan.nextSeasonEpisodeCount)
    }
}

/**
 * Air-time precision, pinned against a real divergence.
 *
 * "Doctor X: Mafia in White" premiered 2026-10-09 and Android showed NEW while macOS
 * showed PREMIERE. Its air date is a bare `2026-10-09`, which parses to midnight —
 * 5.5 hours before the episode actually airs. That pushed `daysSinceAir` to 3.013,
 * just past the `premiereDaysWindow` upper bound of 3, and the badge fell through to
 * the release window.
 */
class BadgeEngineAirTimeTest {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    private fun at(text: String): Date = dateFormat.parse(text)!!

    @Test
    fun `air time shifts the episode to its real instant`() {
        val dateOnly = BadgeEngine.parseAirInstant("2026-10-09", null)!!
        val withTime = BadgeEngine.parseAirInstant("2026-10-09", "20:30")!!

        assertEquals("2026-10-09 00:00", dateFormat.format(dateOnly))
        assertEquals("2026-10-09 20:30", dateFormat.format(withTime))
    }

    @Test
    fun `bare air date lands before the real instant`() {
        val dateOnly = BadgeEngine.parseAirInstant("2026-10-09", null)!!
        val withTime = BadgeEngine.parseAirInstant("2026-10-09", "20:30")!!

        assertTrue(dateOnly.before(withTime))
    }

    @Test
    fun `doctor x keeps PREMIERE when the air time is known`() {
        // Evaluated at 2026-10-12 00:18 local, exactly when the bad badge was stored.
        val now = at("2026-10-12 00:18")

        val episodes = listOf(
            EpisodeEntity(
                uniqueId = MediaIds.buildEpisodeUniqueId("tv_293610", 1, 1),
                showId = "tv_293610",
                seasonNumber = 1,
                episodeNumber = 1,
                airDate = "2026-10-09",
                airTime = "20:30"
            )
        )
        val show = MediaItemEntity(
            id = "tv_293610", title = "Doctor X", type = "TV Show", state = "Wishlist"
        )

        assertEquals(SmartBadge.PREMIERE, BadgeEngine.calculateBadge(show, episodes, now)?.badge)
    }

    @Test
    fun `doctor x loses PREMIERE without the air time`() {
        // The exact regression: same data, air time omitted.
        val now = at("2026-10-12 00:18")

        // Twelve episodes as in the real library, otherwise E1 would look like the
        // season's last episode and resolve as FINALE instead of falling through.
        val episodes = listOf(
            EpisodeEntity(
                uniqueId = MediaIds.buildEpisodeUniqueId("tv_293610", 1, 1),
                showId = "tv_293610",
                seasonNumber = 1,
                episodeNumber = 1,
                airDate = "2026-10-09"
            )
        ) + (2..12).map {
            EpisodeEntity(
                uniqueId = MediaIds.buildEpisodeUniqueId("tv_293610", 1, it),
                showId = "tv_293610",
                seasonNumber = 1,
                episodeNumber = it,
                airDate = "2026-11-${(it - 2).toString().padStart(2, '0')}"
            )
        }
        val show = MediaItemEntity(
            id = "tv_293610", title = "Doctor X", type = "TV Show", state = "Wishlist"
        )

        assertEquals(SmartBadge.NEW, BadgeEngine.calculateBadge(show, episodes, now)?.badge)
    }

    @Test
    fun `malformed air times fall back to the date`() {
        val expected = BadgeEngine.parseAirInstant("2026-10-09", null)!!
        listOf("", "  ", "8pm", "25:99", "ab:cd", "8:5").forEach { bad ->
            assertEquals("airTime '$bad' must be ignored", expected, BadgeEngine.parseAirInstant("2026-10-09", bad))
        }
    }

    @Test
    fun `null air date yields no instant`() {
        assertNull(BadgeEngine.parseAirInstant(null, "20:30"))
        assertNull(BadgeEngine.parseAirInstant("", "20:30"))
        assertNull(BadgeEngine.parseAirInstant("2026", "20:30"))
    }

    @Test
    fun `midnight air time is accepted`() {
        assertEquals("2026-10-09 00:00", dateFormat.format(BadgeEngine.parseAirInstant("2026-10-09", "00:00")!!))
        assertEquals("2026-10-09 23:59", dateFormat.format(BadgeEngine.parseAirInstant("2026-10-09", "23:59")!!))
    }
}
