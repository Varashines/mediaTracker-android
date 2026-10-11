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
        if (prefs.tmdbApiKey.isBlank() || prefs.supabaseUrl.isBlank()) {
            return Result.success(workDataOf(KEY_SHOWS to 0))
        }

        val db = container.database
        val todo = db.mediaDao().getShowsNeedingEpisodeEnrichment(BATCH_SIZE)
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
                            if (enrichShow(db, tmdb, tvmaze, show)) processed++
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

    /** Fetches every season of one show. @return true when anything was stored. */
    private suspend fun enrichShow(
        db: AppDatabase,
        tmdb: TMDBClient,
        tvmaze: com.vara.mediatracker.data.api.TVMazeClient,
        show: MediaItemEntity
    ): Boolean {
        val tmdbId = MediaIds.tmdbIdOf(show.id)
        if (tmdbId <= 0) return false
        val showId = MediaIds.tvShowKey(show.id)

        // Resolve the precise airing instant before anything else: this is what the
        // badge windows actually need, and it is the one thing TMDB cannot supply.
        val instant = resolveNextAiringInstant(tmdb, tvmaze, tmdbId, show.title)

        val details = tmdb.getTVDetails(tmdbId).getOrNull() ?: return false
        val existing = db.mediaDao().getEpisodesListForShow(showId).associateBy {
            "${it.seasonNumber}_${it.episodeNumber}"
        }

        val newEpisodes = ArrayList<EpisodeEntity>()
        val seasonRows = ArrayList<TVSeasonEntity>()

        for (season in details.seasons.orEmpty()) {
            if (season.season_number < 1) continue
            val eps = tmdb.getSeasonDetails(tmdbId, season.season_number).getOrNull()?.episodes.orEmpty()

            seasonRows += TVSeasonEntity(
                showId = showId,
                seasonNumber = season.season_number,
                episodeCount = season.episode_count?.takeIf { it > 0 } ?: eps.size,
                name = season.name,
                airDate = season.air_date
            )

            for (ep in eps) {
                val prior = existing["${ep.season_number}_${ep.episode_number}"]
                newEpisodes += EpisodeEntity(
                    uniqueId = prior?.uniqueId ?: MediaIds.buildEpisodeUniqueId(showId, ep.season_number, ep.episode_number),
                    showId = showId,
                    seasonNumber = ep.season_number,
                    episodeNumber = ep.episode_number,
                    name = ep.name ?: prior?.name ?: "",
                    overview = ep.overview ?: prior?.overview ?: "",
                    stillPath = ep.still_path ?: prior?.stillPath,
                    // Watch state is the user's, never overwritten by a refresh.
                    isWatched = prior?.isWatched ?: false,
                    watchedDate = prior?.watchedDate,
                    airDate = ep.air_date ?: prior?.airDate,
                    airTime = ep.air_time ?: prior?.airTime,
                    runtime = ep.runtime ?: prior?.runtime,
                    updatedAt = prior?.updatedAt
                )
            }
        }

        if (instant != null) {
            db.mediaDao().upsertMedia(
                show.copy(nextAiringInstant = instant.toString())
            )
        }

        if (newEpisodes.isEmpty()) return instant != null
        db.mediaDao().upsertEpisodes(newEpisodes)
        if (seasonRows.isNotEmpty()) db.seasonDao().upsertSeasons(seasonRows)
        return true
    }

    /**
     * TMDB -> TVDB -> TVMaze, falling back to a title search when the show has no
     * TVDB id. Any failure is tolerated: the caller still gets episode dates, just
     * without a precise instant.
     */
    private suspend fun resolveNextAiringInstant(
        tmdb: TMDBClient,
        tvmaze: com.vara.mediatracker.data.api.TVMazeClient,
        tmdbId: Int,
        title: String
    ): java.util.Date? {
        val tvMazeId = tmdb.getTVExternalIds(tmdbId).getOrNull()?.tvdb_id
            ?.let { tvmaze.lookupByTvDbId(it).getOrNull() }
            ?: tvmaze.searchShowByTitle(title).getOrNull()
            ?: return null
        return tvmaze.nextEpisodeInstant(tvMazeId).getOrNull()
    }

    companion object {
        const val KEY_SHOWS = "shows"

        /**
         * Shows per pass. Was 8 on a 3-hour cadence, which put the 128 shows with no
         * air data roughly two days out — far too slow for Coming Soon to ever be
         * populated. Raised alongside the cadence below.
         */
        const val BATCH_SIZE = 20

        /** Concurrent shows. TMDB is rate limited; this stays well inside it. */
        const val MAX_CONCURRENT_SHOWS = 4
    }
}