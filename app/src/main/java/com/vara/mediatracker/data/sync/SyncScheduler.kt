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
    const val BADGE_ONESHOT_WORK_NAME = "mediatracker-badge-refresh-now"
    const val ENRICHMENT_WORK_NAME = "mediatracker-episode-enrichment"
    const val ENRICHMENT_ONESHOT_WORK_NAME = "mediatracker-episode-enrichment-now"
    const val METADATA_WORK_NAME = "mediatracker-metadata-enrichment"
    const val METADATA_ONESHOT_WORK_NAME = "mediatracker-metadata-enrichment-now"
    const val ONESHOT_WORK_NAME = "mediatracker-immediate-sync"

    /** WorkManager's floor for periodic work is 15 minutes. */
    private const val PERIODIC_INTERVAL_MINUTES = 30L

    /** WorkManager's floor for periodic work is 15 minutes. */
    private const val BADGE_INTERVAL_MINUTES = 15L

    /**
     * Backfill cadence. Hourly rather than every 3 hours: the work is one-time
     * convergence, and the per-pass batch is already bounded, so a faster tick
     * shortens the blind period without adding steady-state cost once the library
     * is fully enriched and the DAO stops selecting anything.
     */
    private const val ENRICHMENT_INTERVAL_HOURS = 1L

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
            scheduleEpisodeEnrichment(context)
            scheduleMetadataEnrichment(context)
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
    /**
     * Removes titles whose delete has aged out of the undo window.
     *
     * Scheduled unconditionally, unlike the sync jobs: this is local housekeeping and has nothing to
     * do with whether the cloud is configured.
     */
    fun schedulePurge(context: Context) {
        val request = PeriodicWorkRequestBuilder<PurgeWorker>(1, java.util.concurrent.TimeUnit.DAYS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(TAG_PURGE, ExistingPeriodicWorkPolicy.KEEP, request)
    }

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

    /**
     * Episode backfill schedule.
     *
     * Network-bound and only useful while shows are still missing air dates, so it
     * runs on a slow cadence separate from both badge refresh and cloud sync. Requires
     * connectivity because it is nothing but TMDB calls.
     */
    fun scheduleEpisodeEnrichment(context: Context) {
        val request = PeriodicWorkRequestBuilder<EpisodeEnrichmentWorker>(
            ENRICHMENT_INTERVAL_HOURS, TimeUnit.HOURS
        )
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .addTag(TAG_ENRICH)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            ENRICHMENT_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    /** Runs one backfill pass immediately — useful after adding titles. */
    fun enrichEpisodesNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<EpisodeEnrichmentWorker>()
            .setConstraints(networkConstraints())
            .addTag(TAG_ENRICH)
            .build()
        // A dedicated name: reusing ENRICHMENT_WORK_NAME would collide with the
        // periodic job, and KEEP would silently drop this one-shot.
        WorkManager.getInstance(context).enqueueUniqueWork(
            ENRICHMENT_ONESHOT_WORK_NAME, ExistingWorkPolicy.REPLACE, request
        )
    }

    /**
     * Discovery-facet backfill schedule (genres / language / studios / providers).
     * Separate cadence from the episode backfill; both are TMDB-bound.
     */
    fun scheduleMetadataEnrichment(context: Context) {
        val request = PeriodicWorkRequestBuilder<MetadataEnrichmentWorker>(
            METADATA_INTERVAL_HOURS, TimeUnit.HOURS
        )
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .addTag(TAG_METADATA)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            METADATA_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    /** Runs one discovery-facet backfill pass immediately. */
    fun enrichMetadataNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<MetadataEnrichmentWorker>()
            .setConstraints(networkConstraints())
            .addTag(TAG_METADATA)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            METADATA_ONESHOT_WORK_NAME, ExistingWorkPolicy.REPLACE, request
        )
    }

    /** Runs a badge pass immediately — used on install and after a cloud sync. */
    fun refreshBadgesNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<BadgeUpdateWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG_BADGE)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(BADGE_ONESHOT_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(PERIODIC_WORK_NAME)
            cancelUniqueWork(ONESHOT_WORK_NAME)
            cancelUniqueWork(BADGE_WORK_NAME)
            cancelUniqueWork(BADGE_ONESHOT_WORK_NAME)
            cancelUniqueWork(ENRICHMENT_WORK_NAME)
            cancelUniqueWork(ENRICHMENT_ONESHOT_WORK_NAME)
            cancelUniqueWork(METADATA_WORK_NAME)
            cancelUniqueWork(METADATA_ONESHOT_WORK_NAME)
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

    /**
     * Kick the TVMaze episode backfill when the app opens, throttled separately from
     * the cloud sync because it is a heavier TMDB/TVMaze crawl. The hourly periodic
     * run is only a safety net — this is what makes air times show up promptly on a
     * fresh install instead of waiting an hour for the first periodic pass.
     */
    fun enrichOnForeground(context: Context, throttleMs: Long = FOREGROUND_ENRICH_THROTTLE_MS) {
        if (!isEnabled(context)) return
        val now = System.currentTimeMillis()
        val last = prefs(context).getLong(KEY_LAST_FOREGROUND_ENRICH, 0L)
        if (now - last >= throttleMs) {
            prefs(context).edit().putLong(KEY_LAST_FOREGROUND_ENRICH, now).apply()
            enrichEpisodesNow(context)
        }
        // Discovery facets fill faster than the episode backfill: the hub looks empty
        // until the whole library is enriched, so this runs on a much shorter throttle.
        val lastMeta = prefs(context).getLong(KEY_LAST_FOREGROUND_METADATA, 0L)
        if (now - lastMeta >= FOREGROUND_METADATA_THROTTLE_MS) {
            prefs(context).edit().putLong(KEY_LAST_FOREGROUND_METADATA, now).apply()
            enrichMetadataNow(context)
        }
    }

    /**
     * Recompute smart badges when the app opens (throttled). Badges go stale purely with
     * the passage of time — a PREMIERE/SOON window closing — so opening the app should
     * refresh them instead of waiting up to 15 minutes for the periodic run. Runs
     * regardless of the background-sync toggle, since it is local and cheap.
     */
    fun refreshBadgesOnForeground(context: Context, throttleMs: Long = FOREGROUND_THROTTLE_MS) {
        val now = System.currentTimeMillis()
        val last = prefs(context).getLong(KEY_LAST_FOREGROUND_BADGE, 0L)
        if (now - last < throttleMs) return
        prefs(context).edit().putLong(KEY_LAST_FOREGROUND_BADGE, now).apply()
        refreshBadgesNow(context)
    }

    const val TAG_SYNC = "mediatracker-sync"
    const val TAG_BADGE = "mediatracker-badge"
    const val TAG_ENRICH = "mediatracker-enrichment"
    const val TAG_METADATA = "mediatracker-metadata"
    const val TAG_PURGE = "mediatracker-purge"

    /** Not user-visible; lets Settings show a "last synced" time for background runs. */
    fun recordSyncCompletion(context: Context, atMillis: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(KEY_LAST_SYNC, atMillis).apply()
    }

    fun lastSyncTimestamp(context: Context): Long =
        prefs(context).getLong(KEY_LAST_SYNC, 0L)

    private const val KEY_BACKGROUND_SYNC_ENABLED = "background_sync_enabled"
    private const val KEY_LAST_FOREGROUND_SYNC = "last_foreground_sync"
    private const val KEY_LAST_FOREGROUND_ENRICH = "last_foreground_enrich"
    private const val KEY_LAST_FOREGROUND_METADATA = "last_foreground_metadata"
    private const val KEY_LAST_FOREGROUND_BADGE = "last_foreground_badge"
    private const val KEY_LAST_SYNC = "last_sync"
    private const val PREFS_NAME = "media_tracker_sync_prefs"
    private const val FOREGROUND_THROTTLE_MS = 5 * 60 * 1000L

    /** Episode backfill is heavier, so it runs at most every 30 minutes on foreground. */
    private const val FOREGROUND_ENRICH_THROTTLE_MS = 30 * 60 * 1000L

    /** Discovery facets: short throttle so the hub fills in quickly. */
    private const val FOREGROUND_METADATA_THROTTLE_MS = 5 * 60 * 1000L

    /** Discovery-facet backfill cadence. */
    private const val METADATA_INTERVAL_HOURS = 2L
}