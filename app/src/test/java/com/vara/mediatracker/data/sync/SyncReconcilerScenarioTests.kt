package com.vara.mediatracker.data.sync

import com.vara.mediatracker.data.model.EpisodeEntity
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.data.model.WatchEventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The sync decisions as scenarios — the counterpart of macOS's `CloudSyncScenarioTests`.
 *
 * These drive [SyncReconciler] with plain entities, so the decision layer is covered on the JVM:
 * no Room, no Ktor, no emulator. The scenarios mirror the Mac's one-for-one, because the two apps
 * have to agree about who wins a conflict and what counts as one viewing.
 *
 * A watermarked fetch is modelled by passing only the rows the server would return: with a
 * watermark, "absent" means *unchanged*, which is what the reconciler's push gate depends on.
 */
class SyncReconcilerScenarioTests {

    private fun stamp(offsetSeconds: Long): String = Instant.now().plusSeconds(offsetSeconds).toString()

    private fun item(
        id: String,
        state: String = "Active",
        taste: String = "None",
        lastInteraction: String? = stamp(-7200),
        dateAdded: String? = null
    ) = MediaItemEntity(
        id = id,
        title = "T",
        state = state,
        taste = taste,
        lastInteractionDate = lastInteraction,
        lastStateChangeDate = lastInteraction,
        dateAdded = dateAdded
    )

    private fun cloudMedia(
        id: String,
        state: String = "Active",
        taste: String = "None",
        lastInteraction: String? = stamp(-60),
        updatedAt: String? = stamp(-60),
        softDeleted: Boolean = false,
        dateAdded: String? = null
    ) = RemoteMedia(
        user_id = "mt_sync_test",
        media_id = id,
        type = "Movie",
        state = state,
        taste = taste,
        is_soft_deleted = softDeleted,
        last_interaction_date = lastInteraction,
        last_state_change_date = lastInteraction,
        updated_at = updatedAt,
        date_added = dateAdded
    )

    private fun event(id: String, mediaId: String, episodeId: String?, watchedAt: String,
                      voidedAt: String? = null, updatedAt: String? = stamp(-60)) =
        WatchEventEntity(
            id = id,
            cycleId = "cycle-1",
            mediaId = mediaId,
            episodeId = episodeId,
            watchedAt = watchedAt,
            voidedAt = voidedAt,
            deduplicationKey = "k",
            updatedAt = updatedAt
        )

    private fun cloudEvent(id: String, mediaId: String, episodeId: String?, watchedAt: String,
                           voidedAt: String? = null, updatedAt: String? = stamp(-60)) =
        RemoteWatchEvent(
            user_id = "mt_sync_test",
            event_id = id,
            cycle_id = "cycle-1",
            media_id = mediaId,
            episode_id = episodeId,
            watched_at = watchedAt,
            source = "manual",
            timezone_identifier = null,
            runtime_minutes = null,
            voided_at = voidedAt,
            deduplication_key = "k",
            is_backfilled = false,
            updated_at = updatedAt
        )

    // MARK: - Watermark

    @Test
    fun `an unchanged library has nothing to do`() {
        // The watermarked fetch returned nothing: every row predates the watermark.
        val result = SyncReconciler.reconcileMedia(
            local = listOf(item("movie_1", lastInteraction = stamp(-7200))),
            remote = emptyList(),
            since = stamp(-3600)
        )

        assertTrue(result.outgoing.isEmpty())
        assertTrue(result.incoming.isEmpty())
        assertTrue(result.conflicts.isEmpty())
    }

    @Test
    fun `a row edited after the watermark is pushed even when absent from the response`() {
        val result = SyncReconciler.reconcileMedia(
            local = listOf(item("movie_1", lastInteraction = stamp(-60))),
            remote = emptyList(),
            since = stamp(-3600)
        )

        assertEquals(listOf("movie_1"), result.outgoing.map { it.id })
    }

    // MARK: - Media conflicts

    @Test
    fun `a newer cloud edit is applied`() {
        val result = SyncReconciler.reconcileMedia(
            local = listOf(item("movie_1", state = "Active", lastInteraction = stamp(-7200))),
            remote = listOf(cloudMedia("movie_1", state = "Completed",
                                       lastInteraction = stamp(-60), updatedAt = stamp(-60))),
            since = stamp(-7200)
        )

        assertEquals(listOf("movie_1"), result.incoming.map { it.id })
        assertEquals("Completed", result.incoming.single().state)
        assertTrue(result.outgoing.isEmpty())
    }

