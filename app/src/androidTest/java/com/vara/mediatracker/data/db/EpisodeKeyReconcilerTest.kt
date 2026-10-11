package com.vara.mediatracker.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vara.mediatracker.data.model.EpisodeEntity
import com.vara.mediatracker.data.model.MediaIds
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers the one-shot repair that collapses duplicate episode rows written by
 * pre-canonical builds.
 */
@RunWith(AndroidJUnit4::class)
class EpisodeKeyReconcilerTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: MediaDao
    private lateinit var reconciler: EpisodeKeyReconciler

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.mediaDao()
        reconciler = EpisodeKeyReconciler(db)
    }

    @After
    fun tearDown() = db.close()

    private fun episode(
        uniqueId: String,
        season: Int,
        number: Int,
        watched: Boolean = false,
        watchedDate: String? = null,
        name: String = "",
        showId: String = "tv_1399"
    ) = EpisodeEntity(
        uniqueId = uniqueId,
        showId = showId,
        seasonNumber = season,
        episodeNumber = number,
        name = name,
        isWatched = watched,
        watchedDate = watchedDate
    )

    private suspend fun ids() = dao.getAllEpisodesList().map { it.uniqueId }.sorted()

    @Test
    fun rekeysLegacyEpisodeIdsToCanonicalForm() = runTest {
        dao.upsertEpisodes(
            listOf(
                episode("1399_1_1", 1, 1),
                episode("1399_2_7", 2, 7)
            )
        )

        val migrated = reconciler.reconcile()

        assertEquals(2, migrated)
        assertEquals(listOf("tv_1399_s1_e1", "tv_1399_s2_e7"), ids())
    }

    @Test
    fun collapsesDuplicatePairsAndKeepsWatchState() = runTest {
        // Same episode, written once by the TMDB enrichment path and once by the
        // Supabase pull path. Only the legacy copy is marked watched.
        dao.upsertEpisodes(
            listOf(
                episode("1399_1_1", 1, 1, watched = false, name = ""),
                episode("tv_1399_s1_e1", 1, 1, watched = true, name = "Pilot", watchedDate = "2026-01-01")
            )
        )

        val migrated = reconciler.reconcile()

        assertEquals(1, migrated)
        assertEquals(listOf("tv_1399_s1_e1"), ids())
        val merged = dao.getEpisodeByUniqueId("tv_1399_s1_e1")!!
        assertTrue("watch state must be additive across the merge", merged.isWatched)
        assertEquals("2026-01-01", merged.watchedDate)
        assertEquals("Pilot", merged.name)
    }

    @Test
    fun canonicalRowsAreLeftUntouched() = runTest {
        dao.upsertEpisodes(
            listOf(
                episode("tv_1399_s1_e1", 1, 1, watched = true, name = "Canonical"),
                episode("tv_1399_s1_e2", 1, 2, name = "Canonical 2")
            )
        )

        assertEquals(0, reconciler.reconcile())
        assertEquals(listOf("tv_1399_s1_e1", "tv_1399_s1_e2"), ids())
        assertEquals("Canonical", dao.getEpisodeByUniqueId("tv_1399_s1_e1")?.name)
    }

    @Test
    fun isIdempotentOnRepeatedRuns() = runTest {
        dao.upsertEpisodes(listOf(episode("1399_1_1", 1, 1), episode("1399_1_2", 1, 2)))

        assertEquals(2, reconciler.reconcile())
        assertEquals(0, reconciler.reconcile())
        assertEquals(0, reconciler.reconcile())
        assertEquals(listOf("tv_1399_s1_e1", "tv_1399_s1_e2"), ids())
    }

    @Test
    fun leavesUnrecognizedIdsAlone() = runTest {
        dao.upsertEpisodes(
            listOf(
                episode("not-an-episode-key", 3, 3),
                episode("1399_1_1", 1, 1)
            )
        )

        assertEquals(1, reconciler.reconcile())
        // The recognizable legacy key is re-keyed; the unparseable one is left intact.
        assertEquals(listOf("not-an-episode-key", "tv_1399_s1_e1"), ids())
    }

    @Test
    fun preservesWatchedDateOnlyWhenWatched() = runTest {
        // Neither copy is watched, so a stale watchedDate must not survive the merge.
        dao.upsertEpisodes(
            listOf(
                episode("1399_1_1", 1, 1, watched = false, name = "Legacy"),
                episode("tv_1399_s1_e1", 1, 1, watched = false, name = "", watchedDate = "2026-05-05")
            )
        )

        reconciler.reconcile()

        val merged = dao.getEpisodeByUniqueId("tv_1399_s1_e1")!!
        assertFalse(merged.isWatched)
        assertNull(merged.watchedDate)
    }

    @Test
    fun backfillsMetadataMissingFromTheCanonicalRow() = runTest {
        dao.upsertEpisodes(
            listOf(
                episode("1399_1_1", 1, 1, watched = true, name = "From Legacy", watchedDate = "2026-03-03"),
                episode("tv_1399_s1_e1", 1, 1, watched = false, name = "")
            )
        )

        reconciler.reconcile()

        val merged = dao.getEpisodeByUniqueId("tv_1399_s1_e1")!!
        assertEquals("From Legacy", merged.name)
        assertTrue(merged.isWatched)
        assertEquals("2026-03-03", merged.watchedDate)
    }

    @Test
    fun normalizesLegacyShowIdOnRekey() = runTest {
        dao.upsertEpisodes(listOf(episode("1399_1_1", 1, 1, showId = "1399")))

        reconciler.reconcile()

        assertEquals("tv_1399", dao.getEpisodeByUniqueId("tv_1399_s1_e1")?.showId)
    }

    @Test
    fun handlesDoubleDigitSeasonsAndEpisodes() = runTest {
        dao.upsertEpisodes(
            listOf(
                episode("1399_10_10", 10, 10),
                episode("tv_1399_s10_e11", 10, 11, name = "Eleven")
            )
        )

        assertEquals(1, reconciler.reconcile())
        assertEquals(listOf("tv_1399_s10_e10", "tv_1399_s10_e11"), ids())
        assertEquals("tv_1399_s10_e10", MediaIds.buildEpisodeUniqueId("tv_1399", 10, 10))
    }

    @Test
    fun doesNotDeleteEpisodesFromOtherShows() = runTest {
        dao.upsertEpisodes(
            listOf(
                episode("1399_1_1", 1, 1),
                EpisodeEntity(
                    uniqueId = "603_1_1", // legacy key belonging to a different show
                    showId = "tv_603",
                    seasonNumber = 1,
                    episodeNumber = 1
                )
            )
        )

        assertEquals(2, reconciler.reconcile())
        assertEquals(listOf("tv_1399_s1_e1", "tv_603_s1_e1"), ids())
    }
}