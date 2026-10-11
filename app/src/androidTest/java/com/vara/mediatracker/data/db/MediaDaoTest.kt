package com.vara.mediatracker.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vara.mediatracker.data.model.MediaIds
import com.vara.mediatracker.data.model.MediaItemEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: MediaDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.mediaDao()
    }

    @After
    fun tearDown() = db.close()

    private fun media(
        id: String,
        state: String = "Watching",
        taste: String = "None",
        softDeleted: Boolean = false,
        lastInteraction: String? = null
    ) = MediaItemEntity(
        id = id,
        title = "Title $id",
        state = state,
        taste = taste,
        isSoftDeleted = softDeleted,
        lastInteractionDate = lastInteraction
    )

    @Test
    fun getAllActiveMedia_excludesSoftDeleted() = runTest {
        dao.upsertMedia(listOf(media("movie_1"), media("movie_2", softDeleted = true)))

        val result = dao.getAllActiveMedia().first()

        assertEquals(listOf("movie_1"), result.map { it.id })
    }

    @Test
    fun getAllActiveMedia_ordersByLastInteractionDescending() = runTest {
        dao.upsertMedia(
            listOf(
                media("movie_old", lastInteraction = "2026-01-01T00:00:00Z"),
                media("movie_new", lastInteraction = "2026-06-01T00:00:00Z"),
                media("movie_mid", lastInteraction = "2026-03-01T00:00:00Z")
            )
        )

        val result = dao.getAllActiveMedia().first()

        assertEquals(listOf("movie_new", "movie_mid", "movie_old"), result.map { it.id })
    }

    @Test
    fun getMediaByState_filtersAndExcludesSoftDeleted() = runTest {
        dao.upsertMedia(
            listOf(
                media("movie_1", state = "Watching"),
                media("movie_2", state = "Completed"),
                media("movie_3", state = "Watching", softDeleted = true)
            )
        )

        assertEquals(listOf("movie_1"), dao.getMediaByState("Watching").first().map { it.id })
    }

    @Test
    fun getMediaById_returnsNullForUnknownId() = runTest {
        assertNull(dao.getMediaById("movie_999"))
    }

    @Test
    fun upsertMedia_replacesExistingRow() = runTest {
        dao.upsertMedia(media("movie_1", taste = "None"))
        dao.upsertMedia(media("movie_1", taste = "Loved"))

        val stored = dao.getMediaById("movie_1")

        assertEquals(1, dao.getAllMediaList().size)
        assertEquals("Loved", stored?.taste)
    }

    @Test
    fun updateState_setsStateAndTimestamp() = runTest {
        dao.upsertMedia(media("movie_1", state = "Wishlist"))

        dao.updateState("movie_1", "Completed", "2026-07-04T10:00:00Z")

        val stored = dao.getMediaById("movie_1")
        assertEquals("Completed", stored?.state)
        assertEquals("2026-07-04T10:00:00Z", stored?.lastStateChangeDate)
    }

    @Test
    fun updateTaste_updatesOnlyTaste() = runTest {
        dao.upsertMedia(media("movie_1", state = "Watching", taste = "None"))

        dao.updateTaste("movie_1", "Liked")

        val stored = dao.getMediaById("movie_1")
        assertEquals("Liked", stored?.taste)
        assertEquals("Watching", stored?.state)
    }

    @Test
    fun upsertEpisodes_storesCanonicalKeys() = runTest {
        dao.upsertEpisodes(
            listOf(
                episode(1, 1, watched = false),
                episode(2, 5, watched = true)
            )
        )

        val stored = dao.getEpisodesListForShow("tv_1399")

        assertEquals(listOf("tv_1399_s1_e1", "tv_1399_s2_e5"), stored.map { it.uniqueId })
    }

    @Test
    fun getEpisodesForShow_ordersBySeasonThenEpisode() = runTest {
        dao.upsertEpisodes(
            listOf(episode(2, 1), episode(1, 10), episode(1, 2), episode(2, 0))
        )

        val ordered = dao.getEpisodesForShow("tv_1399").first().map { it.uniqueId }

        assertEquals(
            listOf("tv_1399_s1_e2", "tv_1399_s1_e10", "tv_1399_s2_e0", "tv_1399_s2_e1"),
            ordered
        )
    }

    @Test
    fun setEpisodeWatched_updatesFlagAndDate() = runTest {
        dao.upsertEpisodes(listOf(episode(1, 1)))

        dao.setEpisodeWatched("tv_1399_s1_e1", true, "2026-02-02T08:00:00Z")

        val stored = dao.getEpisodeByUniqueId("tv_1399_s1_e1")
        assertTrue(stored!!.isWatched)
        assertEquals("2026-02-02T08:00:00Z", stored.watchedDate)
    }

    @Test
    fun setEpisodeWatched_clearsDateWhenUnwatched() = runTest {
        dao.upsertEpisodes(listOf(episode(1, 1, watched = true, watchedDate = "2026-02-02T08:00:00Z")))

        dao.setEpisodeWatched("tv_1399_s1_e1", false, null)

        val stored = dao.getEpisodeByUniqueId("tv_1399_s1_e1")
        assertEquals(false, stored!!.isWatched)
        assertNull(stored.watchedDate)
    }

    private fun episode(
        season: Int,
        number: Int,
        watched: Boolean = false,
        watchedDate: String? = null
    ) = com.vara.mediatracker.data.model.EpisodeEntity(
        uniqueId = MediaIds.buildEpisodeUniqueId("tv_1399", season, number),
        showId = "tv_1399",
        seasonNumber = season,
        episodeNumber = number,
        isWatched = watched,
        watchedDate = watchedDate
    )
}