package com.vara.mediatracker.data.sync

import com.vara.mediatracker.data.api.TMDBClient
import com.vara.mediatracker.data.api.TVMazeClient
import com.vara.mediatracker.data.db.AppDatabase
import com.vara.mediatracker.data.model.EpisodeEntity
import com.vara.mediatracker.data.model.MediaIds
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.data.model.TVSeasonEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Fetches one show's episodes — air dates, air times and the exact air instant.
 *
 * TVMaze is the source, reached through TMDB for the ids, because it supplies the two things TMDB
 * cannot: the per-episode air instant and the show timezone/service that drive the streaming-time
 * rules. That is also what the Mac stores, so both platforms answer "when does this air" the same
 * way.
 *
 * Extracted from the enrichment worker so the detail screen can ask for the same thing when it
 * opens a show that has no episodes yet. A second source there — TMDB's season endpoint, which was
 * the first attempt — would have been a different answer to the same question, without the instant.
 */
object EpisodeEnrichment {

    /** Fetches every season of one show. @return true when anything was stored. */
    suspend fun enrichShow(
        db: AppDatabase,
        tmdb: TMDBClient,
        tvmaze: TVMazeClient,
        show: MediaItemEntity
    ): Boolean {
        val tmdbId = MediaIds.tmdbIdOf(show.id)
        if (tmdbId <= 0) return false
        val showId = MediaIds.tvShowKey(show.id)

        // Fetched before the TVMaze id is resolved, not after: the resolution needs the original
        // title, and it was already being fetched here anyway — just one step too late to use.
        val details = tmdb.getTVDetails(tmdbId).getOrNull() ?: return false

        val tvMazeId = resolveTvMazeId(tmdb, tvmaze, tmdbId, show.title, details.original_name)
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

        val existing = db.mediaDao().getEpisodesListForShow(showId).associateBy {
            "${it.seasonNumber}_${it.episodeNumber}"
        }

        val newEpisodes = ArrayList<EpisodeEntity>()
        val seasonRows = ArrayList<TVSeasonEntity>()

        // Fetch the seasons concurrently (bounded): sequentially a 10-season show costs
        // ~2.5s of round-trips, which the enrichment budget cannot afford.
        val seasonNumbers = details.seasons.orEmpty().map { it.season_number }.filter { it >= 1 }
        val episodesBySeason = coroutineScope {
            val gate = Semaphore(5)
            seasonNumbers.map { seasonNumber ->
                async {
                    gate.withPermit {
                        tmdb.getSeasonDetails(tmdbId, seasonNumber).getOrNull()?.episodes.orEmpty()
                    }
                }
            }.awaitAll()
        }
        val episodesBySeasonNumber = seasonNumbers.zip(episodesBySeason).toMap()

        // Thai shows take TVMaze's list, as on the Mac. TMDB is regularly two episodes ahead for a
        // Thai series, with entries it has titles for but that have not aired; TVMaze schedules
        // them week by week. TMDB still supplies names, stills and runtimes by episode number.
        val isThai = show.language == "th" || details.original_language == "th"

        for (season in details.seasons.orEmpty()) {
            if (season.season_number < 1) continue
            val tmdbEps = episodesBySeasonNumber[season.season_number].orEmpty()
            val mazeRefs = mazeByKey
                .filterKeys { it.startsWith("${season.season_number}_") }
                .values
                .filter { it.number != null }
            val useMaze = isThai && mazeRefs.isNotEmpty()
            val eps = if (useMaze) {
                mazeRefs.mapNotNull { ref ->
                    val number = ref.number ?: return@mapNotNull null
                    val tmdb = tmdbEps.firstOrNull { it.episode_number == number }
                    com.vara.mediatracker.data.api.TMDBEpisodeItem(
                        season_number = season.season_number,
                        episode_number = number,
                        name = tmdb?.name,
                        overview = tmdb?.overview,
                        air_date = ref.airdate ?: tmdb?.air_date,
                        air_time = ref.airtime ?: tmdb?.air_time,
                        still_path = tmdb?.still_path,
                        runtime = tmdb?.runtime
                    )
                }
            } else {
                tmdbEps
            }

            seasonRows += TVSeasonEntity(
                showId = showId,
                seasonNumber = season.season_number,
                episodeCount = if (useMaze) eps.size
                    else (season.episode_count?.takeIf { it > 0 } ?: eps.size),
                name = season.name,
                airDate = season.air_date
            )

            for (ep in eps) {
                val prior = existing["${ep.season_number}_${ep.episode_number}"]
                val maze = mazeByKey["${ep.season_number}_${ep.episode_number}"]
                newEpisodes += EpisodeEntity(
                    uniqueId = prior?.uniqueId
                        ?: MediaIds.buildEpisodeUniqueId(showId, ep.season_number, ep.episode_number),
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
        // An authoritative list means rows it omits should go — except watched ones.
        if (isThai && mazeByKey.isNotEmpty()) {
            db.mediaDao().pruneUnwatchedEpisodes(showId, newEpisodes.map { it.uniqueId })
        }
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
        tvmaze: TVMazeClient,
        tmdbId: Int,
        title: String,
        originalTitle: String?
    ): Int? {
        // 1. The TVDB id, when TMDB has one. Exact, and the usual path.
        tmdb.getTVExternalIds(tmdbId).getOrNull()?.tvdb_id?.let { id ->
            tvmaze.lookupByTvDbId(id).getOrNull()?.let { return it }
        }

        // 2. The title as this app knows it.
        tvmaze.searchShowByTitle(title).getOrNull()?.let { return it }

        // 3. The title as it airs.
        //
        // A regional show is often indexed by TVMaze under its own name and not the English one, so
        // the search above finds nothing and the show ends up with no id — which means no airstamp,
        // no airtime and no timezone, and an episode that lands at the wrong time. Juliet & Juliet
        // is Thai and drifted from the Mac for exactly this reason: the Mac resolved its id, this
        // device did not.
        val original = originalTitle?.trim()
        if (!original.isNullOrBlank() && !original.equals(title, ignoreCase = true)) {
            tvmaze.searchShowByTitle(original).getOrNull()?.let { return it }
        }
        return null
    }

}
