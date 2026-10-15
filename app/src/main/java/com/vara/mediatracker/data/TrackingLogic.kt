package com.vara.mediatracker.data

import com.vara.mediatracker.data.model.EpisodeEntity
import com.vara.mediatracker.data.model.MediaIds
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.di.AppContainer
import com.vara.mediatracker.ui.common.AppToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
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

    fun isoNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())

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
            val allWatched = allEpisodes.isNotEmpty() && allEpisodes.all { it.isWatched }

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

        // 1. Bulk update episodes in Room
        dao.setSeasonEpisodesWatched(showId, seasonNumber, watched, if (watched) now else null)
        dao.updateLastInteractionDate(showId, now)

        // 2. Log each episode in watch history (same append-only log as the single-episode path).
        val historyDao = container.database.watchHistoryDao()
        for (ep in seasonEpisodes) {
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
        for (ep in seasonEpisodes) {
            syncManager.pushEpisodeWatch(showTmdbId, ep.seasonNumber, ep.episodeNumber, watched)
        }

        // 4. Auto-progress show state
        val show = dao.getMediaById(showId)
        if (show != null) {
            val allEpisodes = dao.getEpisodesListForShow(showId)
            val allWatched = allEpisodes.isNotEmpty() && allEpisodes.all { it.isWatched }

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
