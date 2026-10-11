package com.vara.mediatracker.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import com.vara.mediatracker.data.badge.BadgeUpdateWorker
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.TimeUnit

/**
 * Schedules the background sync that keeps the local library and its smart badges
 * current without the user opening the app.
 *
 * Two kinds of work, deliberately separated:
 *
 *  - **Periodic** work is the safety net. WorkManager will not run it more often
 *    than every 15 minutes and it survives reboots and process death, so the
 *    library converges on its own over time.
 *  - **Expedited one-shot** work is the responsiveness path. Firing it when the
 *    app comes to the foreground means badges are already correct by the time the
 *    user looks at them, which is what removes the "open the app and pull to
 *    refresh" habit.
 */
object SyncScheduler {

    const val PERIODIC_WORK_NAME = "mediatracker-periodic-sync"
    const val BADGE_WORK_NAME = "mediatracker-badge-refresh"
    const val ONESHOT_WORK_NAME = "mediatracker-immediate-sync"

    /** WorkManager's floor for periodic work is 15 minutes. */
    private const val PERIODIC_INTERVAL_MINUTES = 30L

    /** WorkManager's floor for periodic work is 15 minutes. */
    private const val BADGE_INTERVAL_MINUTES = 15L

    private fun networkConstraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** Is background syncing switched on by the user? */
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BACKGROUND_SYNC_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BACKGROUND_SYNC_ENABLED, enabled).apply()
        if (enabled) {
            schedulePeriodic(context)
            scheduleBadgeRefresh(context)
        } else {
            cancelAll(context)
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Idempotent. Safe to call from Application.onCreate and from a boot receiver —
     * UPDATE keeps the existing schedule rather than resetting the interval on
     * every process start.
     */
    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(
            PERIODIC_INTERVAL_MINUTES, TimeUnit.MINUTES
        )
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    /**
     * Badge refresh schedule.
     *
     * Intentionally unconstrained: badges expire against the wall clock, not against
     * network availability. This is a local recompute over data already in Room.
     */
    fun scheduleBadgeRefresh(context: Context) {
        val request = PeriodicWorkRequestBuilder<BadgeUpdateWorker>(
            BADGE_INTERVAL_MINUTES, TimeUnit.MINUTES
        )
            .setBackoffCriteria(BackoffPolicy.LINEAR, 2, TimeUnit.MINUTES)
            .addTag(TAG_BADGE)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            BADGE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    /** Runs a badge pass immediately — used on install and after a cloud sync. */
    fun refreshBadgesNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<BadgeUpdateWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG_BADGE)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(BADGE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(PERIODIC_WORK_NAME)
            cancelUniqueWork(ONESHOT_WORK_NAME)
            cancelUniqueWork(BADGE_WORK_NAME)
        }
    }

    /**
     * Runs a sync right away, jumping the queue when the OS allows it.
     *
     * REPLACE rather than KEEP: a stale queued sync would be worse than no sync
     * when the user is actively looking at the screen.
     */
    fun syncNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(networkConstraints())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG_SYNC)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            ONESHOT_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    /** Observable sync state, for a status row in Settings. */
    fun workInfo(context: Context): Flow<List<WorkInfo>> =
        WorkManager.getInstance(context).getWorkInfosByTagFlow(TAG_SYNC)

    /**
     * Sync when the app comes to the foreground, so badges are already current by the
     * time the user sees them.
     *
     * Throttled: returning from a sub-screen fires the same lifecycle event as coming
     * back from a long absence, and without this every rotation would queue a sync.
     */
    fun syncOnForeground(context: Context, throttleMs: Long = FOREGROUND_THROTTLE_MS) {
        if (!isEnabled(context)) return
        val now = System.currentTimeMillis()
        val last = prefs(context).getLong(KEY_LAST_FOREGROUND_SYNC, 0L)
        if (now - last < throttleMs) return
        prefs(context).edit().putLong(KEY_LAST_FOREGROUND_SYNC, now).apply()
        syncNow(context)
    }

    const val TAG_SYNC = "mediatracker-sync"
    const val TAG_BADGE = "mediatracker-badge"

    /** Not user-visible; lets Settings show a "last synced" time for background runs. */
    fun recordSyncCompletion(context: Context, atMillis: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(KEY_LAST_SYNC, atMillis).apply()
    }

    fun lastSyncTimestamp(context: Context): Long =
        prefs(context).getLong(KEY_LAST_SYNC, 0L)

    private const val KEY_BACKGROUND_SYNC_ENABLED = "background_sync_enabled"
    private const val KEY_LAST_FOREGROUND_SYNC = "last_foreground_sync"
    private const val KEY_LAST_SYNC = "last_sync"
    private const val PREFS_NAME = "media_tracker_sync_prefs"
    private const val FOREGROUND_THROTTLE_MS = 5 * 60 * 1000L
}