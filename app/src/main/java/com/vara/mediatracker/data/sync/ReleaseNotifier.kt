package com.vara.mediatracker.data.sync

import android.app.PendingIntent
import android.content.Intent
import android.content.Context
import com.vara.mediatracker.data.badge.BadgeEngine
import com.vara.mediatracker.data.badge.BadgeResult
import com.vara.mediatracker.data.badge.SmartBadge
import com.vara.mediatracker.data.model.MediaIds
import com.vara.mediatracker.di.AppContainer
import kotlinx.coroutines.flow.first
import java.util.Date
import java.util.Locale

/**
 * Raises notifications for titles newly entering a time-sensitive badge window.
 *
 * Badges are computed at read time, so this is purely about *telling* the user
 * something changed while the app was closed. Everything here is best-effort:
 * notifications need a runtime permission on Android 13+, and if it was declined
 * the sync still runs and badges are still correct in-app.
 */
class ReleaseNotifier(
    private val context: Context,
    private val container: AppContainer
) {

    private val prefs get() = context.getSharedPreferences("release_announcements", Context.MODE_PRIVATE)

    fun ensureChannel() = Notify.ensureChannels(context)

    suspend fun announceNewReleases() {
        // Global prefs, not a per-title list: the master switch and the channel for this
        // title's type decide whether it is worth interrupting the user for.
        val notifications = container.preferences
        if (!notifications.notificationsEnabled) return
        if (!Notify.canPost(context)) return

        val candidates = collectCandidates()
        if (candidates.isEmpty()) return

        val already = prefs.getStringSet(KEY_ANNOUNCED, emptySet()).orEmpty()
        val fresh = ReleaseAnnouncer.filterNew(candidates.keys, already)
        if (fresh.isEmpty()) return

        // One summary notification beats one per title: a slow weekend sync can touch
        // dozens of titles at once and a burst of alerts gets muted immediately.
        val summary = fresh.mapNotNull { key ->
            candidates[key]?.let { key to it }
        }

        postSummary(summary)

        prefs.edit()
            .putStringSet(KEY_ANNOUNCED, ReleaseAnnouncer.remember(already, fresh))
            .apply()
    }

    private fun postSummary(entries: List<Pair<String, MediaCandidate>>) {
        val titles = entries.take(MAX_TITLES_IN_SUMMARY).map { it.second.title }
        val extra = entries.size - titles.size

        val body = buildString {
            append(titles.joinToString("\n"))
            if (extra > 0) append("\n…and $extra more")
        }

        val open = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, com.vara.mediatracker.MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        Notify.post(
            context = context,
            id = NOTIFICATION_ID,
            channel = CHANNEL_ID,
            title = if (entries.size == 1) "New in your library" else "${entries.size} new releases in your library",
            text = titles.first(),
            bigText = body,
            contentIntent = open
        )
    }

    private suspend fun collectCandidates(): Map<String, MediaCandidate> {
        val notifications = container.preferences
        val dao = container.mediaDao
        val media = dao.getAllActiveMedia().first()
        if (media.isEmpty()) return emptyMap()

        val episodes = dao.getAllEpisodesFlow().first()
        val byShow = episodes.groupBy { it.showId }
        val now = Date()

        val out = LinkedHashMap<String, MediaCandidate>()
        for (item in media) {
            val isTv = item.type.contains("TV", ignoreCase = true)
            if (isTv && !notifications.notificationsTv) continue
            if (!isTv && !notifications.notificationsMovies) continue
            val showKey = if (isTv) MediaIds.tvShowKey(item.id) else null
            val badge: BadgeResult? = BadgeEngine.calculateBadge(
                item,
                byShow[showKey].orEmpty(),
                now
            )
            val resolved = badge?.badge ?: continue
            if (resolved !in ReleaseAnnouncer.NOTIFIABLE) continue

            val target = if (isTv) {
                byShow[showKey].orEmpty()
                    .firstOrNull { !it.isWatched && it.airDate != null }
                    ?.airDate
            } else {
                item.releaseDate
            }

            val key = ReleaseAnnouncer.key(item.id, resolved, target)
            out[key] = MediaCandidate(item.title, resolved.label, target)
        }
        return out
    }

    private data class MediaCandidate(val title: String, val badge: String, val date: String?)

    companion object {
        const val CHANNEL_ID = Notify.CHANNEL_RELEASES
        const val NOTIFICATION_ID = 4201
        private const val KEY_ANNOUNCED = "announced_keys"
        private const val MAX_TITLES_IN_SUMMARY = 5
    }
}