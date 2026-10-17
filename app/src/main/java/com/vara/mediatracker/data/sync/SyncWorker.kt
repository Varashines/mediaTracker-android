package com.vara.mediatracker.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.vara.mediatracker.data.badge.BadgeComputer
import com.vara.mediatracker.data.badge.BadgeResult
import com.vara.mediatracker.data.badge.SmartBadge
import com.vara.mediatracker.data.model.MediaIds
import com.vara.mediatracker.di.AppContainer

/**
 * Pulls the library from the cloud, refreshes TMDB metadata, and raises a
 * notification for anything newly entering a time-sensitive badge window.
 *
 * This is what removes the need to open the app and manually refresh. By the time
 * the UI is drawn the database is already current, so badges reflect reality
 * without a user gesture.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = AppContainer(applicationContext)
        val prefs = container.preferences

        // Nothing configured yet is a permanent state, not a failure. Retrying would
        // burn the backoff budget forever.
        if (prefs.supabaseUrl.isBlank() || prefs.supabaseAnonKey.isBlank() || prefs.syncKey.isBlank()) {
            return Result.success(workDataOf(KEY_ITEMS to 0, KEY_SKIPPED to true))
        }

        // Reconcile, not a force-pull: `pullFromCloud` downloads everything and lets the cloud
        // overwrite local rows (so an offline edit taken since the last push was clobbered),
        // and it re-fetched the whole library every 30 minutes. `sync` diffs against the cloud
        // and only asks for rows newer than the stored watermark — an idle pass pulls nothing.
        val outcome = container.syncManager().sync(since = prefs.syncWatermark)
        val failure = outcome.exceptionOrNull()
        if (failure != null) {
            // Transient network/server problems deserve a retry with backoff. A 4xx from
            // a bad key never will, so stop rather than looping on a dead credential.
            val retryable = failure is java.io.IOException ||
                failure.message?.contains("50", ignoreCase = true) == true
            return if (retryable) Result.retry() else Result.failure()
        }

        outcome.getOrNull()?.watermark?.let { prefs.syncWatermark = it }
        val count = outcome.getOrNull()?.let { it.incoming + it.outgoing } ?: 0
        SyncScheduler.recordSyncCompletion(applicationContext)

        // Fresh data means stale badges; recompute inline so a notification can be
        // raised against the new state within the same pass.
        BadgeComputer.recompute(container.database)

        // Kick the backfills so new/changed titles get their Discovery facets and air
        // times without waiting for the next periodic tick.
        SyncScheduler.enrichMetadataNow(applicationContext)
        SyncScheduler.enrichEpisodesNow(applicationContext)

        ReleaseNotifier(applicationContext, container).announceNewReleases()

        // Fresh data means a different upcoming set; re-plan so new releases are scheduled and
        // ones watched or removed in this pass stop reminding.
        NotificationScheduler.syncAll(applicationContext, ReleaseReminderPlanner.plan(container))

        return Result.success(workDataOf(KEY_ITEMS to count, KEY_SKIPPED to false))
    }

    companion object {
        const val KEY_ITEMS = "items"
        const val KEY_SKIPPED = "skipped"
    }
}

/**
 * Decides which time-sensitive badges deserve an alert.
 *
 * Badges themselves are derived when the UI reads the database, so nothing needs
 * persisting for the UI to be correct. What does need tracking is *what has already
 * been announced* — otherwise a badge sitting in its window would re-notify on
 * every 30-minute tick.
 */
internal object ReleaseAnnouncer {

    /** Only these badges are time-sensitive enough to interrupt the user. */
    val NOTIFIABLE: Set<SmartBadge> = setOf(SmartBadge.SOON, SmartBadge.PREMIERE, SmartBadge.NEW)

    private const val PREFS = "release_announcements"

    /** Cap the history so the prefs file cannot grow without bound. */
    private const val MAX_REMEMBERED = 400

    /**
     * Stable identity for an announced badge.
     *
     * Includes the badge itself and the target date, so an item legitimately
     * re-entering a window later (a SOON badge that became a NEW badge, then a new
     * SOON for the next episode) announces again rather than being suppressed
     * forever.
     */
    fun key(mediaId: String, badge: SmartBadge, targetDate: String?): String =
        "$mediaId|${badge.label}|$targetDate"

    fun filterNew(candidates: Set<String>, alreadyAnnounced: Set<String>): Set<String> =
        candidates - alreadyAnnounced

    /**
     * SharedPreferences stores this as a StringSet, which has no defined ordering, so
     * "most recent" is not recoverable on read. The cap keeps the file bounded by
     * preferring the just-announced batch, which is the part most likely to still be
     * relevant.
     */
    fun remember(previous: Set<String>, newlyAnnounced: Set<String>): Set<String> {
        val merged = previous + newlyAnnounced
        return if (merged.size <= MAX_REMEMBERED) merged
        else newlyAnnounced.take(MAX_REMEMBERED).toSet()
    }
}