package com.vara.mediatracker.data.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * WorkManager persists its schedule across reboots on modern Android, but the
 * manifest receiver keeps the app correct on OEMs that clear scheduled work
 * aggressively, and on installs where the user revoked battery optimisations.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        if (SyncScheduler.isEnabled(context)) {
            SyncScheduler.schedulePeriodic(context)
            SyncScheduler.scheduleBadgeRefresh(context)
            SyncScheduler.scheduleEpisodeEnrichment(context)
        }
    }
}