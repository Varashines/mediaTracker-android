package com.vara.mediatracker.data.badge

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.vara.mediatracker.di.AppContainer

/**
 * Keeps smart badges current while the app is closed.
 *
 * Deliberately has **no network constraint**: badges are derived from dates and the
 * current time, so they go stale on a timer regardless of connectivity. Forcing this
 * to wait for Wi-Fi or mobile data would leave badges wrong for hours on a device
 * that is offline.
 *
 * WorkManager will not run periodic work more often than every 15 minutes, so the
 * schedule is set to that floor: SOON is a 48-hour window, and finer granularity than
 * that buys nothing but battery cost.
 */
class BadgeUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = AppContainer(applicationContext)
        return try {
            val count = BadgeComputer.recompute(container.database)
            Result.success(workDataOf(KEY_BADGED to count))
        } catch (e: Exception) {
            // A badge pass over the whole library should not be retried aggressively;
            // the next scheduled run is close behind anyway.
            Result.retry()
        }
    }

    companion object {
        const val KEY_BADGED = "badged"
    }
}