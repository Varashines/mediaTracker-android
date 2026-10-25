package com.vara.mediatracker.data

import com.vara.mediatracker.data.model.EpisodeEntity
import com.vara.mediatracker.data.model.MediaIds
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.data.model.TVSeasonEntity
import com.vara.mediatracker.di.AppContainer
import com.vara.mediatracker.ui.common.AppToast
import com.vara.mediatracker.util.WireTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Locale

/**
 * Centralized tracking logic for episode completion, season completion, and automatic state progression.
 *
 * Rules:
 * 1. Marking an episode watched:
 *    - Updates episode isWatched and watchedDate.
 *    - Updates the show's lastInteractionDate to the current timestamp.
 *    - Syncs the watch event to Supabase.
 *    - Automatic state progression:
 *      - If the show is currently in "Wishlist", auto-moves it to "Active" ("In Progress")
 *        because the user has started watching.
 *      - If all episodes for this show are now watched, auto-moves it to "Completed"
 *        and triggers a celebratory toast.
 *      - If marking unwatched and the show was "Completed", auto-moves it back to "Active".
 *
 * 2. Marking a season watched/unwatched:
 *    - Updates all episodes in that season in a single atomic database query.
 *    - Pushes watch updates to sync.
 *    - Updates show lastInteractionDate.
 *    - Automatic state progression (Wishlist -> Active, or Completed if entire show is watched).
 */
object TrackingLogic {

    fun isoNow(): String = WireTime.now()

