package com.vara.mediatracker.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.vara.mediatracker.data.badge.BadgeComputer
import com.vara.mediatracker.di.AppContainer
import java.time.Instant

/**
 * Finishes what a delete started.
 *
 * Removing a title only flags it: the row stays, hidden from every read, so the delete can be undone
 * for a while. Nothing here ever removed it, though, so the rows accumulated — a title deleted a year
 * ago was still in the database, along with its episodes and its watch history. The Mac has done this
 * for a while (`purgeSoftDeleted`); this is the same job on this side.
 *
 * Past the window the row is gone for good: the media row, its episodes, its watch cycles and events,
 * and the badges are rebuilt without it.
 *
 * The cloud is deliberately left alone. Its tombstone has to stay — a device that never learned about
 * the delete would otherwise re-add the title on its next pull.
 */
class PurgeWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = AppContainer(applicationContext)
        val db = container.database

        return try {
            val cutoff = Instant.now().minusSeconds(RETENTION_SECONDS)
            val stale = db.mediaDao().getSoftDeleted().filter { row ->
                parseInstant(row.lastInteractionDate)?.isBefore(cutoff) == true
            }
            if (stale.isEmpty()) return Result.success(workDataOf(KEY_PURGED to 0))

            val ids = stale.map { it.id }
            db.mediaDao().deleteEpisodesByShowIds(ids)
            db.watchHistoryDao().deleteCyclesFor(ids)
            db.watchHistoryDao().deleteEventsFor(ids)
            db.mediaDao().deleteByIds(ids)
            // Rebuilt rather than patched: the badge table is a cache, and one pass over it is
            // cheaper than working out which rows belonged to the titles that just left.
            BadgeComputer.recompute(db)

            android.util.Log.i("Purge", "Removed ${ids.size} soft-deleted titles past the window")
            Result.success(workDataOf(KEY_PURGED to ids.size))
        } catch (e: Exception) {
            Result.retry()
        }
    }

    /** ISO-8601, which is what every writer here stores. Anything else is skipped, not guessed at. */
    private fun parseInstant(raw: String?): Instant? =
        runCatching { Instant.parse(raw) }.getOrNull()

    companion object {
        const val KEY_PURGED = "purged"

        /** A day, matching the Mac's undo window. */
        const val RETENTION_SECONDS = 24L * 60 * 60
    }
}
