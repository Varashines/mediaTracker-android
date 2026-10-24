package com.vara.mediatracker.data.sync

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vara.mediatracker.MediaTrackerApp
import com.vara.mediatracker.data.model.MediaIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The buttons on a release reminder.
 *
 * Reminders are the one notification that asks for something actionable — a thing you already
 * watch is out — so the answer belongs on the notification rather than three taps into the app.
 *
 * The work happens in [goAsync] with an IO coroutine: a broadcast receiver has about ten seconds
 * before the process may be killed, and a CoroutineScope tied to `onReceive` returning would be
 * cancelled before the database write finished.
 */
class ReleaseActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val app = context.applicationContext as? MediaTrackerApp ?: return
        val pending = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    ACTION_MARK_WATCHED -> markWatched(app, intent)
                    ACTION_SNOOZE -> snooze(app, intent)
                }
            } finally {
                withContext(Dispatchers.Main) {
                    // The reminder has been answered; leaving it in the shade would invite a
                    // second tap for something already done.
                    val id = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
                    if (id != 0) {
                        runCatching {
                            androidx.core.app.NotificationManagerCompat.from(context).cancel(id)
                        }
                    }
                    pending.finish()
                }
            }
        }
    }

    private suspend fun markWatched(app: MediaTrackerApp, intent: Intent) {
        val mediaId = intent.getStringExtra(EXTRA_MEDIA_ID) ?: return
        val container = app.container
        val item = container.mediaDao.getMediaById(mediaId) ?: return
        val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())

        if (item.type.contains("TV", ignoreCase = true)) {
            // "Watched" on a show means the next episode, the same rule the detail screen uses.
            val key = MediaIds.tvShowKey(item.id)
            val next = container.mediaDao.getEpisodesListForShow(key)
                .filter { it.seasonNumber > 0 && !it.isWatched }
                .minWithOrNull(compareBy({ it.seasonNumber }, { it.episodeNumber }))
            if (next != null) {
                container.mediaDao.setEpisodeWatched(next.uniqueId, true, now)
            }
        } else {
            container.mediaDao.upsertMedia(
                item.copy(state = "Completed", lastStateChangeDate = now, lastInteractionDate = now)
            )
        }

        // The reminder set is now stale: the thing it reminded about has been dealt with.
        NotificationScheduler.syncAll(app, ReleaseReminderPlanner.plan(container))
    }

    private suspend fun snooze(app: MediaTrackerApp, intent: Intent) {
        val title = intent.getStringExtra(ReleaseAlertWorker.KEY_TITLE) ?: return
        val subtitle = intent.getStringExtra(ReleaseAlertWorker.KEY_SUBTITLE).orEmpty()
        NotificationScheduler.snooze(app, title, subtitle)
    }

    companion object {
        const val ACTION_MARK_WATCHED = "com.vara.mediatracker.action.MARK_WATCHED"
        const val ACTION_SNOOZE = "com.vara.mediatracker.action.SNOOZE"
        const val EXTRA_MEDIA_ID = "media_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"

        fun pendingIntent(
            context: Context,
            action: String,
            requestCode: Int,
            mediaId: String?,
            notificationId: Int,
            title: String,
            subtitle: String
        ): PendingIntent {
            val intent = Intent(context, ReleaseActionReceiver::class.java).apply {
                this.action = action
                putExtra(EXTRA_MEDIA_ID, mediaId)
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
                putExtra(ReleaseAlertWorker.KEY_TITLE, title)
                putExtra(ReleaseAlertWorker.KEY_SUBTITLE, subtitle)
            }
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
