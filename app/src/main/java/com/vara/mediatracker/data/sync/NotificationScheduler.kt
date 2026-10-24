package com.vara.mediatracker.data.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.PendingIntent
import android.content.Intent
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
    val triggerAtMillis: Long,
    /** The library row this reminder is about; the notification's actions act on it. */
    val mediaId: String? = null
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
        post(title, subtitle, inputData.getString(KEY_MEDIA_ID))
        return Result.success()
    }

    private fun post(title: String, subtitle: String, mediaId: String?) {
        val notificationId = (title + subtitle).hashCode()
        val openIntent = Intent(applicationContext, com.vara.mediatracker.MainActivity::class.java)
            .apply {
                putExtra(com.vara.mediatracker.EXTRA_OPEN_MEDIA, mediaId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        val contentIntent = PendingIntent.getActivity(
            applicationContext,
            notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // The two answers worth offering from the shade: deal with it, or defer it. Both are
        // broadcasts so the app does not have to be opened for either.
        val actions = buildList {
            if (mediaId != null) {
                add(
                    Notify.Action(
                        "Mark watched",
                        ReleaseActionReceiver.pendingIntent(
                            applicationContext,
                            ReleaseActionReceiver.ACTION_MARK_WATCHED,
                            notificationId,
                            mediaId,
                            notificationId,
                            title,
                            subtitle
                        )
                    )
                )
            }
            add(
                Notify.Action(
                    "Snooze 1 day",
                    ReleaseActionReceiver.pendingIntent(
                        applicationContext,
                        ReleaseActionReceiver.ACTION_SNOOZE,
                        notificationId + 1,
                        mediaId,
                        notificationId,
                        title,
                        subtitle
                    )
                )
            )
        }

        Notify.post(
            context = applicationContext,
            id = notificationId,
            channel = Notify.CHANNEL_RELEASES,
            title = title,
            text = subtitle.ifBlank { "Premiering now" },
            contentIntent = contentIntent,
            actions = actions
        )
    }

    companion object {
        const val KEY_TITLE = "title"
        const val KEY_SUBTITLE = "subtitle"
        const val KEY_MEDIA_ID = "media_id"
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
                        ReleaseAlertWorker.KEY_SUBTITLE to release.subtitle,
                        ReleaseAlertWorker.KEY_MEDIA_ID to release.mediaId
                    )
                )
                .addTag(TAG)
                .build()
            workManager.enqueueUniqueWork(PREFIX + release.key, ExistingWorkPolicy.REPLACE, request)
        }

        prefs.edit().putStringSet(KEY_SCHEDULED, wanted).apply()
    }

    /**
     * Re-posts a reminder a day later.
     *
     * A second unique work name rather than replacing the first: the original has already fired
     * and been consumed, and reusing its name would make a second snooze cancel the first.
     */
    fun snooze(context: Context, title: String, subtitle: String) {
        val request = OneTimeWorkRequestBuilder<ReleaseAlertWorker>()
            .setInitialDelay(1, TimeUnit.DAYS)
            .setInputData(
                workDataOf(
                    ReleaseAlertWorker.KEY_TITLE to title,
                    ReleaseAlertWorker.KEY_SUBTITLE to subtitle
                )
            )
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            PREFIX + "snooze_" + title,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    /** Used when notifications are switched off entirely. */
    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
