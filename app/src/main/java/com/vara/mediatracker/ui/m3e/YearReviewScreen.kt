package com.vara.mediatracker.ui.m3e

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.vara.mediatracker.data.model.EpisodeEntity
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.data.model.WatchCycleEntity
import com.vara.mediatracker.data.model.WatchEventEntity
import com.vara.mediatracker.di.AppContainer
import com.vara.mediatracker.ui.theme.AppTextStyles
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.Year
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * The year, as a handful of sentences rather than a dashboard.
 *
 * Four numbers stacked read like a bank statement, so this leads with a claim about the person
 * instead: how long they were watching, what kind of watcher they were, which day of the week had
 * their name on it, and the two titles that bookend the year. The leaderboard comes last, where a
 * list belongs.
 *
 * Runtimes are the load-bearing number, and the ledger's own is nullable — anything logged before
 * runtimes were filled in counted as zero minutes. Every event falls back to its episode's runtime,
 * then to the show's median episode, and the card admits it when that happened.
 */
private data class TopTitle(val title: String, val minutes: Int, val episodes: Int)

private data class Bookend(val title: String, val day: LocalDate)

private data class YearStats(
    val year: Int = Year.now().value,
    val episodes: Int = 0,
    val films: Int = 0,
    val minutes: Int = 0,
    val estimatedEvents: Int = 0,
    val days: Int = 0,
    val rewatches: Int = 0,
    val busiestWeekday: DayOfWeek? = null,
    val busiestDay: LocalDate? = null,
    val busiestDayMinutes: Int = 0,
    val first: Bookend? = null,
    val last: Bookend? = null,
    val persona: Persona = Persona.Dabbler,
    val topTitles: List<TopTitle> = emptyList()
) {
    val isEmpty: Boolean get() = episodes == 0
}

/** Six shapes of watcher, each with the rule that produced it. */
private enum class Persona(val label: String, val line: String) {
    Rewatcher("The returner", "You go back to what you love. More than once."),
    Completionist("The finisher", "You start things and you end them. Rare."),
    Binger("The swallower", "Whole seasons, in one sitting, more than once."),
    Sampler("The grazer", "Lots of beginnings. Fewer endings."),
    Regular("The regular", "A steady habit, most days, no drama."),
    Dabbler("The dabbler", "A quiet year. It still counts.")
}

@Composable
internal fun M3EYearReview(
    container: AppContainer,
    library: List<MediaItemEntity>,
    episodes: List<EpisodeEntity>
) {
    val year = Year.now().value
    val byId = library.associateBy { it.id }

    val stats by produceState(YearStats(year), year, library, episodes) {
        val events = runCatching { container.database.watchHistoryDao().getAllEvents() }
            .getOrDefault(emptyList())
            .filter { it.voidedAt == null && it.inYear(year) }
        val cycles = runCatching { container.database.watchHistoryDao().getAllCycles() }
            .getOrDefault(emptyList())
        value = summarise(year, events, byId, episodes, cycles)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(M3EColor.page())
            .statusBarsPadding()
            .padding(horizontal = M3ESpacing.page),
        verticalArrangement = Arrangement.spacedBy(M3ESpacing.sectionGap)
    ) {
        item {
            Spacer(Modifier.height(M3ESpacing.pageVertical))
            M3EScreenHeader(eyebrow = "Jan – Dec", title = "$year in review")
        }

        if (stats.isEmpty) {
            item {
                EmptyState(
                    title = "Nothing to add up yet.",
                    body = "Watch something — or mark something watched — and this fills in as the year goes."
                )
            }
            item { Spacer(Modifier.height(LocalNavClearance.current)) }
            return@LazyColumn
        }

        // 1. The claim, not the statistic.
        item {
            Beat(
                kicker = "The damage",
                headline = hoursLabel(stats.minutes),
                body = damageLine(stats.minutes) + splitLine(stats.films, stats.episodes)
            )
        }

        // 2. Who you were this year.
        item { Beat(kicker = stats.persona.label.uppercase(), headline = stats.persona.line, body = null) }

        // 3. The rhythm, in a sentence.
        item {
            val weekday = stats.busiestWeekday
            Beat(
                kicker = "Your rhythm",
                headline = if (weekday != null) "${weekday.plural()} were yours" else "No pattern yet",
                body = rhythmLine(stats)
            )
        }

        // 4. Bookends: the two dates worth remembering.
        val first = stats.first
        val last = stats.last
        if (first != null && last != null) {
            item {
                Beat(
                    kicker = "You opened with / closed with",
                    headline = "${first.title} → ${last.title}",
                    body = "${first.day.format(shortDate)} → ${last.day.format(shortDate)}"
                )
            }
        }

        // 5. Then, and only then, the list.
        if (stats.topTitles.isNotEmpty()) {
            item { M3ESectionHeader("Most time spent") }
            items(stats.topTitles.size) { index ->
                val entry = stats.topTitles[index]
                M3ETonalCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(M3ESpacing.cardGap),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "${index + 1}", style = M3EType.metric(), color = M3EColor.accentText())
                        Spacer(Modifier.width(M3ESpacing.inlineGap))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.title,
                                style = M3EType.rowTitleStrong(),
                                color = M3EColor.text(),
                                maxLines = 1
                            )
                            Text(
                                text = if (entry.episodes > 1) "${entry.episodes} episodes" else "watched",
                                style = M3EType.rowSupporting(),
                                color = M3EColor.textMuted()
                            )
                        }
                        Text(
                            text = hoursLabel(entry.minutes),
                            style = M3EType.rowTitleStrong(),
                            color = M3EColor.text()
                        )
                    }
                }
            }
        }

        if (stats.estimatedEvents > 0) {
            item {
                Text(
                    // Said plainly rather than hidden: a total that quietly guessed is worse than
                    // one that admits it guessed.
                    text = "${stats.estimatedEvents} of these had no runtime recorded, so their length is estimated.",
                    style = M3EType.rowSupporting(),
                    color = M3EColor.textMuted()
                )
            }
        }

        item { Spacer(Modifier.height(LocalNavClearance.current)) }
    }
}

