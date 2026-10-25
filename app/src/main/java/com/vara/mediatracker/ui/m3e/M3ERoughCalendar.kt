package com.vara.mediatracker.ui.m3e

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.vara.mediatracker.data.model.EpisodeEntity
import com.vara.mediatracker.data.model.MediaIds
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.ui.common.LibraryCore
import com.vara.mediatracker.ui.common.LocalNowMillis
import com.vara.mediatracker.ui.common.OverlayAwareBackHandler
import com.vara.mediatracker.ui.common.rememberHaptics
import com.vara.mediatracker.ui.home.HomeEligibility
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ln
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Release Radar, in two states.
 *
 * **Grid** is the default: the month's days, and the selected day's releases under them. It answers
 * "what is on today", which is the question a radar is asked most.
 *
 * **List** is the whole month as an agenda — every dated release, grouped by day, days with nothing
 * skipped. It answers "what does this month look like", which the grid only ever drew in heat.
 *
 * They are two states rather than one that collapses. A grid that shrinks into a week strip as you
 * scroll reads well and costs a continuous height animation over forty-two tinted cells — the
 * classic source of scroll jank — and it would have left the header with three states and two
 * meanings on one label. Two honest states and one switch is the version that can be trusted.
 *
 * The mode switch is the only control that changes mode. The month label is a label: a title that is
 * secretly a button is a button nobody presses.
 *
 * Days are shaded by how much lands on them. The weight is episodes plus films, run through log1p
 * against the busiest day of the month, so one busy Friday does not flatten every other day to
 * nothing — the Mac's scale, including its opacity bounds. In list mode the same shading appears on
 * each date's day chip, which is how the radar's shape survives without the grid.
 *
 * A release is a *resolved* date, not a stored one: the stored date is the show's first-air date,
 * years old for anything still running. Everything is placed, not only what is still to come — a
 * calendar you cannot look back through is a countdown.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun M3ERoughCalendar(
    core: LibraryCore,
    onOpen: (MediaItemEntity) -> Unit = {}
) {
    // A cold start renders the empty state before Room answers — "0 titles", "Your library is up
    // to date" — which reads as an empty library rather than a loading one.
    if (!core.loaded) {
        M3ESkeleton()
        return
    }

    // Reading the app clock is what recomposes this as it ticks, so `today` re-derives rather
    // than being fixed at first composition. The clock itself is published at minute resolution
    // (see `rememberNowMillis`), so a tick that does not cross a minute boundary invalidates
    // nothing here — which is what stops the 42 day cells rebuilding twice a minute.
    val nowMillis = LocalNowMillis.current
    val today = remember(nowMillis) { LocalDate.now() }
    var month by rememberSaveable { mutableStateOf(YearMonth.from(today).toString()) }
    var selected by rememberSaveable { mutableStateOf(today.toString()) }
    var listMode by rememberSaveable { mutableStateOf(false) }
    val haptics = rememberHaptics()

    // Back leaves the list, not the section. Without this the shell's handler would take back to
    // Home, because the mode is not part of the overlay stack.
    OverlayAwareBackHandler(enabled = listMode) { listMode = false }

    val zone = remember { ZoneId.systemDefault() }
    val monthLabel = remember { DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US) }
    val dayLabel = remember { DateTimeFormatter.ofPattern("d", Locale.US) }
    val fullLabel = remember { DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.US) }

    // Pure: no theme reads inside `remember`.
    //
    // Every dated episode of every show, and the release date of every film — aired, watched,
    // upcoming alike. Each gets its own entry, so the month is a schedule rather than a countdown
    // and checking an episode off elsewhere moves it in place instead of making the show vanish.
    // Off the main thread.
    //
    // This walks every title and every episode, and it used to run inside a `remember` during
    // composition — on the frame that opened the tab, on every DB change. The view model already
    // builds `core` on `Dispatchers.Default`; this derivation was the one piece still doing its work
    // on the main thread, and the most expensive one in the app.
    val events by produceState(initialValue = emptyList<CalendarEvent>(), core) {
        value = withContext(Dispatchers.Default) {
        buildList {
            for (item in core.items) {
                val isTv = item.type.contains("TV", ignoreCase = true)
                if (!isTv) {
                    // A film has one date: the day it was released.
                    val instant = HomeEligibility.nextAiringInstant(item, emptyList()) ?: continue
                    val zoned = instant.toInstant().atZone(zone)
                    add(
                        CalendarEvent(
                            item = item,
                            day = zoned.toLocalDate(),
                            time = zoned.toLocalTime().takeIf { it.hour != 0 || it.minute != 0 },
                            episodes = emptyList()
                        )
                    )
                    continue
                }

                // A show dropping three episodes in one day is one release, not three — which is
                // what the Mac shows, and what makes a binge-readable day. The heat still counts
                // the episodes, because that is what "how much lands here" means.
                val key = MediaIds.tvShowKey(item.id)
                val byDay = LinkedHashMap<LocalDate, MutableList<Pair<EpisodeEntity, ZonedDateTime>>>()
                for (episode in core.episodesByShow[key].orEmpty()) {
                    val instant = HomeEligibility.episodeInstant(item, episode) ?: continue
                    val zoned = instant.toInstant().atZone(zone)
                    byDay.getOrPut(zoned.toLocalDate()) { mutableListOf() }.add(episode to zoned)
                }
                for ((day, entries) in byDay) {
                    val earliest = entries.minByOrNull { it.second }?.second?.toLocalTime()
                    add(
                        CalendarEvent(
                            item = item,
                            day = day,
                            time = earliest?.takeIf { it.hour != 0 || it.minute != 0 },
                            episodes = entries.map { it.first }
                        )
                    )
                }
            }
        }
        }
    }
    val byDay: Map<LocalDate, List<CalendarEvent>> = remember(events) { events.groupBy { it.day } }

    val monthValue = remember(month) { YearMonth.parse(month) }
    // Heat: weight is episodes + films, scaled by log1p against the busiest day of the month being
    // shown — the Mac's rule, which keeps a single heavy day from flattening the rest. A grouped
    // release contributes its episode count, so grouping the rows does not change the shading.
    val weights = remember(events) {
        val byWeight = HashMap<LocalDate, Int>()
        for (event in events) {
            val weight = event.episodes.size.coerceAtLeast(1)
            byWeight[event.day] = (byWeight[event.day] ?: 0) + weight
        }
        byWeight
    }
    val maxWeight = remember(weights, monthValue) {
        var max = 0
        for (dayOfMonth in 1..monthValue.lengthOfMonth()) {
            val weight = weights[monthValue.atDay(dayOfMonth)] ?: 0
            if (weight > max) max = weight
        }
        max
    }
    val logMax = if (maxWeight > 0) ln(1.0 + maxWeight) else 0.0
    fun intensityOf(day: LocalDate): Float {
        val weight = weights[day] ?: 0
        if (weight == 0 || logMax == 0.0) return 0f
        return (ln(1.0 + weight) / logMax).toFloat()
    }

    val selectedDay = remember(selected) { LocalDate.parse(selected) }
    val cells = remember(monthValue) { monthCells(monthValue) }
    val releases = remember(byDay, selectedDay) { byDay[selectedDay].orEmpty() }

    // The month as an agenda: only the days that carry something, in order. Empty days are skipped
    // deliberately — an agenda that lists "nothing" nineteen times is the grid with extra steps.
    val agenda = remember(byDay, monthValue) {
        byDay.entries
            .filter { YearMonth.from(it.key) == monthValue }
            .sortedBy { it.key }
    }

    // The month steps aside when the day under it is long enough to be worth the room: three
    // releases still fit below a full month, past that the grid is what is in the way. It gates the
    // week strip — whether one pins — and nothing else.
    val collapsible = releases.size > 3

    fun stepMonth(delta: Long) {
        haptics.tap()
        val next = monthValue.plusMonths(delta)
        month = next.toString()
        // Carry the day across, clamped: stepping from the 31st to a 30-day month should land on
        // the 30th, not vanish.
        selected = next.atDay(selectedDay.dayOfMonth.coerceAtMost(next.lengthOfMonth())).toString()
    }

    // The month folds for a long day, and the trigger is the day rather than the scroll.
    //
    // It was the scroll, and that could not work: the gesture that triggered the change was also the
    // gesture the change disturbed. The date header scrolled away before the fold landed, and on a
    // day short enough to fit there was nothing to scroll at all, so it never fired. Neither is a
    // threshold problem — the trigger simply cannot be the thing the trigger moves.
    //
    // The escape is the strip itself: tapping it opens the month again, and the reset is keyed on
    // the day, so picking a different day folds it back.
    var monthOpened by remember(selectedDay) { mutableStateOf(false) }
    val isCompactWeek = releases.size >= 3 && !monthOpened

    // One row or five, and that is the whole of the week/month difference. A row count cannot be
    // wrong; a height or a travel distance can, and mine was, repeatedly.
    val weeks = remember(cells, isCompactWeek, selectedDay) {
        val all = cells.chunked(7)
        if (!isCompactWeek) all
        else listOf(all.firstOrNull { it.contains(selectedDay) } ?: all.first())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(M3EColor.page())
            .statusBarsPadding()
    ) {
        Column(modifier = Modifier.padding(horizontal = M3ESpacing.page)) {
            Spacer(Modifier.height(M3ESpacing.pageVertical))
            // The month sits with the title, where it is read, and the whole thing is one control:
            // step a month either way, or tap the name to open the month as a list. The caret is
            // what says the name is tappable — a title that is secretly a button is a button nobody
            // presses.
            M3EScreenHeader(
                title = "Calendar",
                eyebrow = "Release radar",
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { stepMonth(-1) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.ChevronLeft,
                                contentDescription = "Previous month",
                                tint = M3EColor.text(),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Row(
                            modifier = Modifier
                                .clip(M3EShape.chip())
                                .clickable {
                                    haptics.tap()
                                    listMode = !listMode
                                }
                                .padding(horizontal = M3ESpacing.tightGap, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = monthLabel.format(monthValue),
                                style = M3EType.rowTitleStrong(),
                                color = M3EColor.text(),
                                maxLines = 1
                            )
                            Spacer(Modifier.width(2.dp))
                            Icon(
                                imageVector = if (listMode) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = if (listMode) {
                                    "Back to the month grid"
                                } else {
                                    "See the whole month as a list"
                                },
                                tint = M3EColor.accent(),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = { stepMonth(1) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = "Next month",
                                tint = M3EColor.text(),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            )

            if (!listMode) {
                MonthGrid(
                    weeks = weeks,
                    today = today,
                    selectedDay = selectedDay,
                    dayLabel = dayLabel,
                    intensityOf = { intensityOf(it) },
                    onSelect = {
                        haptics.tap()
                        selected = it.toString()
                    },
                    // Only when folded. The strip is the month, collapsed, so the thing to tap to
                    // open it is the strip — no separate control, and no reliance on there being
                    // something to scroll.
                    onExpand = if (isCompactWeek) {
                        {
                            haptics.tap()
                            monthOpened = true
                        }
                    } else {
                        null
                    }
                )
                Spacer(Modifier.height(M3ESpacing.cardGap))
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = M3ESpacing.page),
            verticalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)
        ) {
            if (listMode) {
                if (agenda.isEmpty()) {
                    item {
                        EmptyState(
                            title = "A quiet stretch.",
                            body = "Nothing lands here. Try another week."
                        )
                    }
                } else {
                    agenda.forEach { (day, dayEvents) ->
                        stickyHeader(key = "header_$day") {
                            DateHeader(
                                day = day,
                                count = dayEvents.size,
                                intensity = intensityOf(day),
                                today = today,
                                label = fullLabel,
                                number = dayLabel
                            )
                        }
                        items(dayEvents, key = { "event_${day}_${it.item.id}" }) { event ->
                            ReleaseRow(
                                item = event.item,
                                episodes = event.episodes,
                                time = event.time,
                                onClick = { onOpen(event.item) }
                            )
                        }
                    }
                }
            } else {
                // Pinned, so the day cannot be pulled out of view while you read its releases — which
                // is exactly what happened when the fold was driven by scrolling. Opaque, because a
                // sticky header the rows show through is worse than no header.
                stickyHeader(key = "day") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(M3EColor.page())
                            .padding(vertical = M3ESpacing.tightGap)
                    ) {
                        M3ESectionHeader(
                            title = fullLabel.format(selectedDay),
                            trailing = {
                                Text(
                                    text = "${releases.size} " +
                                        if (releases.size == 1) "release" else "releases",
                                    style = M3EType.rowSupporting(),
                                    color = M3EColor.textMuted()
                                )
                            }
                        )
                    }
                }
                if (releases.isEmpty()) {
                    item {
                        EmptyState(
                            title = "Nothing lands on this day.",
                            body = "Pick another day, or switch to the month."
                        )
                    }
                } else {
                    items(
                        releases.sortedBy { it.time ?: LocalTime.MIDNIGHT },
                        key = { "release_${it.item.id}" }
                    ) { event ->
                        ReleaseRow(
                            item = event.item,
                            episodes = event.episodes,
                            time = event.time,
                            onClick = { onOpen(event.item) }
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(LocalNavClearance.current)) }
        }
    }
}

/**
 * A day's heading in the agenda: its chip, its full date, and how much lands on it.
 *
 * Opaque background because it pins — a sticky header the rows show through is worse than no header.
 */
@Composable
private fun DateHeader(
    day: LocalDate,
    count: Int,
    intensity: Float,
    today: LocalDate,
    label: DateTimeFormatter,
    number: DateTimeFormatter
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(M3EColor.page())
            .padding(vertical = M3ESpacing.tightGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DayChip(day = day, today = today, intensity = intensity, label = number)
        Spacer(Modifier.width(M3ESpacing.inlineGap))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label.format(day),
                style = M3EType.rowTitleStrong(),
                color = M3EColor.text(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "$count ${if (count == 1) "release" else "releases"}",
                style = M3EType.rowSupporting(),
                color = M3EColor.textMuted()
            )
        }
    }
}

/**
 * The grid cell, shrunk to a badge.
 *
 * This is how the radar survives without the grid: the same fill from the same weight, so a heavy
 * day still reads as heavy while the rows carry what the grid never could. No selection ring here —
 * nothing is selected in the agenda, and a ring would imply a state that has no meaning in this
 * mode.
 */
@Composable
private fun DayChip(
    day: LocalDate,
    today: LocalDate,
    intensity: Float,
    label: DateTimeFormatter
) {
    val fill = dayFill(intensity)
    val textColor = dayTextColor(intensity)
    val onDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
        Surface(
            shape = M3EShape.chip(),
            color = fill,
            contentColor = textColor,
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = label.format(day),
                    style = M3EType.rowSupporting(),
                    fontWeight = FontWeight.SemiBold,
                    color = textColor
                )
                if (day == today) {
                    Spacer(Modifier.height(2.dp))
                    Box(
                        modifier = Modifier
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(if (onDark) Color.White else Color.Black)
                    )
                }
            }
        }
    }
}

