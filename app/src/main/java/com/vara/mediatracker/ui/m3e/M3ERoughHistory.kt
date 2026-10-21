package com.vara.mediatracker.ui.m3e

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The log as a live tail.
 *
 * Not a summary of history — the rows themselves, streaming: newest at the bottom, scrolling
 * itself, with a cursor blinking at the end. Both tables are observed rather than read once, so a
 * watch recorded on this device, or pulled from the Mac mid-screen, appears the way a log line
 * appears in a terminal.
 *
 * The colours are the columns: the type of show, the verb, the episode, and the title's state and
 * taste each get one, because five words of label per line is what a terminal cannot afford.
 */
@Composable
internal fun M3ERoughHistory(
    container: AppContainer,
    core: LibraryCore,
    onOpen: (MediaItemEntity) -> Unit = {}
) {
    val events by container.database.watchHistoryDao().observeEvents().collectAsState(emptyList())
    val cycles by container.database.watchHistoryDao().observeCycles().collectAsState(emptyList())
    val byId = remember(core) { core.items.associateBy { it.id } }

    val lines = remember(events, cycles, byId) { buildLines(events, cycles, byId) }
    val listState = rememberLazyListState()

    // Tail: whenever a line arrives, follow it.
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(M3EColor.page())
            .statusBarsPadding()
            .padding(horizontal = M3ESpacing.page)
    ) {
        Spacer(Modifier.height(M3ESpacing.pageVertical))
        M3EScreenHeader(eyebrow = "Watch history", title = "Log")
        Spacer(Modifier.height(M3ESpacing.cardGap))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(TerminalBackground)
                .padding(vertical = M3ESpacing.tightGap)
        ) {
            TerminalBar(count = lines.size)

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(460.dp)
                    .padding(horizontal = M3ESpacing.inlineGap),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                if (lines.isEmpty()) {
                    item {
                        Text(
                            text = "no entries yet",
                            style = LogStyle.copy(color = DimText)
                        )
                    }
                }
                items(lines, key = { it.key }) { line ->
                    LogLine(line) {
                        line.item?.let(onOpen)
                    }
                }
                item { Cursor() }
            }
        }

        Spacer(Modifier.height(M3ESpacing.tightGap))
        Text(
            text = "WATCH viewing · START sitting · DONE closed · live, newest last",
            style = M3EType.rowSupporting(),
            color = M3EColor.textMuted()
        )
        Spacer(Modifier.height(M3ESpacing.cardGap))
        Spacer(Modifier.height(LocalNavClearance.current))
    }
}

private val TerminalBackground = Color(0xFF0D1012)
private val TerminalText = Color(0xFFD7DEE3)
private val DimText = Color(0xFF6C7A82)
private val TvColor = Color(0xFF7BD88F)
private val MovieColor = Color(0xFFE3B341)
private val WatchColor = Color(0xFF6FC3D9)
private val DoneColor = Color(0xFF7BD88F)
private val LoveColor = Color(0xFFE3B341)
private val DislikeColor = Color(0xFFE06C75)

private val LogStyle = androidx.compose.ui.text.TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 11.5.sp,
    fontWeight = FontWeight.Medium
)

private val LogClock = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.US)

