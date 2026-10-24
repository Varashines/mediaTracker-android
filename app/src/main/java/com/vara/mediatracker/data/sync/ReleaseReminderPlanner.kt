package com.vara.mediatracker.data.sync

import com.vara.mediatracker.data.model.EpisodeEntity
import com.vara.mediatracker.data.model.MediaIds
import com.vara.mediatracker.di.AppContainer
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
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
 * The trigger is the release's exact air instant when one is known — an `airstamp`, or a date
 * plus an "HH:mm" air time — and otherwise the release day at the user's delivery time. That is
 * the Mac's rule too: a show that airs at 20:00 is announced at 20:00, and only a release with no
 * time attached falls back to the default hour.
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
            val next: EpisodeEntity?
            if (isTv) {
                val showKey = MediaIds.tvShowKey(item.id)
                next = episodesByShow[showKey].orEmpty()
                    .filter { !it.isWatched && !it.airDate.isNullOrBlank() }
                    .minByOrNull { it.airDate!! }
                dateString = next?.airDate
                subtitle = "New episode"
            } else {
                next = null
                dateString = item.releaseDate?.take(10)
                subtitle = "Premiering"
            }

            val date = dateString?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: continue
            if (date < today || date > horizon) continue

            val trigger = triggerMillis(date, next?.airstamp, next?.airTime, deliverySeconds, zone)
            // An announcement already due today is the announcer's job, not a reminder's.
            if (trigger <= nowMillis) continue

            out += PlannedRelease(
                key = "${item.id}@$dateString",
                title = item.title,
                subtitle = subtitle,
                triggerAtMillis = trigger,
                mediaId = item.id
            )
        }
        return out
    }
}

/**
 * When a release is announced: its exact air instant if one is known, otherwise its day at the
 * delivery time.
 *
 * `airstamp` wins over date-plus-time because it already carries the correct UTC offset; a bare
 * "HH:mm" is interpreted in the device's zone, which is the only zone the app has for a title
 * without one of its own.
 */
private fun triggerMillis(
    date: LocalDate,
    airstamp: String?,
    airTime: String?,
    deliverySeconds: Long,
    zone: ZoneId
): Long {
    airstamp?.let { stamp -> parseAirstamp(stamp)?.let { return it } }

    var zoned = date.atStartOfDay(zone)
    if (!airTime.isNullOrBlank()) {
        val parts = airTime.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull()
        val minute = parts.getOrNull(1)?.toIntOrNull()
        if (hour != null && minute != null) {
            zoned = zoned.plusHours(hour.toLong()).plusMinutes(minute.toLong())
        }
    }
    // Still midnight means nothing was known about the time: use the user's delivery hour.
    if (zoned.hour == 0 && zoned.minute == 0) zoned = zoned.plusSeconds(deliverySeconds)
    return zoned.toInstant().toEpochMilli()
}

private fun parseAirstamp(stamp: String): Long? =
    runCatching { OffsetDateTime.parse(stamp).toInstant().toEpochMilli() }.getOrElse {
        runCatching { Instant.parse(stamp).toEpochMilli() }.getOrNull()
    }
