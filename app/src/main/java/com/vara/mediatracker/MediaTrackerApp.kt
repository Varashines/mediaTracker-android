package com.vara.mediatracker

import android.app.Application
import com.vara.mediatracker.data.badge.BadgeComputer
import com.vara.mediatracker.data.db.EpisodeKeyReconciler
import com.vara.mediatracker.data.sync.ReleaseNotifier
import com.vara.mediatracker.data.sync.SyncScheduler
import com.vara.mediatracker.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MediaTrackerApp : Application() {

    val container: AppContainer by lazy { AppContainer(this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

            // On a fresh install, or straight after the v3 migration, media_badges is
            // empty. Without this pass every title would render badge-less until the
            // first scheduled run — up to 15 minutes of wrong UI.
            if (container.database.badgeDao().badgeCount() == 0) {
                BadgeComputer.recompute(container.database)
            }
        }
    }
}