/** A kicker, a sentence, and optionally a second one. */
@Composable
private fun Beat(kicker: String, headline: String, body: String?) {
    Column {
        Text(text = kicker.uppercase(), style = AppTextStyles.MonoLabel, color = M3EColor.accentText())
        Spacer(Modifier.height(M3ESpacing.tightGap))
        Text(text = headline, style = M3EType.sectionTitle(), color = M3EColor.text())
        if (body != null) {
            Spacer(Modifier.height(M3ESpacing.tightGap))
            Text(text = body, style = M3EType.rowSupporting(), color = M3EColor.textMuted())
        }
    }
}

private val shortDate: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

private fun DayOfWeek.plural(): String =
    getDisplayName(TextStyle.FULL, Locale.getDefault()) + "s"

/** A number only lands when it is compared to something. */
private fun damageLine(minutes: Int): String {
    val days = minutes / (60 * 24)
    return when {
        days >= 2 -> "That is $days whole days of your life, spent in the dark."
        days == 1 -> "That is a full day of your life, spent in the dark."
        minutes / 60 >= 12 -> "That is most of a day, spent in the dark."
        minutes / 60 >= 1 -> "A respectable dent in the year."
        else -> "Barely a scratch. There is still time."
    }
}

private fun splitLine(films: Int, episodes: Int): String = buildString {
    if (episodes > 0) {
        append(" ")
        append(if (episodes == 1) "One episode" else "$episodes episodes")
    }
    if (films > 0) {
        append(if (episodes == 0) " " else " and ")
        append(if (films == 1) "one film" else "$films films")
    }
    append(".")
}

private fun rhythmLine(stats: YearStats): String {
    val parts = mutableListOf<String>()
    stats.busiestDay?.let { day ->
        parts += "Bet on ${day.format(shortDate)} — ${hoursLabel(stats.busiestDayMinutes)} in one day."
    }
    parts += "${stats.days} day${if (stats.days == 1) "" else "s"} all told."
    return parts.joinToString(" ")
}

private fun hoursLabel(minutes: Int): String {
    if (minutes <= 0) return "0m"
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0 -> "${rest}m"
        rest == 0 -> "${hours}h"
        else -> "${hours}h ${rest}m"
    }
}

private fun WatchEventEntity.localTime() =
    Instant.parse(watchedAt).atZone(ZoneId.systemDefault())

