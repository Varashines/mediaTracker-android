package com.vara.mediatracker

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import okio.Path.Companion.toOkioPath
import com.vara.mediatracker.data.badge.BadgeComputer
import com.vara.mediatracker.data.db.EpisodeKeyReconciler
import com.vara.mediatracker.data.db.TasteCanonicalizer
import com.vara.mediatracker.data.sync.DigestScheduler
import com.vara.mediatracker.data.sync.NotificationScheduler
import com.vara.mediatracker.data.sync.ReleaseNotifier
import com.vara.mediatracker.data.sync.ReleaseReminderPlanner
import com.vara.mediatracker.data.sync.SyncScheduler
import com.vara.mediatracker.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MediaTrackerApp : Application(), SingletonImageLoader.Factory {

    val container: AppContainer by lazy { AppContainer(this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Tuned for a poster/backdrop grid: a 25% memory cache and a 100 MB disk cache so
     * scrolling back over a rail re-uses decoded bitmaps instead of re-downloading.
     * Crossfade is off — it made every rail look like it was reloading on tab switches.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory()) }
            .crossfade(false)
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
            .diskCache {
                DiskCache.Builder()
                    // Coil 3 addresses the cache through okio rather than java.io.File.
                    .directory(cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(100L * 1024 * 1024)
                    .build()
            }
            .build()

    override fun onCreate() {
        super.onCreate()

        ReleaseNotifier(this, container).ensureChannel()

        // Local housekeeping, so it is not gated on cloud sync being configured.
        SyncScheduler.schedulePurge(this)

        // Re-registering is idempotent: UPDATE keeps the existing schedule instead of
        // resetting the interval on every process start.
        if (SyncScheduler.isEnabled(this)) {
            SyncScheduler.schedulePeriodic(this)
            // Badges expire on a timer even with no connectivity, so this schedule is
            // independent of the cloud sync one.
            SyncScheduler.scheduleBadgeRefresh(this)
            SyncScheduler.scheduleEpisodeEnrichment(this)
            SyncScheduler.scheduleMetadataEnrichment(this)
        }

        appScope.launch {
            if (!container.preferences.episodeKeyMigrationDone) {
                EpisodeKeyReconciler(container.database).reconcile()
                container.preferences.episodeKeyMigrationDone = true
            }

            // Tastes written before the canonical vocabulary was enforced: "Loved"/"Liked"/
            // "Disliked" are unreadable to the Mac's enum, so a like set here used to vanish
            // on the other side. Rewrite once; the next push carries the fix to the cloud.
            if (!container.preferences.tasteCanonicalizationDone) {
                TasteCanonicalizer(container.database).canonicalize()
                container.preferences.tasteCanonicalizationDone = true
            }

            // The bulk colour backfill preserved each row's `updated_at` so it would not read as an
            // edit and force a re-pull — which means the watermarked pull can never see those
            // colours, since their stamps are older than any watermark. So they are fetched once,
            // explicitly. The flag is only set when the fetch actually reached the cloud, so a
            // launch without network tries again rather than giving up for good.
            if (!container.preferences.themeColorPullDone) {
                container.syncManager().pullThemeColors()?.let { colored ->
                    container.preferences.themeColorPullDone = true
                    android.util.Log.i("ThemeColors", "Pulled $colored poster palettes")
                }
            }

            // On a fresh install, or straight after the v3 migration, media_badges is
            // empty. Without this pass every title would render badge-less until the
            // first scheduled run — up to 15 minutes of wrong UI.
            if (container.database.badgeDao().badgeCount() == 0) {
                BadgeComputer.recompute(container.database)
            }

            // The per-title alert set is read by nothing now; drop it rather than leave a
            // stale opt-in in prefs forever.
            container.preferences.clearLegacyReleaseAlerts()

            // Refresh the reminder queue on every start: preferences may have changed, and
            // anything scheduled for a day already past has fired and is no longer in the plan.
            NotificationScheduler.syncAll(this@MediaTrackerApp, ReleaseReminderPlanner.plan(container))

            // The digest is periodic work, so a restart re-registers it rather than re-planning a
            // list: this keeps its day and time in step after a preference change and cancels it
            // outright if it was switched off.
            val preferences = container.preferences
            if (preferences.digestEnabled) {
                DigestScheduler.schedule(
                    this@MediaTrackerApp,
                    preferences.digestWeekday,
                    preferences.digestTime
                )
            } else {
                DigestScheduler.cancel(this@MediaTrackerApp)
            }
        }
    }
}