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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vara.mediatracker.data.model.MediaIds
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.di.AppContainer
import com.vara.mediatracker.ui.common.LibraryCore
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The log, rather than a summary of the log.
 *
 * A history screen that aggregates hides the thing history is for: what happened, in order. This
 * prints the rows as they are — one line per fact — in a monospace face so the columns line up, with
 * the kind of fact, the title's type, the episode and the title's state and taste each carrying
 * their own colour. Colour is doing the work a column header would otherwise do, which is the only
 * reason it can fit on a phone.
 *
 * The two row types are the ones the sync already carries: `WatchEvent` is one viewing, `WatchCycle`
 * is one sitting that a viewing belongs to. Nothing here is derived — no totals, no grouping — so
 * the screen cannot disagree with the database or with the Mac.
 */
@Composable
internal fun M3ERoughHistory(
    container: AppContainer,
    core: LibraryCore,
    onOpen: (MediaItemEntity) -> Unit = {}
) {
    val byId = remember(core) { core.items.associateBy { it.id } }

    val entries by produceState(emptyList<LogEntry>(), core.items, core.allEpisodes) {
        value = withContext(Dispatchers.Default) {
            val dao = container.database.watchHistoryDao()
            val cycles = dao.getAllCycles().associateBy { it.id }
            val out = mutableListOf<LogEntry>()
            // One viewing can exist twice in the rows: the Mac spells an episode
            // `{show}_{season}_{episode}` and this device `tv_{show}_s{season}_e{episode}`, and
            // older builds keyed on the raw string. Canonical ids make the two the same episode
            // here, and the second line is dropped rather than shown as a rewatch that never
            // happened.
            val seen = mutableSetOf<String>()

            for (event in dao.getAllEvents()) {
                if (event.voidedAt != null) continue
                val cycle = cycles[event.cycleId]
                val item = byId[event.mediaId]
                val key = event.episodeId?.let { MediaIds.parseEpisodeUniqueId(it) }
                val identity = "${event.cycleId}|${key?.let { "${it.showId}_${it.seasonNumber}_${it.episodeNumber}" } ?: "movie"}"
                if (!seen.add(identity)) continue
                out += LogEntry(
                    at = event.watchedAt,
                    verb = "WATCH",
                    isShow = cycle?.kind != "movie" && item?.type?.contains("TV", true) != false,
                    title = item?.title ?: event.mediaId,
                    detail = key?.let { "S${it.seasonNumber}E${it.episodeNumber}" },
                    state = item?.state,
                    taste = item?.taste,
                    rewatch = cycle?.isRewatch == true,
                    item = item
                )
            }

            for (cycle in cycles.values) {
                val item = byId[cycle.mediaId]
                if (item == null) continue
                out += LogEntry(
                    at = cycle.startedAt,
                    verb = "START",
                    isShow = cycle.kind != "movie",
                    title = item.title,
                    detail = null,
                    state = item.state,
                    taste = item.taste,
                    rewatch = cycle.isRewatch,
                    item = item
                )
                cycle.completedAt?.let { completed ->
                    out += LogEntry(
                        at = completed,
                        verb = "DONE",
                        isShow = cycle.kind != "movie",
                        title = item.title,
                        detail = if (cycle.isComplete) "complete" else null,
                        state = item.state,
                        taste = item.taste,
                        rewatch = cycle.isRewatch,
                        item = item
                    )
                }
            }

            out.sortedByDescending { it.at }.take(400)
        }
    }

    val byDay = remember(entries) { entries.groupBy { it.at.take(10) } }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(M3EColor.page())
            .statusBarsPadding()
            .padding(horizontal = M3ESpacing.page),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        item {
            Spacer(Modifier.height(M3ESpacing.pageVertical))
            M3EScreenHeader(
                eyebrow = if (entries.isEmpty()) "Nothing logged" else "${entries.size} events",
                title = "Watch history"
            )
            Spacer(Modifier.height(M3ESpacing.tightGap))
            Text(
                text = "WATCH viewing · START sitting · DONE season closed",
                style = M3EType.rowSupporting(),
                color = M3EColor.textMuted()
            )
            Spacer(Modifier.height(M3ESpacing.cardGap))
        }

        if (entries.isEmpty()) {
            item {
                Text(
                    text = "Nothing has been watched yet.",
                    style = M3EType.rowSupporting(),
                    color = M3EColor.textMuted()
                )
            }
        }

        byDay.forEach { (day, dayEntries) ->
            item(key = "day_$day") {
                Spacer(Modifier.height(M3ESpacing.cardGap))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = dayLabel(day),
                    style = LogStyle.copy(color = M3EColor.textMuted())
                )
                Spacer(Modifier.height(2.dp))
            }
            items(dayEntries, key = { "${it.at}_${it.verb}_${it.title}_${it.detail}" }) { entry ->
                LogRow(entry) { entry.item?.let(onOpen) }
            }
        }

        item { Spacer(Modifier.height(LocalNavClearance.current)) }
    }
}