/**
 * The fill for a day, from how much lands on it.
 *
 * The Mac's opacity bounds, chosen from the palette in use rather than the system setting so a
 * forced light or dark theme still shades correctly. Shared by the cell and the chip so the two can
 * never disagree about what "busy" looks like.
 */
@Composable
private fun dayFill(intensity: Float): Color {
    if (intensity <= 0f) return MaterialTheme.colorScheme.surfaceContainerHigh
    val onDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val base = if (onDark) 0.20f else 0.16f
    val extra = if (onDark) 0.65f else 0.70f
    return M3EColor.accent().copy(alpha = base + intensity * extra)
}

/** The Mac's label rule, matched: white once the fill is heavy, otherwise on-surface held back. */
@Composable
private fun dayTextColor(intensity: Float): Color =
    if (intensity > 0.4f) Color.White.copy(alpha = 0.9f) else M3EColor.text().copy(alpha = 0.7f)

/**
 * The month, as a card. Nothing clever.
 *
 * Takes the weeks to draw rather than a month, so week mode is one row and month mode is five or six
 * — the same composable, the same cells, one list that is shorter. The card carries
 * `animateContentSize`, so when the row count changes Compose animates the height. It asks the layout
 * how big it is and moves between the answers, which is the part I kept getting wrong by computing
 * the answers myself.
 */
