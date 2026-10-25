package com.vara.mediatracker.data

import com.vara.mediatracker.data.db.WatchHistoryDao
import com.vara.mediatracker.data.model.MediaIds
import com.vara.mediatracker.data.model.WatchCycleEntity
import com.vara.mediatracker.data.model.WatchEventEntity
import java.util.UUID

/**
 * Records watch history on Android, mirroring the core of macOS's `WatchHistoryCoordinator`:
 * one open [WatchCycleEntity] per title at a time (a new one after completion counts as a
 * rewatch) and one immutable [WatchEventEntity] per watch — voided, never deleted, when
 * un-watched.
 *
 * Deliberately minimal next to the Mac's engine: no per-cycle episode scope, no partial-run
 * pausing, no backfill. It exists so this device produces *real* history — the media/episode
 * state still syncs separately and history never drives it.
 */
object WatchHistoryRecorder {

    const val KIND_TV = "tvShow"
    const val KIND_MOVIE = "movie"

    /**
     * Marks one episode — or a movie, when [episodeId] is null — watched.
     * Idempotent: a live event for the same episode is reused rather than duplicated.
     */
    suspend fun recordWatch(
        dao: WatchHistoryDao,
        mediaId: String,
        kind: String,
        episodeId: String?,
        watchedAt: String,
        runtimeMinutes: Int?,
        timezone: String? = null,
        source: String = "manual"
    ): WatchEventEntity {
        val cycle = ensureOpenCycle(dao, mediaId, kind, watchedAt)
        // Canonical, not literal. The same episode arrives spelled two ways — the Mac's
        // `{show}_{season}_{episode}` and this device's `tv_{show}_s{season}_e{episode}` — so a
        // literal lookup sees two episodes where there is one and records the watch twice. Null
        // (a movie) compares equal to null, which is what keeps the movie path working.
        val wanted = MediaIds.canonicalEpisodeIdOrNull(episodeId)
        // Scoped to *this* cycle. Matching any live event for the episode made a rewatch silent:
        // in the second run the still-live event from the first was returned, so nothing new was
        // written and the rewatch never reached the ledger the year review reads.
        dao.getLiveEvents(mediaId).firstOrNull {
            it.cycleId == cycle.id && MediaIds.canonicalEpisodeIdOrNull(it.episodeId) == wanted
        }?.let { return it }

        val event = WatchEventEntity(
            id = UUID.randomUUID().toString(),
            cycleId = cycle.id,
            mediaId = mediaId,
            episodeId = episodeId,
            watchedAt = watchedAt,
            source = source,
            timezoneIdentifier = timezone,
            runtimeMinutes = runtimeMinutes,
            // Canonical, like the lookup above. The Mac writes `{show}_{season}_{episode}` and this
            // device `tv_{show}_s{season}_e{episode}`; keyed on the raw string the two spellings
            // were two different events, so the cloud merge kept both and one viewing appeared
            // twice in the history.
            // Lower-case the cycle id, like the Mac: both devices build this key from a UUID's
            // string form, and one side upper-cased it — so the same viewing got two keys and the
            // cloud merge kept both, which is why history had duplicates differing only in case.
            deduplicationKey = "${cycle.id.lowercase()}:${MediaIds.canonicalEpisodeIdOrNull(episodeId) ?: "movie"}",
            updatedAt = watchedAt
        )
        dao.upsertEvents(listOf(event))
        return event
    }

    /** Un-watches: voids the live event (history is append-only, so sync can propagate it). */
    suspend fun voidWatch(dao: WatchHistoryDao, mediaId: String, episodeId: String?, at: String) {
        // Canonical here too, or un-watching would silently do nothing to an event the Mac wrote.
        val wanted = MediaIds.canonicalEpisodeIdOrNull(episodeId)
        // The newest live event, not the first: with a rewatch there can be one per cycle, and
        // un-marking means undoing the most recent viewing rather than an old run's.
        val existing = dao.getLiveEvents(mediaId)
            .filter { MediaIds.canonicalEpisodeIdOrNull(it.episodeId) == wanted }
            .maxByOrNull { it.watchedAt }
            ?: return
        dao.upsertEvents(listOf(existing.copy(voidedAt = at, updatedAt = at)))
    }

    /**
     * Closes the open cycle — the whole title is done (movie finished, show's last episode
     * watched). A later watch then opens a fresh, `isRewatch` cycle.
     */
    suspend fun completeCycle(dao: WatchHistoryDao, mediaId: String, at: String) {
        val cycle = dao.getOpenCycle(mediaId) ?: return
        dao.upsertCycles(
            listOf(cycle.copy(state = "completed", isComplete = true, completedAt = at, updatedAt = at))
        )
    }

    /**
     * Re-opens the newest cycle after a completion was undone (an episode un-watched on a
     * finished show). Without this the next watch would look like a fresh rewatch.
     */
    suspend fun reopenCycle(dao: WatchHistoryDao, mediaId: String, at: String) {
        val latest = dao.getCyclesFor(mediaId).firstOrNull() ?: return
        if (latest.state == "completed") {
            dao.upsertCycles(
                listOf(latest.copy(state = "active", isComplete = false, completedAt = null, updatedAt = at))
            )
        }
    }

    private suspend fun ensureOpenCycle(
        dao: WatchHistoryDao,
        mediaId: String,
        kind: String,
        at: String
    ): WatchCycleEntity {
        dao.getOpenCycle(mediaId)?.let { return it }
        val cycle = WatchCycleEntity(
            id = UUID.randomUUID().toString(),
            mediaId = mediaId,
            kind = kind,
            startedAt = at,
            // A run on a title that has been through cycles before is a rewatch.
            isRewatch = dao.getCyclesFor(mediaId).isNotEmpty(),
            updatedAt = at
        )
        dao.upsertCycles(listOf(cycle))
        return cycle
    }
}
