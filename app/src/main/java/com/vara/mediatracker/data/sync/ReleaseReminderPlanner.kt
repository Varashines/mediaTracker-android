package com.vara.mediatracker.data.sync

import com.vara.mediatracker.data.model.MediaIds
import com.vara.mediatracker.di.AppContainer
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Works out which upcoming releases deserve a reminder, under the user's global notification
 * preferences — master switch, one channel per media type, and the time of day a release is
 * announced. This is the Android counterpart of the Mac's `scheduleAllUpcomingNotifications`:
 * whole-library, no per-title opt-in.
 *
 * Purely a read: it schedules nothing itself, so it is cheap to call on every app start, after
 * every sync, and whenever a preference changes.
 *
 * The trigger is the release day at the delivery time. Where the Mac prefers an exact episode air
 * time when it has one, this does not — an episode is announced on the day at the time you chose
 * rather than at its air time. That is a real difference, not a rounding: it is noted so it can
 * be closed deliberately rather than discovered.
 */
object ReleaseReminderPlanner {

    /** Bounds the number of WorkManager jobs a large library can create in one pass. */
    private const val HORIZON_DAYS = 90L

    suspend fun plan(
        container: AppContainer,
        nowMillis: Long = System.currentTimeMillis()
    ): List<PlannedRelease> {
        val prefs = container.preferences
        if (!prefs.notificationsEnabled) return emptyList()

        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val horizon = today.plusDays(HORIZON_DAYS)
        val deliverySeconds = prefs.notificationsTime.toLong()

        val dao = container.mediaDao
        val media = runCatching { dao.getAllActiveMedia().first() }.getOrDefault(emptyList())
        if (media.isEmpty()) return emptyList()

        val episodesByShow = runCatching { dao.getAllEpisodesFlow().first() }
            .getOrDefault(emptyList())
            .groupBy { it.showId }

        val out = ArrayList<PlannedRelease>()
        for (item in media) {
            val isTv = item.type.contains("TV", ignoreCase = true)
            if (isTv && !prefs.notificationsTv) continue
            if (!isTv && !prefs.notificationsMovies) continue

            val dateString: String?
            val subtitle: String
            if (isTv) {
                val showKey = MediaIds.tvShowKey(item.id)
                val next = episodesByShow[showKey].orEmpty()
                    .filter { !it.isWatched && !it.airDate.isNullOrBlank() }
                    .minByOrNull { it.airDate!! }
                dateString = next?.airDate
                subtitle = "New episode"
            } else {
                dateString = item.releaseDate?.take(10)
                subtitle = "Premiering"
            }

            val date = dateString?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: continue
            if (date < today || date > horizon) continue

            val trigger = date.atStartOfDay(zone)
                .plusSeconds(deliverySeconds)
                .toInstant()
                .toEpochMilli()
            // An announcement already due today is the announcer's job, not a reminder's.
            if (trigger <= nowMillis) continue

            out += PlannedRelease(
                key = "${item.id}@$dateString",
                title = item.title,
                subtitle = subtitle,
                triggerAtMillis = trigger
            )
        }
        return out
    }
}