@Composable
private fun MonthGrid(
    weeks: List<List<LocalDate?>>,
    today: LocalDate,
    selectedDay: LocalDate,
    dayLabel: DateTimeFormatter,
    intensityOf: (LocalDate) -> Float,
    onSelect: (LocalDate) -> Unit,
    onExpand: (() -> Unit)? = null
) {
    M3ETonalCard(modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Column(
            modifier = Modifier.padding(M3ESpacing.cardGap),
            verticalArrangement = Arrangement.spacedBy(CELL_GAP)
        ) {
            WeekdayHeader()
            Spacer(Modifier.height(M3ESpacing.tightGap))
            weeks.forEachIndexed { index, week ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(CELL_GAP)
                ) {
                    week.forEach { day ->
                        DayCell(
                            day = day,
                            today = today,
                            selected = selectedDay,
                            intensity = day?.let(intensityOf) ?: 0f,
                            label = dayLabel,
                            onSelect = onSelect,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                if (index < weeks.size - 1) Spacer(Modifier.height(CELL_GAP))
            }
            if (onExpand != null) {
                Spacer(Modifier.height(M3ESpacing.tightGap))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(M3EShape.chip())
                        .clickable { onExpand() }
                        .padding(vertical = M3ESpacing.tightGap),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = M3EColor.accent(),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Show month",
                        style = M3EType.action(),
                        color = M3EColor.accentText()
                    )
                }
            }
        }
    }
}

