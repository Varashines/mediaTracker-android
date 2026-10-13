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

        // TVMaze supplies the two things TMDB cannot: the per-episode air instant and
        // the show timezone/service that drive the streaming-time rules.
        val tvMazeId = resolveTvMazeId(tmdb, tvmaze, tmdbId, show.title)
        var showInfo: com.vara.mediatracker.data.api.TVMazeShowInfo? = null
        val mazeByKey = HashMap<String, com.vara.mediatracker.data.api.TVMazeEpisodeRef>()
        if (tvMazeId != null && tvMazeId > 0) {
            showInfo = tvmaze.showInfo(tvMazeId).getOrNull()
            tvmaze.episodes(tvMazeId).getOrNull()?.forEach { ref ->
                val season = ref.season
                val number = ref.number
                if (season != null && number != null) mazeByKey["${season}_${number}"] = ref
            }
        }

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
                val maze = mazeByKey["${ep.season_number}_${ep.episode_number}"]
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
                    // TVMaze's airdate is the local broadcast day; prefer it over TMDB's.
                    airDate = maze?.airdate ?: ep.air_date ?: prior?.airDate,
                    airTime = maze?.airtime ?: ep.air_time ?: prior?.airTime,
                    airstamp = maze?.airstamp ?: prior?.airstamp,
                    runtime = ep.runtime ?: prior?.runtime,
                    updatedAt = prior?.updatedAt
                )
            }
        }

        if (showInfo != null) {
            db.mediaDao().upsertMedia(
                show.copy(
                    // The ISO string is stored verbatim; BadgeEngine parses it back.
                    nextAiringInstant = showInfo.nextAirstamp?.takeIf { it.isNotBlank() }
                        ?: show.nextAiringInstant,
                    timezone = showInfo.timezone ?: show.timezone,
                    network = showInfo.network ?: show.network
                )
            )
        }

        if (newEpisodes.isEmpty()) return showInfo != null
        db.mediaDao().upsertEpisodes(newEpisodes)
        if (seasonRows.isNotEmpty()) db.seasonDao().upsertSeasons(seasonRows)
        return true
    }

    /**
     * TMDB -> TVDB -> TVMaze, falling back to a title search when the show has no
     * TVDB id. Any failure is tolerated: the caller still gets episode dates, just
     * without a precise instant.
     */
    private suspend fun resolveTvMazeId(
        tmdb: TMDBClient,
        tvmaze: com.vara.mediatracker.data.api.TVMazeClient,
        tmdbId: Int,
        title: String
    ): Int? =
        tmdb.getTVExternalIds(tmdbId).getOrNull()?.tvdb_id
            ?.let { tvmaze.lookupByTvDbId(it).getOrNull() }
            ?: tvmaze.searchShowByTitle(title).getOrNull()

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