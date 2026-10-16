package com.vara.mediatracker

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.vara.mediatracker.data.badge.BadgeComputer
import com.vara.mediatracker.data.db.EpisodeKeyReconciler
import com.vara.mediatracker.data.db.TasteCanonicalizer
import com.vara.mediatracker.data.sync.ReleaseNotifier
import com.vara.mediatracker.data.sync.SyncScheduler
import com.vara.mediatracker.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MediaTrackerApp : Application(), ImageLoaderFactory {

    val container: AppContainer by lazy { AppContainer(this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Tuned for a poster/backdrop grid: a 25% memory cache and a 100 MB disk cache so
     * scrolling back over a rail re-uses decoded bitmaps instead of re-downloading.
     * Crossfade is off — it made every rail look like it was reloading on tab switches.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .crossfade(false)
            .respectCacheHeaders(false)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(100L * 1024 * 1024)
                    .build()
            }
            .build()

    override fun onCreate() {
        super.onCreate()

        ReleaseNotifier(this, container).ensureChannel()

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

            // On a fresh install, or straight after the v3 migration, media_badges is
            // empty. Without this pass every title would render badge-less until the
            // first scheduled run — up to 15 minutes of wrong UI.
            if (container.database.badgeDao().badgeCount() == 0) {
                BadgeComputer.recompute(container.database)
            }
        }
    }
}