/** The width of the selection ring. */
private val RING_WIDTH = 2.dp

/** The gap between cells. */
private val CELL_GAP = 4.dp

/**
 * One dated release: the title, the day it lands, its resolved local air time, and the episodes it
 * covers — one entry for a single episode, several when a show drops a batch that day, and none for
 * a film.
 */
private data class CalendarEvent(
    val item: MediaItemEntity,
    val day: LocalDate,
    val time: LocalTime?,
    val episodes: List<EpisodeEntity>
)

/** The month's days, Monday first, padded with blanks to whole weeks. */
private fun monthCells(month: YearMonth): List<LocalDate?> {
    val cells = mutableListOf<LocalDate?>()
    val leading = month.atDay(1).dayOfWeek.value - 1
    repeat(leading) { cells.add(null) }
    for (day in 1..month.lengthOfMonth()) cells.add(month.atDay(day))
    while (cells.size % 7 != 0) cells.add(null)
    return cells
}

@Composable
private fun WeekdayHeader() {
    val labels = listOf("M", "T", "W", "T", "F", "S", "S")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        labels.forEach { label ->
            Text(
                text = label,
                style = M3EType.rowSupporting(),
                color = M3EColor.textMuted(),
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * One day in the grid.
 *
 * Three states, matching the Mac's radar cell: a day carrying releases is tinted by how much lands
 * on it, today carries a dot beneath its number, and the selected day is ringed.
 *
 * The ring is the cell's own border, so it traces the day exactly, and it is the accent: the fill
 * underneath is the accent at varying opacity, but the border sits on the outline where the two do
 * not merge.
 */
@Composable
private fun DayCell(
    day: LocalDate?,
    today: LocalDate,
    selected: LocalDate,
    intensity: Float,
    label: DateTimeFormatter,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier
) {
    if (day == null) {
        Spacer(modifier = modifier.aspectRatio(0.9f))
        return
    }

    val isSelected = day == selected
    val isToday = day == today
    val fill = dayFill(intensity)
    val textColor = dayTextColor(intensity)
    val onDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    Box(modifier = modifier.aspectRatio(0.9f), contentAlignment = Alignment.Center) {
        Surface(
            onClick = { onSelect(day) },
            shape = M3EShape.chip(),
            color = fill,
            contentColor = textColor,
            // Drawn as the Surface's own border rather than as a separate ring around it. A ring a
            // few dp outside the cell shares the shape constant but not the shape: the same corner
            // radius over a larger box reads as a different, squarer outline. Here the border is
            // the cell's outline, so the two cannot disagree.
            border = if (isSelected) BorderStroke(RING_WIDTH, M3EColor.accent()) else null,
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = label.format(day),
                    style = M3EType.rowSupporting(),
                    fontWeight = FontWeight.SemiBold,
                    color = textColor
                )
                if (isToday) {
                    Spacer(Modifier.height(2.dp))
                    Box(
                        modifier = Modifier
                            .size(4.dp)
                            .clip(CircleShape)
                            // White on a dark palette, black on a light one — the Mac's dot, and
                            // the reason it reads on the page as well as on a filled day.
                            .background(if (onDark) Color.White else Color.Black)
                    )
                }
            }
        }
    }
}

/** One release on the selected day. */
@Composable
private fun ReleaseRow(
    item: MediaItemEntity,
    episodes: List<EpisodeEntity>,
    time: LocalTime?,
    onClick: (() -> Unit)? = null
) {
    M3ETonalCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(M3ESpacing.inlineGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = item.posterUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(44.dp)
                    .height(66.dp)
                    .clip(M3EShape.chip())
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            )
            Spacer(Modifier.width(M3ESpacing.inlineGap))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = M3EType.rowTitleStrong(),
                    color = M3EColor.text(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = releaseSupport(item.type, episodes, time),
                    style = M3EType.rowSupporting(),
                    color = M3EColor.textMuted(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * The second line of a release: the slot when the database knows it, the kind of title otherwise.
 *
 * "S2E5 · 9:00 PM" is what a radar is for — the episode and when it lands. A movie, or a show
 * whose episode row has not been enriched yet, falls back to saying what it is rather than
 * inventing a time.
 */
private fun releaseSupport(
    type: String,
    episodes: List<EpisodeEntity>,
    time: LocalTime?
): String {
    // The resolved local time, not the raw airTime field: the field is the broadcaster's clock,
    // and a title airing at 21:00 in New York is not 21:00 here.
    val clock = time?.let { DateTimeFormatter.ofPattern("h:mm a", Locale.US).format(it) }
    if (episodes.isEmpty()) return listOfNotNull(type, clock).joinToString(" · ")

    // Named individually up to three, then by season: "S2 E1, E2, E3" is a schedule, "Season 2" is
    // what you want to know once the list would stop being one — the Mac's wording.
    val season = episodes.first().seasonNumber
    val codes = if (episodes.size > 3) {
        "Season $season"
    } else {
        val numbers = episodes.sortedBy { it.episodeNumber }.joinToString(", ") { "E${it.episodeNumber}" }
        "S$season $numbers"
    }
    return if (clock == null) codes else "$codes · $clock"
}