private data class LogLine(
    val key: String,
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

/** Events first (what happened), then cycle boundaries, all ordered oldest → newest. */
private fun buildLines(
    events: List<com.vara.mediatracker.data.model.WatchEventEntity>,
    cycles: List<com.vara.mediatracker.data.model.WatchCycleEntity>,
    byId: Map<String, MediaItemEntity>
): List<LogLine> {
    val cycleById = cycles.associateBy { it.id }
    val seen = mutableSetOf<String>()
    val out = mutableListOf<LogLine>()

    for (event in events) {
        val cycle = cycleById[event.cycleId]
        val item = byId[event.mediaId]
        val key = event.episodeId?.let { MediaIds.parseEpisodeUniqueId(it) }
        val identity = "${event.cycleId}|${key?.let { "${it.showId}_${it.seasonNumber}_${it.episodeNumber}" } ?: "movie"}"
        if (!seen.add(identity)) continue
        out += LogLine(
            key = "e_${event.id}",
            at = event.watchedAt,
            verb = "WATCH",
            isShow = cycle?.kind != "movie",
            title = item?.title ?: event.mediaId,
            detail = key?.let { "S${it.seasonNumber}E${it.episodeNumber}" },
            state = item?.state,
            taste = item?.taste,
            rewatch = cycle?.isRewatch == true,
            item = item
        )
    }

    for (cycle in cycles) {
        val item = byId[cycle.mediaId] ?: continue
        out += LogLine(
            key = "c_start_${cycle.id}",
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
            out += LogLine(
                key = "c_done_${cycle.id}",
                at = completed,
                verb = "DONE",
                isShow = cycle.kind != "movie",
                title = item.title,
                detail = if (cycle.isComplete) "closed" else null,
                state = item.state,
                taste = item.taste,
                rewatch = cycle.isRewatch,
                item = item
            )
        }
    }

    return out.sortedBy { it.at }
}

@Composable
private fun TerminalBar(count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = M3ESpacing.inlineGap, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val pulse by rememberInfiniteTransition(label = "live").animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "liveDot"
        )
        Box(
            modifier = Modifier
                .size(7.dp)
                .alpha(pulse)
                .clip(RoundedCornerShape(50))
                .background(DoneColor)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "watchlog · live",
            style = LogStyle.copy(color = DimText, fontSize = 10.sp)
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "$count",
            style = LogStyle.copy(color = DimText, fontSize = 10.sp)
        )
    }
}

@Composable
private fun LogLine(line: LogLine, onClick: () -> Unit) {
    val kindColor = if (line.isShow) TvColor else MovieColor
    val verbColor = when (line.verb) {
        "WATCH" -> WatchColor
        "DONE" -> DoneColor
        else -> DimText
    }
    val clock = runCatching {
        Instant.parse(if (line.at.endsWith("Z") || line.at.contains("+")) line.at else line.at + "Z")
            .atZone(ZoneId.systemDefault())
            .format(LogClock)
    }.getOrElse { line.at.take(16).replace("T", " ") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
    ) {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = DimText)) { append(clock) }
                append(" ")
                withStyle(SpanStyle(color = kindColor)) { append(if (line.isShow) "TV " else "MOV") }
                append(" ")
                withStyle(SpanStyle(color = verbColor, fontWeight = FontWeight.Bold)) {
                    append(line.verb)
                }
                line.detail?.let {
                    append(" ")
                    withStyle(SpanStyle(color = WatchColor)) { append(it) }
                }
                if (line.rewatch) {
                    append(" ")
                    withStyle(SpanStyle(color = LoveColor)) { append("RW") }
                }
            },
            style = LogStyle
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = if (line.item != null) TerminalText else DimText)) {
                        append(line.title)
                    }
                },
                style = LogStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            line.state?.let { state ->
                Spacer(Modifier.width(6.dp))
                Text(
                    text = state.take(4).lowercase(Locale.US),
                    style = LogStyle.copy(fontSize = 9.sp, color = stateColor(state))
                )
            }
            line.taste?.let { taste ->
                val normalized = taste.lowercase(Locale.US)
                if (normalized != "none") {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (normalized == "love") "♥" else if (normalized == "like") "+" else "–",
                        style = LogStyle.copy(color = when (normalized) {
                            "love" -> LoveColor
                            "like" -> WatchColor
                            else -> DislikeColor
                        })
                    )
                }
            }
        }
    }
}

@Composable
private fun Cursor() {
    val pulse by rememberInfiniteTransition(label = "cursor").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "cursorBlink"
    )
    Text(
        text = "▍",
        style = LogStyle.copy(color = WatchColor),
        modifier = Modifier.alpha(pulse)
    )
}

private fun stateColor(state: String): Color = when {
    state.equals("Completed", true) -> DoneColor
    state.equals("Watching", true) || state.equals("Active", true) ||
        state.equals("Re-watching", true) -> WatchColor
    state.equals("On Hold", true) -> MovieColor
    state.equals("Dropped", true) -> DislikeColor
    else -> DimText
}
