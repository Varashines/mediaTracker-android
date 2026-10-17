package com.vara.mediatracker.data.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/** One upcoming release worth a reminder, already filtered by the user's notification prefs. */
data class PlannedRelease(
    val key: String,
    val title: String,
    val subtitle: String,
    val triggerAtMillis: Long
)

/**
 * Posts the reminder for a single upcoming release.
 *
 * Reminders go through WorkManager rather than exact alarms: exact alarms need a special
 * permission on Android 12+, and a release premiere is not precise enough to justify asking
 * for it. WorkManager may slip a few minutes under Doze, which is fine for "something you're
 * tracking drops today".
 */
class ReleaseAlertWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val title = inputData.getString(KEY_TITLE) ?: return Result.success()
        val subtitle = inputData.getString(KEY_SUBTITLE).orEmpty()
        post(title, subtitle)
        return Result.success()
    }

    private fun post(title: String, subtitle: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val manager = NotificationManagerCompat.from(applicationContext)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    ReleaseNotifier.CHANNEL_ID,
                    "New releases",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "Alerts when something in your library premieres or airs" }
            )
        }

        val notification = NotificationCompat.Builder(applicationContext, ReleaseNotifier.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(subtitle.ifBlank { "Premiering now" })
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        runCatching {
            manager.notify((title + subtitle).hashCode(), notification)
        }
    }

    companion object {
        const val KEY_TITLE = "title"
        const val KEY_SUBTITLE = "subtitle"
    }
}

/**
 * Keeps the set of scheduled release reminders in step with what is upcoming.
 *
 * Whole-library, not per-title: the Mac has no per-title opt-in, and a per-release toggle grew
 * a stored set without bound while duplicating information the app already has. Callers pass
 * the full plan every time (see [ReleaseReminderPlanner]); anything previously scheduled that is
 * no longer in the plan is cancelled, so a title marked watched, dropped from the library or
 * left in the past stops reminding without anyone tracking it.
 *
 * Unique work is named by media id and date, so re-planning is idempotent.
 */
object NotificationScheduler {

    private const val PREFIX = "release-alert-"
    private const val TAG = "mediatracker-release-alert"
    private const val PREFS = "release_reminders"
    private const val KEY_SCHEDULED = "scheduled_keys"

    fun syncAll(context: Context, releases: List<PlannedRelease>) {
        val workManager = WorkManager.getInstance(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getStringSet(KEY_SCHEDULED, emptySet()).orEmpty()
        val wanted = releases.mapTo(HashSet()) { it.key }

        for (stale in previous - wanted) {
            workManager.cancelUniqueWork(PREFIX + stale)
        }

        val now = System.currentTimeMillis()
        for (release in releases) {
            val delay = release.triggerAtMillis - now
            if (delay <= 0L) continue
            val request = OneTimeWorkRequestBuilder<ReleaseAlertWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(
                    workDataOf(
                        ReleaseAlertWorker.KEY_TITLE to release.title,
                        ReleaseAlertWorker.KEY_SUBTITLE to release.subtitle
                    )
                )
                .addTag(TAG)
                .build()
            workManager.enqueueUniqueWork(PREFIX + release.key, ExistingWorkPolicy.REPLACE, request)
        }

        prefs.edit().putStringSet(KEY_SCHEDULED, wanted).apply()
    }

    /** Used when notifications are switched off entirely. */
    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