private val LogStyle = androidx.compose.ui.text.TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    fontWeight = FontWeight.Medium
)

private data class LogEntry(
    val at: String,
    val verb: String,
    val isShow: Boolean,
    val title: String,
    val detail: String?,
    val state: String?,
    val taste: String?,
    val rewatch: Boolean,
    val item: MediaItemEntity?
)

/** One line of the log: coloured tokens on the left, the name they belong to on the right. */
@Composable
private fun LogRow(entry: LogEntry, onClick: () -> Unit) {
    val clock = runCatching {
        val raw = if (entry.at.contains("+") || entry.at.endsWith("Z")) entry.at else entry.at + "Z"
        Instant.parse(raw).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.US))
    }.getOrDefault(entry.at.take(16).replace("T", " "))

    val kindColor = if (entry.isShow) M3EColor.positive() else M3EColor.warning()
    val verbColor = when (entry.verb) {
        "WATCH" -> M3EColor.accent()
        "DONE" -> M3EColor.positive()
        else -> M3EColor.textMuted()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        // The header line is the log: fixed-width fields so they line up down the screen.
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = M3EColor.textMuted())) { append(clock) }
                append(" ")
                withStyle(SpanStyle(color = kindColor, fontWeight = FontWeight.Bold)) {
                    append(if (entry.isShow) "TV " else "MOV")
                }
                append(" ")
                withStyle(SpanStyle(color = verbColor, fontWeight = FontWeight.Bold)) {
                    append(entry.verb.padEnd(5))
                }
                entry.detail?.let {
                    append(" ")
                    withStyle(SpanStyle(color = M3EColor.accent())) { append(it) }
                }
                if (entry.rewatch) {
                    append(" ")
                    withStyle(SpanStyle(color = M3EColor.warning())) { append("RW") }
                }
            },
            style = LogStyle,
            color = M3EColor.text()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.title,
                style = LogStyle.copy(
                    color = if (entry.item != null) M3EColor.text() else M3EColor.textMuted()
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            entry.state?.let { state ->
                Spacer(Modifier.width(6.dp))
                Text(
                    text = state.take(4).lowercase(Locale.US),
                    style = LogStyle.copy(fontSize = 9.sp, color = stateColor(state))
                )
            }
            entry.taste?.let { taste ->
                val normalized = taste.lowercase(Locale.US)
                if (normalized != "none") {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (normalized == "love") "♥" else if (normalized == "like") "+" else "–",
                        style = LogStyle.copy(fontSize = 10.sp, color = when (normalized) {
                            "love" -> M3EColor.warning()
                            "like" -> M3EColor.accent()
                            else -> MaterialTheme.colorScheme.error
                        })
                    )
                }
            }
        }
    }
}

@Composable
private fun stateColor(state: String): Color = when {
    state.equals("Completed", true) -> M3EColor.positive()
    state.equals("Active", true) || state.equals("Watching", true) ||
        state.equals("Re-watching", true) -> M3EColor.accent()
    state.equals("On Hold", true) -> M3EColor.warning()
    state.equals("Dropped", true) -> MaterialTheme.colorScheme.error
    else -> M3EColor.textMuted()
}

/** "2026-10-20" → "Tue 20 Oct". */
private fun dayLabel(day: String): String = runCatching {
    LocalDate.parse(day).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.US))
}.getOrDefault(day)
