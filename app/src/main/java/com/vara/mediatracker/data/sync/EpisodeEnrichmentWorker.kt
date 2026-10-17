package com.vara.mediatracker.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.vara.mediatracker.data.api.TMDBClient
import com.vara.mediatracker.data.db.AppDatabase
import com.vara.mediatracker.data.model.EpisodeEntity
import com.vara.mediatracker.data.model.MediaIds
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.data.model.TVSeasonEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Backfills episode air dates and air times for TV shows that have none.
 *
 * Air dates previously only arrived as a side effect of opening a DetailScreen, so
 * ~98% of the episode table had none. Badge rules that depend on air times could not
 * resolve at all for those shows, and a bare `airDate` was enough to push `daysSinceAir`
 * across the 3-day premiere boundary.
 *
 * Runs on a deliberately slow cadence: this is a one-time backfill over the library,
 * not something that needs to be fresh. Each pass handles a bounded batch so a sync
 * never becomes a long-running TMDB crawl, and the DAO query naturally stops selecting
 * shows once they are complete.
 */
class EpisodeEnrichmentWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = com.vara.mediatracker.di.AppContainer(applicationContext)
        val prefs = container.preferences
        // TVMaze needs no key, but resolving a show's TVMaze id goes through TMDB, so a
        // TMDB key is required. Supabase is deliberately NOT required: air times are
        // local enrichment and must work for users who never set up cloud sync.
        if (prefs.tmdbApiKey.isBlank()) {
            return Result.success(workDataOf(KEY_SHOWS to 0))
        }

        val db = container.database
        val todo = db.mediaDao().getShowsNeedingEpisodeEnrichment(BATCH_SIZE)
        android.util.Log.i("EpisodeEnrich", "doWork: ${todo.size} shows to enrich")
        if (todo.isEmpty()) return Result.success(workDataOf(KEY_SHOWS to 0))

        val tmdb = container.tmdbClient()
        val tvmaze = container.tvmaze
        var processed = 0

        try {
            val gate = Semaphore(MAX_CONCURRENT_SHOWS)
            coroutineScope {
                todo.map { show ->
                    async(Dispatchers.IO) {
                        gate.withPermit {
                            if (EpisodeEnrichment.enrichShow(db, tmdb, tvmaze, show)) processed++
                        }
                    }
                }.awaitAll()
            }
        } catch (e: Exception) {
            return Result.retry()
        }

        // Badges depend on the air dates just written, so recompute in the same pass
        // rather than leaving the rail stale until the next badge tick.
        com.vara.mediatracker.data.badge.BadgeComputer.recompute(db)

        return Result.success(workDataOf(KEY_SHOWS to processed))
    }

    companion object {
        const val KEY_SHOWS = "shows"

        /**
         * Shows per pass. A full library of a few hundred shows converges in a handful
         * of on-demand passes; the periodic schedule is the safety net.
         */
        const val BATCH_SIZE = 40

        /** Concurrent shows. TMDB is rate limited; this stays well inside it. */
        const val MAX_CONCURRENT_SHOWS = 4
    }
}