private fun WatchEventEntity.inYear(year: Int): Boolean =
    runCatching { localTime().year == year }.getOrDefault(false)

private fun summarise(
    year: Int,
    events: List<WatchEventEntity>,
    byId: Map<String, MediaItemEntity>,
    episodes: List<EpisodeEntity>,
    cycles: List<WatchCycleEntity>
): YearStats {
    if (events.isEmpty()) return YearStats(year)

    // Runtimes: the ledger's own, else the episode's, else the show's median.
    val episodeRuntime = episodes.mapNotNull { e -> e.runtime?.let { e.uniqueId to it } }.toMap()
    val showMedian = episodes
        .filter { it.runtime != null }
        .groupBy { it.showId }
        .mapValues { (_, rows) -> rows.mapNotNull { it.runtime }.sorted()[rows.size / 2] }

    var estimated = 0
    val minutesByTitle = mutableMapOf<String, Int>()
    val episodesByTitle = mutableMapOf<String, Int>()
    var totalMinutes = 0

    for (event in events) {
        val own = event.runtimeMinutes
        val fallback = event.episodeId?.let { episodeRuntime[it] } ?: showMedian[event.mediaId]
        val runtime = own ?: fallback
        if (own == null && fallback != null) estimated++
        if (runtime != null) {
            totalMinutes += runtime
            minutesByTitle[event.mediaId] = (minutesByTitle[event.mediaId] ?: 0) + runtime
        }
        episodesByTitle[event.mediaId] = (episodesByTitle[event.mediaId] ?: 0) + 1
    }

    val byDate = events.groupBy { it.localTime().toLocalDate() }
    val busiest = byDate.maxByOrNull { it.value.size }
    val busiestMinutes = busiest?.value?.sumOf { ev ->
        ev.runtimeMinutes ?: ev.episodeId?.let { episodeRuntime[it] } ?: showMedian[ev.mediaId] ?: 0
    } ?: 0

    val sorted = events.sortedBy { it.localTime() }
    val firstEvent = sorted.firstOrNull()
    val lastEvent = sorted.lastOrNull()

    val rewatches = cycles.count {
        it.isRewatch && runCatching {
            Instant.parse(it.startedAt).atZone(ZoneId.systemDefault()).year == year
        }.getOrDefault(false)
    }

    val films = events.count { byId[it.mediaId]?.type?.contains("Movie", ignoreCase = true) == true }
    val activeDays = byDate.size
    val startedTitles = events.map { it.mediaId }.distinct().size
    val finished = events.mapNotNull { byId[it.mediaId] }.count { it.state.equals("Completed", true) }

    return YearStats(
        year = year,
        episodes = events.size,
        films = films,
        minutes = totalMinutes,
        estimatedEvents = estimated,
        days = activeDays,
        rewatches = rewatches,
        busiestWeekday = busiest?.key?.dayOfWeek,
        busiestDay = busiest?.key,
        busiestDayMinutes = busiestMinutes,
        first = firstEvent?.let { Bookend(byId[it.mediaId]?.title ?: "something", it.localTime().toLocalDate()) },
        last = lastEvent?.let { Bookend(byId[it.mediaId]?.title ?: "something", it.localTime().toLocalDate()) },
        persona = persona(
            rewatches = rewatches,
            episodesPerActiveDay = if (activeDays > 0) events.size.toFloat() / activeDays else 0f,
            completionRate = if (startedTitles > 0) finished.toFloat() / startedTitles else 0f,
            activeDays = activeDays
        ),
        topTitles = minutesByTitle.entries
            .sortedByDescending { it.value }
            .take(5)
            .mapNotNull { (id, minutes) ->
                byId[id]?.let { TopTitle(it.title, minutes, episodesByTitle[id] ?: 1) }
            }
    )
}

private fun persona(
    rewatches: Int,
    episodesPerActiveDay: Float,
    completionRate: Float,
    activeDays: Int
): Persona = when {
    activeDays < 5 -> Persona.Dabbler
    rewatches >= 5 -> Persona.Rewatcher
    episodesPerActiveDay >= 3.5f -> Persona.Binger
    completionRate >= 0.75f -> Persona.Completionist
    completionRate <= 0.3f && activeDays >= 20 -> Persona.Sampler
    else -> Persona.Regular
}
