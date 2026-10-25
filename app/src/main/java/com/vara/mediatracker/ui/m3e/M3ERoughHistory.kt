package com.vara.mediatracker.ui.m3e

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.vara.mediatracker.data.model.MediaIds
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.data.model.WatchCycleEntity
import com.vara.mediatracker.data.model.WatchEventEntity
import com.vara.mediatracker.di.AppContainer
import com.vara.mediatracker.ui.common.LibraryCore
import com.vara.mediatracker.ui.theme.LocalSemanticColors
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
    // A cold start renders the empty state before Room answers — "0 titles", "Your library is up
    // to date" — which reads as an empty library rather than a loading one.
    if (!core.loaded) {
        M3ESkeleton()
        return
    }

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
                    mediaId = event.mediaId,
                    at = event.watchedAt,
                    title = item?.title ?: event.mediaId,
                    isShow = cycle?.kind != "movie",
                    detail = key?.let { "S${it.seasonNumber}E${it.episodeNumber}" },
                    season = key?.seasonNumber,
                    episode = key?.episodeNumber,
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
                        mediaId = cycle.mediaId,
                        at = done,
                        title = item.title,
                        isShow = cycle.kind != "movie",
                        detail = "season complete",
                        season = null,
                        episode = null,
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
    // Which collapsed groups are open. Keyed by day + title, so opening one sitting does not open
    // every copy of the show in the list.
    var expanded by remember { mutableStateOf(setOf<String>()) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(M3EColor.page())
            .statusBarsPadding()
            .padding(horizontal = M3ESpacing.page),
        verticalArrangement = Arrangement.spacedBy(M3ESpacing.tightGap)
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
                EmptyState(
                    title = "Your history starts with one episode.",
                    body = "Watch something and the timeline begins."
                )
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
            val groups = dayEntries.groupBy { it.mediaId }.values.toList()
            groups.forEach { group ->
                val first = group.first()
                val key = "${day}_${first.mediaId}"
                if (group.size == 1) {
                    item(key = "row_${first.id}") {
                        HistoryRow(first) { first.item?.let(onOpen) }
                    }
                } else {
                    item(key = "group_$key") {
                        HistoryRow(
                            entry = first.copy(
                                id = key,
                                detail = rangeLabel(group),
                                episode = null,
                                season = null
                            ),
                            count = group.size,
                            expanded = key in expanded,
                            onClick = {
                                expanded = if (key in expanded) expanded - key else expanded + key
                            }
                        )
                    }
                    if (key in expanded) {
                        items(group, key = { it.id }) { entry ->
                            HistoryRow(entry) { entry.item?.let(onOpen) }
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(LocalNavClearance.current)) }
    }
}

private data class HistoryEntry(
    val id: String,
    val mediaId: String,
    val at: String,
    val title: String,
    val isShow: Boolean,
    val detail: String?,
    val season: Int?,
    val episode: Int?,
    val state: String?,
    val taste: String?,
    val rewatch: Boolean,
    val item: MediaItemEntity?
)


/**
 * One row, tinted by how it was rated.
 *
 * The Home rails colour a row's container for what it is; here the row's colour is the reaction —
 * love amber, like accent, dislike red — so a glance down the list groups itself by feeling rather
 * than by a tag at the end of the line. Unrated keeps the plain card.
 */
@Composable
private fun HistoryRow(
    entry: HistoryEntry,
    count: Int? = null,
    expanded: Boolean = false,
    onClick: () -> Unit
) {
    // Same shape as the Continue watching row: poster, two lines, nothing else.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(M3EShape.card())
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = entry.item?.posterUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = 40.dp, height = 60.dp)
                .clip(M3EShape.chip())
        )
        Spacer(Modifier.width(M3ESpacing.inlineGap))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = M3EType.rowTitle(),
                color = M3EColor.text(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = entry.detail ?: supporting(entry),
                style = M3EType.rowSupporting(),
                color = M3EColor.textMuted(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (count != null) {
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = M3EColor.textMuted(),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** "S1E1–E7 · 7 episodes" when the run is contiguous, "7 episodes" when it is not. */
private fun rangeLabel(group: List<HistoryEntry>): String {
    val eps = group.mapNotNull { it.episode }
    val seasons = group.mapNotNull { it.season }.distinct()
    val countLabel = if (group.size == 1) "1 episode" else "${group.size} episodes"
    if (seasons.size != 1 || eps.size != group.size) return countLabel
    val sorted = eps.sorted()
    val contiguous = sorted.zipWithNext().all { (a, b) -> b == a + 1 }
    return if (contiguous && sorted.size > 1) {
        "S${seasons.first()}E${sorted.first()}–E${sorted.last()} · ${group.size} episodes"
    } else {
        countLabel
    }
}

private fun supporting(entry: HistoryEntry): String = buildString {
    append(entry.detail ?: "watched")
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
