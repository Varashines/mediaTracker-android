package com.vara.mediatracker.ui.m3e

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
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
import com.vara.mediatracker.ui.theme.AppTextStyles
import com.vara.mediatracker.data.model.WatchEventEntity
import com.vara.mediatracker.di.AppContainer
import java.time.Instant
import java.time.Year
import java.time.ZoneId

/**
 * The year, counted.
 *
 * A phone recap rather than a port of the Mac's: the Mac has a wide canvas, a shareable card and a
 * twelve-month heatmap, and none of that reads on a 390dp screen. This is one scrolling column of
 * numbers — what you watched, when you watched it most, and what you spent the most time on —
 * because that is what a person actually looks at on a phone in January.
 *
 * Everything is derived from the watch ledger (cycles and events), which has been synced since the
 * history work landed. No new storage: if the Mac recorded it, this can count it.
 */
private data class YearStats(
    val year: Int,
    val episodes: Int = 0,
    val minutes: Int = 0,
    val titles: Int = 0,
    val busiestMonth: String? = null,
    val busiestMonthCount: Int = 0,
    val topTitles: List<Pair<String, Int>> = emptyList()
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
        value = summarise(year, events, byId)
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
            M3EScreenHeader(
                eyebrow = "Jan – Dec",
                title = "$year in review"
            )
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

        item {
            Metric("Episodes watched", stats.episodes.toString(), "one at a time")
        }
        item {
            val hours = stats.minutes / 60
            Metric(
                label = "Hours of it",
                value = if (hours > 0) hours.toString() else "${stats.minutes}m",
                caption = if (hours > 0) "about ${hours / 24} days of screen time" else "barely a dent"
            )
        }
        item {
            Metric("Titles in play", stats.titles.toString(), "films and shows together")
        }

        val busiestMonth = stats.busiestMonth
        if (busiestMonth != null) {
            item {
                Metric(
                    label = "Busiest month",
                    value = busiestMonth,
                    caption = "${stats.busiestMonthCount} episode${if (stats.busiestMonthCount == 1) "" else "s"} that month"
                )
            }
        }

        if (stats.topTitles.isNotEmpty()) {
            item {
                M3ESectionHeader("Most watched")
            }
            items(stats.topTitles.size) { index ->
                val (title, count) = stats.topTitles[index]
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
                        Text(
                            text = title,
                            style = M3EType.rowTitleStrong(),
                            color = M3EColor.text(),
                            modifier = Modifier.weight(1f),
                            maxLines = 1
                        )
                        Text(
                            text = "$count",
                            style = M3EType.rowSupporting(),
                            color = M3EColor.textMuted()
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

private fun WatchEventEntity.inYear(year: Int): Boolean =
    runCatching {
        Instant.parse(watchedAt).atZone(ZoneId.systemDefault()).year == year
    }.getOrDefault(false)

private fun summarise(
    year: Int,
    events: List<WatchEventEntity>,
    byId: Map<String, MediaItemEntity>
): YearStats {
    if (events.isEmpty()) return YearStats(year)

    val perMonth = events.groupingBy {
        runCatching { Instant.parse(it.watchedAt).atZone(ZoneId.systemDefault()).month }.getOrNull()
    }.eachCount().filterKeys { it != null }

    val busiest = perMonth.maxByOrNull { it.value }
    val perTitle = events.groupingBy { it.mediaId }.eachCount()
        .entries
        .sortedByDescending { it.value }
        .take(5)
        .mapNotNull { (id, count) -> byId[id]?.let { it.title to count } }

    return YearStats(
        year = year,
        episodes = events.size,
        minutes = events.sumOf { it.runtimeMinutes ?: 0 },
        titles = perTitle.size.coerceAtLeast(events.map { it.mediaId }.distinct().size),
        busiestMonth = busiest?.key?.let { monthName(it) },
        busiestMonthCount = busiest?.value ?: 0,
        topTitles = perTitle
    )
}

private fun monthName(month: java.time.Month): String =
    month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
