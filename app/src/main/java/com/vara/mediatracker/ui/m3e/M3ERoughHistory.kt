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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.data.model.WatchCycleEntity
import com.vara.mediatracker.data.model.WatchEventEntity
import com.vara.mediatracker.di.AppContainer
import com.vara.mediatracker.ui.common.LibraryCore
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What has been watched, newest first.
 *
 * The Mac has had this as a sidebar screen for a while; the rows underneath — cycles and events —
 * have been syncing to this device the whole time and were never shown. A cycle is one sitting (a
 * film, or one run through a season); the events are the individual viewings inside it, so the
 * count in the row is what was actually watched rather than what the state column says.
 */
@Composable
internal fun M3ERoughHistory(
    container: AppContainer,
    core: LibraryCore,
    onOpen: (MediaItemEntity) -> Unit = {}
) {
    val byId = remember(core) { core.items.associateBy { it.id } }
    val history by produceState(emptyList<WatchCycleRow>(), core.items) {
        value = withContext(Dispatchers.Default) {
            val db = container.database
            val cycles = db.watchHistoryDao().getAllCycles()
            val events = db.watchHistoryDao().getAllEvents()
                .filter { it.voidedAt == null }
                .groupBy { it.cycleId }
            cycles
                .map { cycle ->
                    WatchCycleRow(
                        cycle = cycle,
                        title = byId[cycle.mediaId]?.title ?: cycle.mediaId,
                        item = byId[cycle.mediaId],
                        watched = events[cycle.id].orEmpty().size,
                        lastWatched = events[cycle.id].orEmpty()
                            .maxOfOrNull { it.watchedAt }
                            ?: cycle.startedAt
                    )
                }
                .sortedByDescending { it.lastWatched }
        }
    }

    val byMonth = remember(history) {
        history.groupBy { it.lastWatched.take(7) }
    }

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
                eyebrow = if (history.isEmpty()) "Nothing watched yet" else "${history.size} sessions",
                title = "Watch history"
            )
            Spacer(Modifier.height(M3ESpacing.cardGap))
        }

        if (history.isEmpty()) {
            item {
                M3ETonalCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Mark something watched and it appears here, with the date it happened.",
                        style = M3EType.rowSupporting(),
                        color = M3EColor.textMuted(),
                        modifier = Modifier.padding(M3ESpacing.cardGap)
                    )
                }
            }
        }

        byMonth.forEach { (month, rows) ->
            item(key = "month_$month") {
                Spacer(Modifier.height(M3ESpacing.cardGap))
                M3ESectionHeader(
                    title = monthLabel(month),
                    icon = Icons.Default.History,
                    tint = M3EColor.accent()
                )
            }
            items(rows, key = { it.cycle.id }) { row ->
                HistoryRow(row) { row.item?.let(onOpen) }
            }
        }

        item { Spacer(Modifier.height(LocalNavClearance.current)) }
    }
}

private data class WatchCycleRow(
    val cycle: WatchCycleEntity,
    val title: String,
    val item: MediaItemEntity?,
    val watched: Int,
    val lastWatched: String
)

@Composable
private fun HistoryRow(row: WatchCycleRow, onClick: () -> Unit) {
    M3ETonalCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = if (row.item != null) onClick else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(M3ESpacing.cardGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (row.cycle.kind == "movie") Icons.Default.Movie else Icons.Default.Tv,
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
                        append(dayLabel(row.lastWatched))
                        if (row.cycle.kind != "movie" && row.watched > 0) {
                            append(" · ")
                            append(if (row.watched == 1) "1 episode" else "${row.watched} episodes")
                        }
                        if (row.cycle.isRewatch) append(" · rewatch")
                    },
                    style = M3EType.rowSupporting(),
                    color = M3EColor.textMuted()
                )
            }
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = M3EColor.accent(),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** "2026-10" → "October 2026", falling back to the raw group when it is not a month. */
private fun monthLabel(group: String): String = runCatching {
    val parsed = YearMonth.parse(group)
    parsed.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US))
}.getOrDefault(group)

/** "2026-10-20T05:41:04Z" → "20 Oct". */
private fun dayLabel(iso: String): String = runCatching {
    java.time.LocalDate.parse(iso.take(10))
        .format(DateTimeFormatter.ofPattern("d MMM", Locale.US))
}.getOrDefault(iso.take(10))
