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

/**
 * Posts the one-shot reminder a user asked for with "Set Alert" on the release calendar.
 *
 * Reminders are scheduled through WorkManager rather than exact alarms: exact alarms
 * need a special permission on Android 12+, and a release premiere is not precise
 * enough to justify asking for it. WorkManager may slip a few minutes under Doze,
 * which is fine for "something you're tracking drops today".
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
 * Schedules and cancels per-release reminders. Unique work is named by media id and
 * date so toggling a reminder on then off is idempotent, and two releases for the same
 * title on different dates never clobber each other.
 */
object ReleaseAlertScheduler {

    private const val PREFIX = "release-alert-"
    private const val TAG = "mediatracker-release-alert"

    fun schedule(
        context: Context,
        mediaId: String,
        dateString: String,
        title: String,
        subtitle: String,
        triggerAtMillis: Long
    ) {
        val delay = triggerAtMillis - System.currentTimeMillis()
        if (delay <= 0L) return

        val request = OneTimeWorkRequestBuilder<ReleaseAlertWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(
                workDataOf(
                    ReleaseAlertWorker.KEY_TITLE to title,
                    ReleaseAlertWorker.KEY_SUBTITLE to subtitle
                )
            )
            .addTag(TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueName(mediaId, dateString),
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun cancel(context: Context, mediaId: String, dateString: String) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueName(mediaId, dateString))
    }

    private fun uniqueName(mediaId: String, dateString: String) = "$PREFIX${mediaId}_$dateString"
}
