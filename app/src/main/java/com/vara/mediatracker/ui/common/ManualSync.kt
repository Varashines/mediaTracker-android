package com.vara.mediatracker.ui.common

import android.content.Context
import com.vara.mediatracker.data.sync.CloudSyncManager
import com.vara.mediatracker.data.sync.SyncScheduler
import com.vara.mediatracker.di.AppContainer

/**
 * One manual, user-initiated two-way sync.
 *
 * The Home header and Settings used to each carry their own copy of this sequence — call the
 * manager, advance the watermark, stamp the completion time, report the counts — which meant the
 * two could drift apart on any of the four steps. A sync that forgets to advance the watermark
 * would re-upload the same rows forever, and one that forgets the stamp would leave the UI saying
 * "never synced", so the sequence lives in one place.
 *
 * Returns null when the sync failed; the caller decides how to say so. It deliberately does not
 * touch the UI: the Home button shows a spinner, Settings shows a toast, and neither should have to
 * know about the other's choice.
 */
object ManualSync {

    /**
     * @param resetWatermark pull everything again instead of only what changed since the last sync.
     *   The watermark makes a sync cheap and also makes a bad pull permanent: once every table is
     *   covered, "nothing newer" means a wrong local state can never be corrected.
     */
    suspend fun run(
        container: AppContainer,
        context: Context,
        resetWatermark: Boolean = false
    ): CloudSyncManager.SyncSummary? {
        val prefs = container.preferences
        if (resetWatermark) prefs.syncWatermark = null
        val result = container.syncManager().sync(since = prefs.syncWatermark)
        val summary = result.getOrNull() ?: return null

        summary.watermark?.let { prefs.syncWatermark = it }
        SyncScheduler.recordSyncCompletion(context.applicationContext)
        // Widgets read the same tables the sync just wrote; refreshing here means the home screen
        return summary
    }
}
