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
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.data.model.WatchEventEntity
import com.vara.mediatracker.di.AppContainer
import com.vara.mediatracker.ui.theme.AppTextStyles
import java.time.Instant
import java.time.Year
import java.time.ZoneId
import java.util.Locale

/**
 * The year, counted the way the Mac counts it.
 *
 * The first version ranked titles by episode count, which is a number that mostly says "this was a
 * sitcom": twenty-four half-hours beat a six-part drama, and every film counts as one. The Mac does
 * not rank titles at all — its year review is minutes, days and rewatches — so this follows that,
 * and where it does name titles it ranks by *time spent*, which is what a person means when they
 * say they watched a lot of something.
 *
 * Everything comes from the watch ledger, which has been syncing since the history work landed: no
 * new storage, and if the Mac recorded it this can count it. Bucketed in the device's timezone,
 * because "which month was this" is a local question.
 */
private data class TopTitle(val title: String, val minutes: Int, val episodes: Int)

private data class YearStats(
    val year: Int,
    val episodes: Int = 0,
    val movies: Int = 0,
    val minutes: Int = 0,
    val days: Int = 0,
    val rewatches: Int = 0,
    val busiestMonth: String? = null,
    val busiestMonthCount: Int = 0,
    val topTitles: List<TopTitle> = emptyList()
) {
    val isEmpty: Boolean get() = episodes == 0
}

@Composable
internal fun M3EYearReview(
    container: AppContainer,
    library: List<MediaItemEntity>
) {
    val year = Year.now().value
    val byId = library.associateBy { it.id }

    val stats by produceState(YearStats(year), year, library) {
        val events = runCatching { container.database.watchHistoryDao().getAllEvents() }
            .getOrDefault(emptyList())
            .filter { it.voidedAt == null && it.inYear(year) }
        val rewatchCycles = runCatching { container.database.watchHistoryDao().getAllCycles() }
            .getOrDefault(emptyList())
            .count { it.isRewatch && it.startedYear(year) }
        value = summarise(year, events, byId, rewatchCycles)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(M3EColor.page())
            .statusBarsPadding()
            .padding(horizontal = M3ESpacing.page),
        verticalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)
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

        // Time first: it is the only figure that compares a film with a series honestly.
        item {
            Metric(
                label = "Time watched",
                value = hoursLabel(stats.minutes),
                caption = "across ${stats.episodes} episode${if (stats.episodes == 1) "" else "s"}" +
                    if (stats.movies > 0) {
                        " and ${stats.movies} film${if (stats.movies == 1) "" else "s"}"
                    } else {
                        ""
                    }
            )
        }
        item {
            val busiestMonth = stats.busiestMonth
            Metric(
                label = "Days it happened on",
                value = stats.days.toString(),
                caption = if (busiestMonth != null) {
                    "busiest was $busiestMonth — ${stats.busiestMonthCount} that month"
                } else {
                    "spread through the year"
                }
            )
        }
        if (stats.rewatches > 0) {
            item {
                Metric(
                    label = "Rewatches",
                    value = stats.rewatches.toString(),
                    caption = "things you went back to"
                )
            }
        }

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
                        Text(
                            text = "${index + 1}",
                            style = M3EType.metric(),
                            color = M3EColor.accentText()
                        )
                        Spacer(Modifier.width(M3ESpacing.inlineGap))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.title,
                                style = M3EType.rowTitleStrong(),
                                color = M3EColor.text(),
                                maxLines = 1
                            )
                            Text(
                                // Episodes as context, never as the ranking.
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

        item { Spacer(Modifier.height(LocalNavClearance.current)) }
    }
}

/** One number, with the sentence that makes it mean something. */
@Composable
private fun Metric(label: String, value: String, caption: String) {
    Column {
        Text(text = label.uppercase(), style = AppTextStyles.MonoLabel, color = M3EColor.accentText())
        Spacer(Modifier.height(M3ESpacing.tightGap))
        Text(text = value, style = M3EType.screenTitle(), color = M3EColor.text())
        Text(text = caption, style = M3EType.rowSupporting(), color = M3EColor.textMuted())
    }
}

/** "12h 40m" — minutes stop being readable somewhere around three digits. */
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

private fun com.vara.mediatracker.data.model.WatchCycleEntity.startedYear(year: Int): Boolean =
    runCatching {
        Instant.parse(startedAt).atZone(ZoneId.systemDefault()).year == year
    }.getOrDefault(false)

private fun summarise(
    year: Int,
    events: List<WatchEventEntity>,
    byId: Map<String, MediaItemEntity>,
    rewatchCycles: Int
): YearStats {
    if (events.isEmpty()) return YearStats(year)

    val perMonth = events.groupingBy { it.localTime().month }.eachCount()
    val busiest = perMonth.maxByOrNull { it.value }

    // Time, not count: a title's weight is how long it was on screen.
    val minutesByTitle = mutableMapOf<String, Int>()
    val episodesByTitle = mutableMapOf<String, Int>()
    for (event in events) {
        episodesByTitle[event.mediaId] = (episodesByTitle[event.mediaId] ?: 0) + 1
        val runtime = event.runtimeMinutes ?: continue
        minutesByTitle[event.mediaId] = (minutesByTitle[event.mediaId] ?: 0) + runtime
    }

    val top = minutesByTitle.entries
        .sortedByDescending { it.value }
        .take(5)
        .mapNotNull { (id, minutes) ->
            byId[id]?.let { TopTitle(it.title, minutes, episodesByTitle[id] ?: 1) }
        }

    val days = events.map { it.localTime().toLocalDate() }.toSet().size
    // A film is not an episode: the ledger stores both, so they are counted apart.
    val movies = events.count { byId[it.mediaId]?.type?.contains("Movie", ignoreCase = true) == true }

    return YearStats(
        year = year,
        episodes = events.size,
        movies = movies,
        minutes = events.sumOf { it.runtimeMinutes ?: 0 },
        days = days,
        rewatches = rewatchCycles,
        busiestMonth = busiest?.key?.let {
            it.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())
        },
        busiestMonthCount = busiest?.value ?: 0,
        topTitles = top
    )
}