    /** Local calendar date, the shape `airDate` is stored in (`yyyy-MM-dd`). */
    private fun localToday(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /**
     * Whether an episode is available to watch: its air date has arrived, or we do not know it.
     *
     * Unknown counts as aired on purpose. A show whose episode metadata has not been enriched yet
     * would otherwise ignore "mark season watched" completely, which is a worse failure than
     * treating an unknown episode as available — and it is what the Mac does
     * (`TVEpisode.isAvailable`).
     */
    private fun EpisodeEntity.hasAired(today: String): Boolean =
        airDate?.take(10)?.let { it <= today } ?: true

    /**
     * Whether a show is finished.
     *
     * Two conditions, both of them lessons from the Mac: every episode that has aired must be
     * watched, and the local table must hold at least as many episodes as the season rows declare.
     * Without the second, a show enriched for only one season completed on its last known episode
     * and pushed `Completed` to the cloud.
     */
    private fun isShowComplete(
        episodes: List<EpisodeEntity>,
        declared: List<TVSeasonEntity>,
        today: String
    ): Boolean {
        val aired = episodes.filter { it.seasonNumber > 0 && it.hasAired(today) }
        if (aired.isEmpty() || aired.any { !it.isWatched }) return false
        return declared.all { season ->
            episodes.count { it.seasonNumber == season.seasonNumber } >= season.episodeCount
        }
    }

    /**
     * Mark a single episode watched or unwatched with intelligent show state progression.
     */
    suspend fun markEpisodeWatched(
        container: AppContainer,
        episode: EpisodeEntity,
        watched: Boolean,
        onShowUpdated: ((MediaItemEntity) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        val now = isoNow()
        val dao = container.mediaDao
        val syncManager = container.syncManager()

        // 1. Update episode
        dao.setEpisodeWatched(episode.uniqueId, watched, if (watched) now else null)
        dao.updateLastInteractionDate(episode.showId, now)

        // 2. Log it in watch history — append-only, and the sync ships it to the Mac. Without
        //    this, history only ever existed on macOS.
        val historyDao = container.database.watchHistoryDao()
        if (watched) {
            WatchHistoryRecorder.recordWatch(
                dao = historyDao,
                mediaId = episode.showId,
                kind = WatchHistoryRecorder.KIND_TV,
                episodeId = episode.uniqueId,
                watchedAt = now,
                runtimeMinutes = episode.runtime
            )
        } else {
            WatchHistoryRecorder.voidWatch(historyDao, episode.showId, episode.uniqueId, now)
        }

        // 3. Sync episode watch to Supabase
        val showTmdbId = MediaIds.tmdbIdOf(episode.showId).toString()
        syncManager.pushEpisodeWatch(showTmdbId, episode.seasonNumber, episode.episodeNumber, watched)

        // 4. Auto-progress show state
        val show = dao.getMediaById(episode.showId)
        if (show != null) {
            val allEpisodes = dao.getEpisodesListForShow(episode.showId)
            val allWatched = isShowComplete(
                allEpisodes,
                container.database.seasonDao().getDeclaredCountsForShow(episode.showId),
                localToday()
            )

            if (watched) {
                if (show.state.equals("Wishlist", ignoreCase = true)) {
                    val updatedShow = show.copy(
                        state = "Active",
                        lastStateChangeDate = now,
                        lastInteractionDate = now,
                        updatedAt = now
                    )
                    dao.updateState(show.id, "Active", now)
                    syncManager.pushMediaUpdate(show.id, show.type, "Active", show.taste)
                    onShowUpdated?.invoke(updatedShow)
                    AppToast.show("Moved \"${show.title}\" to In Progress")
                } else if (allWatched && !show.state.equals("Completed", ignoreCase = true)) {
                    val updatedShow = show.copy(
                        state = "Completed",
                        lastStateChangeDate = now,
                        lastInteractionDate = now,
                        updatedAt = now
                    )
                    dao.updateState(show.id, "Completed", now)
                    syncManager.pushMediaUpdate(show.id, show.type, "Completed", show.taste)
                    WatchHistoryRecorder.completeCycle(historyDao, show.id, now)
                    onShowUpdated?.invoke(updatedShow)
                    AppToast.show("Show Completed! 🎉")
                } else {
                    AppToast.show("Marked S${episode.seasonNumber} E${episode.episodeNumber} watched")
                }
            } else {
                if (show.state.equals("Completed", ignoreCase = true)) {
                    val updatedShow = show.copy(
                        state = "Active",
                        lastStateChangeDate = now,
                        lastInteractionDate = now,
                        updatedAt = now
                    )
                    dao.updateState(show.id, "Active", now)
                    syncManager.pushMediaUpdate(show.id, show.type, "Active", show.taste)
                    // Undoing the completion re-opens the run rather than starting a rewatch.
                    WatchHistoryRecorder.reopenCycle(historyDao, show.id, now)
                    onShowUpdated?.invoke(updatedShow)
                    AppToast.show("Moved \"${show.title}\" back to In Progress")
                } else {
                    AppToast.show("Marked S${episode.seasonNumber} E${episode.episodeNumber} unwatched")
                }
            }
        }
    }

    /**
     * Mark an entire season as watched or unwatched in batch.
     */
    suspend fun markSeasonWatched(
        container: AppContainer,
        showId: String,
        seasonNumber: Int,
        seasonEpisodes: List<EpisodeEntity>,
        watched: Boolean,
        onShowUpdated: ((MediaItemEntity) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        val now = isoNow()
        val dao = container.mediaDao
        val syncManager = container.syncManager()

        val today = localToday()

        // Only what has aired. The season list includes announced episodes, and marking those
        // watched wrote watch history for episodes nobody could have watched, then pushed them to
        // the Mac, where the show completed early.
        //
        // Un-watching is deliberately not filtered: a row the old behaviour marked watched has to
        // stay clearable even if it never aired.
        val affected = if (watched) seasonEpisodes.filter { it.hasAired(today) } else seasonEpisodes

        // 1. Update episodes in Room. Per row rather than a season-wide UPDATE, because the
        //    un-aired ones must be left exactly as they are.
        for (ep in affected) {
            dao.setEpisodeWatched(ep.uniqueId, watched, if (watched) now else null)
        }
        dao.updateLastInteractionDate(showId, now)

        // 2. Log each episode in watch history (same append-only log as the single-episode path).
        val historyDao = container.database.watchHistoryDao()
        for (ep in affected) {
            if (watched) {
                WatchHistoryRecorder.recordWatch(
                    dao = historyDao,
                    mediaId = showId,
                    kind = WatchHistoryRecorder.KIND_TV,
                    episodeId = ep.uniqueId,
                    watchedAt = now,
                    runtimeMinutes = ep.runtime
                )
            } else {
                WatchHistoryRecorder.voidWatch(historyDao, showId, ep.uniqueId, now)
            }
        }

        // 3. Sync to Supabase
        val showTmdbId = MediaIds.tmdbIdOf(showId).toString()
        for (ep in affected) {
            syncManager.pushEpisodeWatch(showTmdbId, ep.seasonNumber, ep.episodeNumber, watched)
        }

        // 4. Auto-progress show state
        val show = dao.getMediaById(showId)
        if (show != null) {
            val allEpisodes = dao.getEpisodesListForShow(showId)
            val allWatched = isShowComplete(allEpisodes, container.database.seasonDao().getDeclaredCountsForShow(showId), today)

            if (watched) {
                if (allWatched) {
                    val updatedShow = show.copy(
                        state = "Completed",
                        lastStateChangeDate = now,
                        lastInteractionDate = now,
                        updatedAt = now
                    )
                    dao.updateState(show.id, "Completed", now)
                    syncManager.pushMediaUpdate(show.id, show.type, "Completed", show.taste)
                    WatchHistoryRecorder.completeCycle(historyDao, show.id, now)
                    onShowUpdated?.invoke(updatedShow)
                    AppToast.show("Season $seasonNumber & Entire Show Completed! 🎉")
                } else if (show.state.equals("Wishlist", ignoreCase = true)) {
                    val updatedShow = show.copy(
                        state = "Active",
                        lastStateChangeDate = now,
                        lastInteractionDate = now,
                        updatedAt = now
                    )
                    dao.updateState(show.id, "Active", now)
                    syncManager.pushMediaUpdate(show.id, show.type, "Active", show.taste)
                    onShowUpdated?.invoke(updatedShow)
                    AppToast.show("Season $seasonNumber watched • In Progress")
                } else {
                    AppToast.show("Season $seasonNumber marked as watched")
                }
            } else {
                if (show.state.equals("Completed", ignoreCase = true)) {
                    val updatedShow = show.copy(
                        state = "Active",
                        lastStateChangeDate = now,
                        lastInteractionDate = now,
                        updatedAt = now
                    )
                    dao.updateState(show.id, "Active", now)
                    syncManager.pushMediaUpdate(show.id, show.type, "Active", show.taste)
                    // Undoing the completion re-opens the run rather than starting a rewatch.
                    WatchHistoryRecorder.reopenCycle(historyDao, show.id, now)
                    onShowUpdated?.invoke(updatedShow)
                    AppToast.show("Season $seasonNumber unwatched • In Progress")
                } else {
                    AppToast.show("Season $seasonNumber marked as unwatched")
                }
            }
        }
    }

    /**
     * Toggle a movie between Completed and Active.
     */
    suspend fun toggleMovieWatched(
        container: AppContainer,
        movie: MediaItemEntity,
        onMovieUpdated: ((MediaItemEntity) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        val now = isoNow()
        val completed = movie.state.equals("Completed", ignoreCase = true)
        val nextState = if (completed) "Active" else "Completed"

        container.mediaDao.updateState(movie.id, nextState, now)
        container.syncManager().pushMediaUpdate(movie.id, movie.type, nextState, movie.taste)

        // Watch history: completing logs the viewing and closes the run; un-completing voids
        // it and re-opens the run.
        val historyDao = container.database.watchHistoryDao()
        if (completed) {
            WatchHistoryRecorder.voidWatch(historyDao, movie.id, null, now)
            WatchHistoryRecorder.reopenCycle(historyDao, movie.id, now)
        } else {
            WatchHistoryRecorder.recordWatch(
                dao = historyDao,
                mediaId = movie.id,
                kind = WatchHistoryRecorder.KIND_MOVIE,
                episodeId = null,
                watchedAt = now,
                // No runtime on the entity (the detail reads it from the TMDB cache), so the
                // movie's minutes are left to the Mac, which has cachedRuntime.
                runtimeMinutes = null
            )
            WatchHistoryRecorder.completeCycle(historyDao, movie.id, now)
        }

        val updated = movie.copy(
            state = nextState,
            lastStateChangeDate = now,
            lastInteractionDate = now,
            updatedAt = now
        )
        onMovieUpdated?.invoke(updated)

        if (completed) {
            AppToast.show("Marked \"${movie.title}\" as In Progress")
        } else {
            AppToast.show("Marked \"${movie.title}\" as Completed! 🎉")
        }
    }

    /**
     * Formats runtime into clean human readable strings e.g. "2h 15m" or "45m".
     */
    fun formatRuntime(minutes: Int?, isTV: Boolean): String {
        if (minutes == null || minutes <= 0) return "NA"
        return if (isTV) {
            "${minutes}m"
        } else {
            val h = minutes / 60
            val m = minutes % 60
            if (h > 0 && m > 0) "${h}h ${m}m"
            else if (h > 0) "${h}h"
            else "${m}m"
        }
    }
}
