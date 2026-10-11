package com.vara.mediatracker.data.badge

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vara.mediatracker.data.db.AppDatabase
import com.vara.mediatracker.data.model.EpisodeEntity
import com.vara.mediatracker.data.model.MediaBadgeEntity
import com.vara.mediatracker.data.model.MediaIds
import com.vara.mediatracker.data.model.MediaItemEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Badge persistence and refresh.
 *
 * The behaviour under test is the one that motivated persisting badges at all: a pass
 * must leave no stale rows behind, because a leftover row renders as a live badge that
 * may no longer be true.
 */
@RunWith(AndroidJUnit4::class)
class BadgeComputerTest {

    private lateinit var db: AppDatabase
    private val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val now = Date()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun day(offset: Long): String {
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, offset.toInt()) }
        return sdf.format(cal.time)
    }

    private fun movie(id: String = "movie_550", release: String?, softDeleted: Boolean = false) =
        MediaItemEntity(
            id = id, title = id, type = "Movie",
            releaseDate = release, state = "Watching", isSoftDeleted = softDeleted
        )

    private fun show(id: String = "tv_1399", state: String = "Watching") =
        MediaItemEntity(id = id, title = id, type = "TV Show", state = state)

    private fun ep(
        season: Int = 1,
        number: Int = 1,
        watched: Boolean = false,
        airDate: String? = null,
        showId: String = "tv_1399"
    ) = EpisodeEntity(
        uniqueId = MediaIds.buildEpisodeUniqueId(showId, season, number),
        showId = showId,
        seasonNumber = season,
        episodeNumber = number,
        isWatched = watched,
        airDate = airDate
    )

    @Test
    fun computesAndPersistsBadges() = runTest {
        db.mediaDao().upsertMedia(listOf(movie(release = day(2))))

        val count = BadgeComputer.recompute(db, now)

        assertEquals(1, count)
        val badge = db.badgeDao().getBadge("movie_550")
        assertNotNull(badge)
        assertEquals(SmartBadge.PREMIERE.label, badge!!.badgeLabel)
        assertEquals(day(2), badge.targetDate)
        assertEquals(now.time, badge.computedAt)
    }

    @Test
    fun titlesWithoutABadgeGetNoRow() = runTest {
        db.mediaDao().upsertMedia(listOf(movie(release = day(-90))))

        BadgeComputer.recompute(db, now)

        assertNull(db.badgeDao().getBadge("movie_550"))
        assertEquals(0, db.badgeDao().badgeCount())
    }

    @Test
    fun recomputeClearsBadgesThatStoppedQualifying() = runTest {
        db.mediaDao().upsertMedia(listOf(movie(release = day(2))))
        BadgeComputer.recompute(db, now)
        assertNotNull(db.badgeDao().getBadge("movie_550"))

        // Same title, window long closed — the stale row must not survive the pass.
        db.mediaDao().upsertMedia(listOf(movie(release = day(-90))))
        BadgeComputer.recompute(db, now)

        assertNull(db.badgeDao().getBadge("movie_550"))
        assertEquals(0, db.badgeDao().badgeCount())
    }

    @Test
    fun softDeletedTitlesArePruned() = runTest {
        db.mediaDao().upsertMedia(listOf(movie(release = day(2))))
        BadgeComputer.recompute(db, now)
        assertEquals(1, db.badgeDao().badgeCount())

        db.mediaDao().upsertMedia(listOf(movie(release = day(2), softDeleted = true)))
        BadgeComputer.recompute(db, now)

        assertEquals(0, db.badgeDao().badgeCount())
    }

    @Test
    fun tvBadgeUsesNextUnwatchedEpisodeAirDate() = runTest {
        db.mediaDao().upsertMedia(listOf(show()))
        db.mediaDao().upsertEpisodes(
            listOf(
                ep(1, 1, watched = true, airDate = day(-30)),
                ep(1, 2, watched = true, airDate = day(-30)),
                ep(1, 3, watched = true, airDate = day(-30)),
                ep(1, 4, airDate = day(1)),
                ep(1, 5, airDate = day(20))
            )
        )

        BadgeComputer.recompute(db, now)

        val badge = db.badgeDao().getBadge("tv_1399")
        assertNotNull(badge)
        assertEquals(SmartBadge.SOON.label, badge!!.badgeLabel)
        // Measured against the next unwatched episode, not the show's first air date.
        assertEquals(day(1), badge.targetDate)
    }

    @Test
    fun droppedShowsNeverGetABadge() = runTest {
        db.mediaDao().upsertMedia(listOf(show(state = "Dropped")))

        BadgeComputer.recompute(db, now)

        assertNull(db.badgeDao().getBadge("tv_1399"))
    }

    @Test
    fun caughtUpShowKeepsNoBadge() = runTest {
        // Everything aired is watched and nothing is upcoming: no milestone, no release
        // window, and engagement short circuits on remainingAired == 0.
        db.mediaDao().upsertMedia(listOf(show()))
        db.mediaDao().upsertEpisodes(
            listOf(
                ep(1, 1, watched = true, airDate = day(-60)),
                ep(1, 2, watched = true, airDate = day(-60)),
                ep(1, 3, watched = true, airDate = day(-60))
            )
        )

        BadgeComputer.recompute(db, now)

        assertNull(db.badgeDao().getBadge("tv_1399"))
    }

    @Test
    fun emptyLibraryClearsAllBadges() = runTest {
        // Seed an orphan row, then recompute against an empty library.
        db.badgeDao().upsertBadges(
            listOf(MediaBadgeEntity("movie_gone", SmartBadge.NEW.label, false, null, 0L))
        )
        assertEquals(1, db.badgeDao().badgeCount())

        val count = BadgeComputer.recompute(db, now)

        assertEquals(0, count)
        assertEquals(0, db.badgeDao().badgeCount())
    }

    @Test
    fun observeBadgesEmitsPersistedState() = runTest {
        db.mediaDao().upsertMedia(listOf(movie(release = day(2)), movie("movie_2", day(3))))

        BadgeComputer.recompute(db, now)
        val rows = db.badgeDao().observeBadges().first()

        assertEquals(2, rows.size)
        assertEquals(setOf("movie_550", "movie_2"), rows.map { it.mediaId }.toSet())
    }
}