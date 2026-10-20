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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.data.model.WatchCycleEntity
import com.vara.mediatracker.di.AppContainer
import com.vara.mediatracker.ui.common.LibraryCore
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What has been watched, one row per title.
 *
 * The first version printed a row per cycle, which is one row per sitting — so a show watched weekly
 * for three months was a screenful of the same name, and the interesting fact (you have been
 * watching this since June) was nowhere. A title is the unit you think in; the sessions and the
 * episode count are the supporting line, and the last date says when.
 *
 * The rows underneath are the Mac's `WatchCycle`/`WatchEvent`: cycles are sittings, events are the
 * individual viewings. Counting events rather than trusting the state column is what makes the
 * episode number the number actually watched.
 */
@Composable
internal fun M3ERoughHistory(
    container: AppContainer,
    core: LibraryCore,
    onOpen: (MediaItemEntity) -> Unit = {}
) {
    val byId = remember(core) { core.items.associateBy { it.id } }
    val rows by produceState(emptyList<TitleHistory>(), core.items) {
        value = withContext(Dispatchers.Default) {
            val dao = container.database.watchHistoryDao()
            val cycles = dao.getAllCycles()
            val events = dao.getAllEvents().filter { it.voidedAt == null }.groupBy { it.cycleId }

            cycles
                .groupBy { it.mediaId }
                .map { (mediaId, titleCycles) ->
                    val watched = titleCycles.sumOf { events[it.id].orEmpty().size }
                    val last = titleCycles
                        .flatMap { cycle -> events[cycle.id].orEmpty().map { it.watchedAt } }
                        .maxOrNull()
                        ?: titleCycles.maxOfOrNull { it.startedAt }
                        ?: ""
                    TitleHistory(
                        mediaId = mediaId,
                        item = byId[mediaId],
                        title = byId[mediaId]?.title ?: mediaId,
                        isShow = titleCycles.any { it.kind != "movie" },
                        sessions = titleCycles.size,
                        watched = watched,
                        rewatch = titleCycles.any { it.isRewatch },
                        last = last
                    )
                }
                // Titles that have since left the library are history too, but they are not what a
                // glance at this screen is for.
                .filter { it.item != null && it.last.isNotBlank() }
                .sortedByDescending { it.last }
                .take(100)
        }
    }

    val byMonth = remember(rows) { rows.groupBy { it.last.take(7) } }

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
                eyebrow = if (rows.isEmpty()) "Nothing watched yet" else "${rows.size} titles",
                title = "Watch history"
            )
            Spacer(Modifier.height(M3ESpacing.cardGap))
        }

        if (rows.isEmpty()) {
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

        byMonth.forEach { (month, monthRows) ->
            item(key = "month_$month") {
                Spacer(Modifier.height(M3ESpacing.cardGap))
                M3ESectionHeader(
                    title = monthLabel(month),
                    icon = Icons.Default.History,
                    tint = M3EColor.accent()
                )
            }
            items(monthRows, key = { it.mediaId }) { row -> HistoryRow(row) { row.item?.let(onOpen) } }
        }

        item { Spacer(Modifier.height(LocalNavClearance.current)) }
    }
}

private data class TitleHistory(
    val mediaId: String,
    val item: MediaItemEntity?,
    val title: String,
    val isShow: Boolean,
    val sessions: Int,
    val watched: Int,
    val rewatch: Boolean,
    val last: String
)

@Composable
private fun HistoryRow(row: TitleHistory, onClick: () -> Unit) {
    M3ETonalCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(M3ESpacing.cardGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (row.isShow) Icons.Default.Tv else Icons.Default.Movie,
                contentDescription = null,
                tint = M3EColor.textMuted(),
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(M3ESpacing.inlineGap))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.title,
                    style = M3EType.rowTitleStrong(),
                    color = M3EColor.text(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        if (row.isShow && row.watched > 0) {
                            append(if (row.watched == 1) "1 episode" else "${row.watched} episodes")
                            if (row.sessions > 1) append(" · ${row.sessions} sittings")
                        } else {
                            append(if (row.sessions == 1) "Watched" else "${row.sessions} viewings")
                        }
                        if (row.rewatch) append(" · rewatch")
                        append(" · ")
                        append(dayLabel(row.last))
                    },
                    style = M3EType.rowSupporting(),
                    color = M3EColor.textMuted()
                )
            }
        }
    }
}

/** "2026-10" → "October 2026", falling back to the raw group when it is not a month. */
private fun monthLabel(group: String): String = runCatching {
    YearMonth.parse(group).format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US))
}.getOrDefault(group)

/** "2026-10-20T05:41:04Z" → "20 Oct". */
private fun dayLabel(iso: String): String = runCatching {
    java.time.LocalDate.parse(iso.take(10))
        .format(DateTimeFormatter.ofPattern("d MMM", Locale.US))
}.getOrDefault(iso.take(10))