    @Test
    fun `a newer local edit is pushed`() {
        val result = SyncReconciler.reconcileMedia(
            local = listOf(item("movie_1", state = "Completed", lastInteraction = stamp(-30))),
            remote = listOf(cloudMedia("movie_1", state = "Active",
                                       lastInteraction = stamp(-3600), updatedAt = stamp(-3600))),
            since = stamp(-7200)
        )

        assertEquals(listOf("movie_1"), result.outgoing.map { it.id })
        assertTrue(result.incoming.isEmpty())
    }

    /**
     * Android used to write "Loved" where the Mac writes "Love". That is the same rating, not a
     * conflict — it used to be reported as one, and the row flipped back and forth.
     */
    @Test
    fun `a legacy taste spelling is not a conflict`() {
        val result = SyncReconciler.reconcileMedia(
            local = listOf(item("movie_1", taste = "Loved", lastInteraction = stamp(-7200))),
            remote = listOf(cloudMedia("movie_1", taste = "Love",
                                       lastInteraction = stamp(-60), updatedAt = stamp(-60))),
            since = stamp(-7200)
        )

        assertTrue("canonically equal taste is not a difference", result.conflicts.isEmpty())
        assertTrue("nothing is pushed", result.outgoing.isEmpty())
        // The cloud's stamp is the newer one, so it is adopted — timestamps are facts and travel
        // independently of state. Adopting it is what stops the row being seen as edited again.
        assertEquals(listOf("movie_1"), result.incoming.map { it.id })
    }

    /**
     * The bug the macOS scenario harness found: a cloud row stamped hours ahead used to outrank a
     * genuinely later edit here, so this device's edit could never win.
     */
    @Test
    fun `a future stamped cloud row does not outrank a later local edit`() {
        val result = SyncReconciler.reconcileMedia(
            local = listOf(item("movie_1", state = "Completed", lastInteraction = stamp(0))),
            remote = listOf(cloudMedia("movie_1", state = "Active",
                                       lastInteraction = stamp(4 * 3600),   // bad clock, ahead
                                       updatedAt = stamp(-60))),             // …written a minute ago
            since = stamp(-7200)
        )

        assertEquals("the real later edit wins", listOf("movie_1"), result.outgoing.map { it.id })
        assertTrue(result.incoming.isEmpty())
    }

    @Test
    fun `a soft delete in the cloud is applied`() {
        val result = SyncReconciler.reconcileMedia(
            local = listOf(item("movie_1", lastInteraction = stamp(-7200))),
            remote = listOf(cloudMedia("movie_1", lastInteraction = stamp(-60),
                                       updatedAt = stamp(-60), softDeleted = true)),
            since = stamp(-7200)
        )

        assertTrue("a delete made elsewhere has to arrive here", result.incoming.single().isSoftDeleted)
    }

    // MARK: - Episodes

    @Test
    fun `a corrected episode date is applied`() {
        val old = stamp(-86_400)
        val corrected = stamp(-82_800)
        val result = SyncReconciler.reconcileEpisodes(
            local = listOf(EpisodeEntity(uniqueId = "tv_293610_s1_e3", showId = "tv_293610",
                                         seasonNumber = 1, episodeNumber = 3,
                                         isWatched = true, watchedDate = old)),
            remote = listOf(RemoteEpisode(user_id = "mt_sync_test", episode_id = "293610_1_3",
                                          show_id = "tv_293610", season_number = 1, episode_number = 3,
                                          is_watched = true, watched_date = corrected,
                                          updated_at = stamp(-30))),
            since = stamp(-7200)
        )

        assertEquals(1, result.incoming.size)
        assertEquals(corrected, result.incoming.single().watchedDate)
    }

    @Test
    fun `remote episodes create local rows on a clean install`() {
        val watchedTime = stamp(-86_400)
        val result = SyncReconciler.reconcileEpisodes(
            local = emptyList(),
            remote = listOf(
                RemoteEpisode(
                    user_id = "mt_sync_test",
                    episode_id = "293610_1_3",
                    show_id = "tv_293610",
                    season_number = 1,
                    episode_number = 3,
                    is_watched = true,
                    watched_date = watchedTime,
                    updated_at = stamp(-30)
                )
            ),
            since = null
        )

        assertEquals(1, result.incoming.size)
        val incoming = result.incoming.single()
        assertEquals("tv_293610_s1_e3", incoming.uniqueId)
        assertEquals("tv_293610", incoming.showId)
        assertEquals(1, incoming.seasonNumber)
        assertEquals(3, incoming.episodeNumber)
        assertTrue(incoming.isWatched)
        assertEquals(watchedTime, incoming.watchedDate)
    }

