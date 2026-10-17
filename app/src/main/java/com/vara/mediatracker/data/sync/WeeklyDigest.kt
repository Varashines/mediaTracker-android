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
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vara.mediatracker.data.model.MediaIds
import com.vara.mediatracker.di.AppContainer
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** What was watched in the last seven days, mirroring the Mac's `WeeklyDigest`. */
data class WeeklyDigest(val shows: Int, val movies: Int, val topShows: List<String>)

/**
 * Counts shows and movies watched in the seven days ending today — the same summary the Mac's
 * `WeeklyDigestService` produces, and by the same rules: specials (season 0) are excluded, and a
 * show is counted once however many of its episodes were watched.
 */
object WeeklyDigestPlanner {

    suspend fun digest(
        container: AppContainer,
        nowMillis: Long = System.currentTimeMillis()
    ): WeeklyDigest {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val start = today.minusDays(6)

        val dao = container.mediaDao
        val media = runCatching { dao.getAllMediaList() }.getOrDefault(emptyList())
        val episodes = runCatching { dao.getAllEpisodesList() }.getOrDefault(emptyList())

        val episodeCounts = HashMap<String, Int>()
        for (episode in episodes) {
            if (!episode.isWatched || episode.seasonNumber <= 0) continue
            val day = dayOf(episode.watchedDate) ?: continue
            if (day < start || day > today) continue
            episodeCounts[episode.showId] = (episodeCounts[episode.showId] ?: 0) + 1
        }

        val movies = media.count { item ->
            item.type.equals("Movie", ignoreCase = true) &&
                item.state.equals("Completed", ignoreCase = true) &&
                dayOf(item.lastStateChangeDate)?.let { it >= start && it <= today } == true
        }

        val titlesByShowKey = media
            .filter { it.type.contains("TV", ignoreCase = true) }
            .associate { MediaIds.tvShowKey(it.id) to it.title }

        val topShows = episodeCounts.entries
            .sortedByDescending { it.value }
            .take(3)
            .mapNotNull { titlesByShowKey[it.key] }

        return WeeklyDigest(shows = episodeCounts.size, movies = movies, topShows = topShows)
    }

    /** The notification body, worded as the Mac words it. */
    fun body(digest: WeeklyDigest): String {
        val parts = mutableListOf(
            "${digest.shows} show${if (digest.shows == 1) "" else "s"}",
            "${digest.movies} movie${if (digest.movies == 1) "" else "s"}"
        )
        if (digest.topShows.isNotEmpty()) {
            parts += "including ${digest.topShows.joinToString(", ")}"
        }
        return "You watched ${parts.joinToString(" and ")}"
    }

    private fun dayOf(value: String?): LocalDate? =
        value?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}

/**
 * Posts the weekly digest.
 *
 * The Mac bakes the counts into the notification when it *schedules* it, so a digest arranged on
 * Monday describes what was watched by then and fires with those stale numbers days later. This
 * computes them when the worker actually runs — the same summary, with numbers that are true when
 * it is read.
 */
class WeeklyDigestWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = AppContainer(applicationContext)
        if (!container.preferences.digestEnabled) return Result.success()

        val digest = WeeklyDigestPlanner.digest(container)
        post(applicationContext, WeeklyDigestPlanner.body(digest))
        return Result.success()
    }

    private fun post(context: Context, body: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val manager = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Weekly digest",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "A summary of what you watched, once a week" }
            )
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Your week in review")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    companion object {
        const val CHANNEL_ID = "media_digest"
        private const val NOTIFICATION_ID = 4202
    }
}

/**
 * Keeps the weekly digest work scheduled at the day and time the user chose.
 *
 * A period of seven days is not enough on its own: WorkManager anchors the first run to whenever
 * the work was first enqueued, so without an initial delay the digest would land on whatever
 * weekday the user happened to open the screen. The delay carries the first run to the next
 * occurrence of the chosen day, and the seven-day period keeps it there.
 */
object DigestScheduler {

    private const val WORK_NAME = "weekly-digest"
    private const val TAG = "mediatracker-weekly-digest"

    fun schedule(context: Context, weekday: Int, secondsOfDay: Int) {
        val request = PeriodicWorkRequestBuilder<WeeklyDigestWorker>(7, TimeUnit.DAYS)
            .setInitialDelay(millisUntilNext(weekday, secondsOfDay), TimeUnit.MILLISECONDS)
            .addTag(TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.REPLACE,
            request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
    }

    /**
     * The Mac stores weekdays as 1..7 starting at Sunday; java.time starts at Monday. Both are
     * one-based, so the conversion is a rotation, not an offset.
     */
    fun dayOfWeek(macWeekday: Int): DayOfWeek =
        DayOfWeek.of(((macWeekday - 1) + 6) % 7 + 1)

    fun millisUntilNext(
        macWeekday: Int,
        secondsOfDay: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): Long {
        val zone = ZoneId.systemDefault()
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val target = dayOfWeek(macWeekday)

        var candidate = now.toLocalDate().atStartOfDay(zone).plusSeconds(secondsOfDay.toLong())
        var guard = 0
        while (candidate.dayOfWeek != target && guard < 8) {
            candidate = candidate.plusDays(1)
            guard++
        }
        if (!candidate.isAfter(now)) candidate = candidate.plusWeeks(1)
        return candidate.toInstant().toEpochMilli() - nowMillis
    }
}
