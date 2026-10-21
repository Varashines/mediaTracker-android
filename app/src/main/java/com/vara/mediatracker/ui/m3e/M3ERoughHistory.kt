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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vara.mediatracker.data.model.MediaIds
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.data.model.WatchCycleEntity
import com.vara.mediatracker.data.model.WatchEventEntity
import com.vara.mediatracker.di.AppContainer
import com.vara.mediatracker.ui.common.LibraryCore
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * History as ordinary Material 3 list rows.
 *
 * The log styling was doing work the data did not need: a monospace face, fixed columns and colour
 * codes for information that fits in a headline and a supporting line. A `ListItem` says it more
 * plainly — what was watched, when, and which episode — and the state and taste ride along as text
 * at the end of the supporting line.
 */
@Composable
internal fun M3ERoughHistory(
    container: AppContainer,
    core: LibraryCore,
    onOpen: (MediaItemEntity) -> Unit = {}
) {
    val byId = remember(core) { core.items.associateBy { it.id } }
    val entries by produceState(emptyList<HistoryEntry>(), core.items) {
        value = withContext(Dispatchers.Default) {
            val dao = container.database.watchHistoryDao()
            val cycles = dao.getAllCycles().associateBy { it.id }
            val seen = mutableSetOf<String>()
            val out = mutableListOf<HistoryEntry>()

            for (event in dao.getAllEvents()) {
                if (event.voidedAt != null) continue
                val cycle = cycles[event.cycleId]
                val item = byId[event.mediaId]
                val key = event.episodeId?.let { MediaIds.parseEpisodeUniqueId(it) }
                val identity = "${event.cycleId}|${key?.let { "${it.showId}_${it.seasonNumber}_${it.episodeNumber}" } ?: "movie"}"
                if (!seen.add(identity)) continue
                out += HistoryEntry(
                    id = event.id,
                    at = event.watchedAt,
                    title = item?.title ?: event.mediaId,
                    isShow = cycle?.kind != "movie",
                    detail = key?.let { "S${it.seasonNumber}E${it.episodeNumber}" },
                    state = item?.state,
                    taste = item?.taste,
                    rewatch = cycle?.isRewatch == true,
                    item = item
                )
            }

            for (cycle in cycles.values) {
                val item = byId[cycle.mediaId] ?: continue
                cycle.completedAt?.let { done ->
                    out += HistoryEntry(
                        id = "done_${cycle.id}",
                        at = done,
                        title = item.title,
                        isShow = cycle.kind != "movie",
                        detail = "season complete",
                        state = item.state,
                        taste = item.taste,
                        rewatch = cycle.isRewatch,
                        item = item
                    )
                }
            }
            out.sortedByDescending { it.at }.take(300)
        }
    }

    val byDay = remember(entries) { entries.groupBy { it.at.take(10) } }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(M3EColor.page())
            .statusBarsPadding()
            .padding(horizontal = M3ESpacing.page)
    ) {
        item {
            Spacer(Modifier.height(M3ESpacing.pageVertical))
            M3EScreenHeader(
                eyebrow = if (entries.isEmpty()) "Nothing watched yet" else "${entries.size} entries",
                title = "Watch history"
            )
            Spacer(Modifier.height(M3ESpacing.cardGap))
        }

        if (entries.isEmpty()) {
            item {
                M3ETonalCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Mark something watched and it appears here, newest first.",
                        style = M3EType.rowSupporting(),
                        color = M3EColor.textMuted(),
                        modifier = Modifier.padding(M3ESpacing.cardGap)
                    )
                }
            }
        }

        byDay.forEach { (day, dayEntries) ->
            item(key = "day_$day") {
                Spacer(Modifier.height(M3ESpacing.cardGap))
                M3ESectionHeader(
                    title = dayLabel(day),
                    icon = Icons.Default.History,
                    tint = M3EColor.accent()
                )
            }
            items(dayEntries, key = { it.id }) { entry ->
                ListItem(
                    headlineContent = {
                        Text(
                            text = entry.title,
                            style = M3EType.rowTitleStrong(),
                            color = M3EColor.text(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    supportingContent = {
                        Text(
                            text = supporting(entry),
                            style = M3EType.rowSupporting(),
                            color = M3EColor.textMuted(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = if (entry.isShow) Icons.Default.Tv else Icons.Default.Movie,
                            contentDescription = null,
                            tint = M3EColor.textMuted(),
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    trailingContent = {
                        entry.state?.let {
                            Text(
                                text = it,
                                style = M3EType.rowSupporting(),
                                color = stateColor(it)
                            )
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        item { Spacer(Modifier.height(LocalNavClearance.current)) }
    }
}

private data class HistoryEntry(
    val id: String,
    val at: String,
    val title: String,
    val isShow: Boolean,
    val detail: String?,
    val state: String?,
    val taste: String?,
    val rewatch: Boolean,
    val item: MediaItemEntity?
)

private fun supporting(entry: HistoryEntry): String = buildString {
    append(entry.detail ?: "watched")
    entry.taste?.takeIf { !it.equals("None", true) }?.let { append(" · ") ; append(it.lowercase(Locale.US)) }
    if (entry.rewatch) append(" · rewatch")
}

@Composable
private fun stateColor(state: String): Color = when {
    state.equals("Completed", true) -> M3EColor.positive()
    state.equals("Active", true) || state.equals("Re-watching", true) -> M3EColor.accent()
    state.equals("On Hold", true) -> M3EColor.warning()
    state.equals("Dropped", true) -> MaterialTheme.colorScheme.error
    else -> M3EColor.textMuted()
}

/** "2026-10-20" → "Tue 20 Oct". */
private fun dayLabel(day: String): String = runCatching {
    LocalDate.parse(day).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.US))
}.getOrDefault(day)