    // MARK: - Ledger

    /**
     * The duplicate that put one episode on two days: the same watch, recorded here under the
     * canonical id and pulled back under the legacy one, stamped 5½ hours apart.
     */
    @Test
    fun `a duplicate watch is not logged twice`() {
        val result = SyncReconciler.reconcileEvents(
            local = listOf(event("local-1", "tv_293610", "tv_293610_s1_e3", watchedAt = stamp(-7200))),
            remote = listOf(cloudEvent("remote-1", "tv_293610", "293610_1_3",
                                       watchedAt = stamp(-7200 + 19_800))),
            since = stamp(-7200)
        )

        assertTrue("one viewing, one event", result.incoming.isEmpty())
    }

    @Test
    fun `a voided cloud event is applied locally`() {
        val result = SyncReconciler.reconcileEvents(
            local = listOf(event("same-id", "tv_293610", "293610_1_3", watchedAt = stamp(-7200))),
            remote = listOf(cloudEvent("same-id", "tv_293610", "293610_1_3",
                                       watchedAt = stamp(-7200), voidedAt = stamp(-30))),
            since = stamp(-7200)
        )

        assertEquals(1, result.incoming.size)
        assertTrue("the void has to arrive", result.incoming.single().voidedAt != null)
    }

    @Test
    fun `a local void is pushed to the cloud`() {
        val result = SyncReconciler.reconcileEvents(
            local = listOf(event("same-id", "tv_293610", "293610_1_3",
                                 watchedAt = stamp(-7200), voidedAt = stamp(-30))),
            remote = listOf(cloudEvent("same-id", "tv_293610", "293610_1_3", watchedAt = stamp(-7200))),
            since = stamp(-7200)
        )

        assertEquals(listOf("same-id"), result.outgoing.map { it.id })
        assertTrue(result.incoming.isEmpty())
    }

    /** A genuine rewatch a day later is a separate viewing and must survive. */
    @Test
    fun `a rewatch the next day is a separate event`() {
        val result = SyncReconciler.reconcileEvents(
            local = listOf(event("local-1", "movie_550", null, watchedAt = stamp(-86_400))),
            remote = listOf(cloudEvent("remote-1", "movie_550", null, watchedAt = stamp(-60))),
            since = stamp(-172_800)
        )

        assertFalse("a later viewing is not a duplicate", result.incoming.isEmpty())
    }

    /**
     * The Mac marks an episode on an Active show: state and taste are identical, only the timestamps
     * move. The reconciler used to ignore that, so this device never learned it had been watched.
     */
    @Test
    fun `a later interaction date alone is applied`() {
        val result = SyncReconciler.reconcileMedia(
            local = listOf(item("tv_1", state = "Active", lastInteraction = stamp(-3600))),
            remote = listOf(cloudMedia("tv_1", state = "Active", lastInteraction = stamp(-60), updatedAt = stamp(-60))),
            since = stamp(-7200)
        )

        assertEquals(listOf("tv_1"), result.incoming.map { it.id })
        assertTrue("timestamps are facts, not a conflict", result.outgoing.isEmpty())
    }

    /** The mirror: this device's edit is the later one, so it travels up instead. */
    @Test
    fun `an earlier remote interaction date is pushed up`() {
        val result = SyncReconciler.reconcileMedia(
            local = listOf(item("tv_1", state = "Active", lastInteraction = stamp(-30))),
            remote = listOf(cloudMedia("tv_1", state = "Active", lastInteraction = stamp(-3600), updatedAt = stamp(-3600))),
            since = stamp(-7200)
        )

        assertEquals(listOf("tv_1"), result.outgoing.map { it.id })
        assertTrue(result.incoming.isEmpty())
    }

    /** Equal stamps mean nothing to do — the state that caused the one-title-per-sync churn. */
    @Test
    fun `equal stamps move nothing`() {
        val same = stamp(-60)
        val result = SyncReconciler.reconcileMedia(
            local = listOf(item("tv_1", state = "Active", lastInteraction = same)),
            remote = listOf(cloudMedia("tv_1", state = "Active", lastInteraction = same, updatedAt = same)),
            since = stamp(-7200)
        )

        assertTrue(result.incoming.isEmpty())
        assertTrue(result.outgoing.isEmpty())
    }
}
