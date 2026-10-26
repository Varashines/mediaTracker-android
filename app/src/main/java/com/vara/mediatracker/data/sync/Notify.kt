package com.vara.mediatracker.data.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Every notification the app posts, in one place.
 *
 * Three callers had each grown their own copy of the same four steps — check POST_NOTIFICATIONS,
 * create the channel, build with the same small icon and priority, notify inside `runCatching`.
 * Eight copies of the permission check across four files is four places for a typo to silently
 * disable notifications, and it had already happened once in review.
 *
 * Channels keep their original ids: changing an id creates a *new* channel, and the user's choice
 * to mute the old one would be forgotten.
 */
object Notify {

    const val CHANNEL_RELEASES = "media_releases"
    const val CHANNEL_DIGEST = "media_digest"

    /** One button on a notification. Built by the caller, which owns the action's meaning. */
    data class Action(val label: String, val intent: PendingIntent)

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RELEASES,
                "New releases",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Alerts when something in your library premieres or airs soon" }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DIGEST,
                "Weekly digest",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "A summary of what you watched, once a week" }
        )
    }

    /** The one permission gate. Callers do not repeat the API-level dance. */
    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    fun post(
        context: Context,
        id: Int,
        channel: String,
        title: String,
        text: String,
        bigText: String? = null,
        contentIntent: PendingIntent? = null,
        actions: List<Action> = emptyList()
    ) {
        // Written out rather than delegated to canPost(): lint's MissingPermission check only
        // recognises a permission test in the function that makes the call, and `notify` is the
        // call. Same condition, same meaning — and the duplication is the price of an analyser
        // that cannot follow a helper.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        if (bigText != null) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
        }
        contentIntent?.let(builder::setContentIntent)
        actions.forEach { builder.addAction(0, it.label, it.intent) }

        runCatching {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        }
    }
}
