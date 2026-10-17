package com.vara.mediatracker.ui.m3e

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vara.mediatracker.data.model.MediaItemEntity
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Release Radar as a month, in the [M3E] vocabulary.
 *
 * A week of dates in a row tells you what is next; a month tells you the shape of it — which
 * weeks are busy, which are bare — and that is what a radar is for. Days carrying a release are
 * tinted and show how many, the selected day is filled with the emphasis tone, and the day's
 * releases sit underneath, so a tap always lands on something.
 *
 * Dates are compared as day strings rather than resolved instants. Deliberately crude: the real
 * screen resolves an air time to the minute through the streaming rules the app already has, and
 * this is here to judge the layout, not the timekeeping.
 */
@Composable
fun M3ERoughCalendar(library: List<MediaItemEntity>) {
    val today = remember { LocalDate.now() }
    var month by remember { mutableStateOf(YearMonth.from(today)) }
    var selected by remember { mutableStateOf(today) }

    val monthLabel = remember { DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US) }
    val dayLabel = remember { DateTimeFormatter.ofPattern("d", Locale.US) }
    val fullLabel = remember { DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.US) }

    // Pure: no theme reads inside `remember`.
    val byDay = remember(library) {
        val map = HashMap<String, MutableList<MediaItemEntity>>()
        for (item in library) {
            val date = item.nextAiringInstant?.take(10) ?: item.releaseDate?.take(10) ?: continue
            map.getOrPut(date) { mutableListOf() }.add(item)
        }
        map
    }
    val cells = remember(month) { monthCells(month) }
    val releases = remember(byDay, selected) { byDay[selected.toString()].orEmpty() }

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
            M3EScreenHeader(
                eyebrow = monthLabel.format(month),
                title = "Calendar",
                trailing = {
                    Row {
                        IconButton(onClick = { month = month.minusMonths(1) }) {
                            Icon(Icons.Default.ChevronLeft, contentDescription = "Previous month")
                        }
                        IconButton(onClick = { month = month.plusMonths(1) }) {
                            Icon(Icons.Default.ChevronRight, contentDescription = "Next month")
                        }
                    }
                }
            )
        }

        item {
            M3ETonalCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(M3ESpacing.inlineGap)) {
                    WeekdayHeader()
                    Spacer(Modifier.height(M3ESpacing.tightGap))
                    cells.chunked(7).forEach { week ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            week.forEach { day ->
                                DayCell(
                                    day = day,
                                    today = today,
                                    selected = selected,
                                    count = day?.let { byDay[it.toString()]?.size ?: 0 } ?: 0,
                                    label = dayLabel,
                                    onSelect = { selected = it },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)) {
                M3ESectionHeader(
                    title = fullLabel.format(selected),
                    trailing = {
                        Text(
                            text = "${releases.size} ${if (releases.size == 1) "release" else "releases"}",
                            style = M3EType.rowSupporting(),
                            color = M3EColor.textMuted()
                        )
                    }
                )
                if (releases.isEmpty()) {
                    M3ETonalCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Nothing lands on this day.",
                            style = M3EType.rowSupporting(),
                            color = M3EColor.textMuted(),
                            modifier = Modifier.padding(M3ESpacing.cardGap)
                        )
                    }
                } else {
                    releases.forEach { item -> ReleaseRow(item) }
                }
            }
        }

        item { Spacer(Modifier.height(112.dp)) }
    }
}

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
    Row(modifier = Modifier.fillMaxWidth()) {
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
 * One day.
 *
 * Three states, told apart by colour and fill rather than by text: a day carrying releases is
 * tinted and counts them, the selected day is filled with the emphasis tone, and today keeps a
 * hairline so "today" is findable in a month that is not the current one.
 */
@Composable
private fun DayCell(
    day: LocalDate?,
    today: LocalDate,
    selected: LocalDate,
    count: Int,
    label: DateTimeFormatter,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier
) {
    if (day == null) {
        Spacer(modifier = modifier.aspectRatio(1f))
        return
    }

    val isSelected = day == selected
    val isToday = day == today
    val hasReleases = count > 0

    val fill = when {
        isSelected -> MaterialTheme.colorScheme.primary
        hasReleases -> M3EColor.accent().copy(alpha = 0.18f)
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val textColor = when {
        isSelected -> MaterialTheme.colorScheme.onPrimary
        hasReleases -> M3EColor.accent()
        else -> M3EColor.textMuted()
    }

    Box(modifier = modifier.aspectRatio(1f).padding(2.dp)) {
        Surface(
            onClick = { onSelect(day) },
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
                    fontWeight = if (isSelected || hasReleases) FontWeight.SemiBold else FontWeight.Normal,
                    color = textColor
                )
                if (hasReleases) {
                    Text(
                        text = "$count",
                        style = M3EType.rowSupporting(),
                        color = if (isSelected) textColor else M3EColor.textMuted()
                    )
                }
                if (isToday && !hasReleases && !isSelected) {
                    Box(
                        modifier = Modifier
                            .width(6.dp)
                            .height(2.dp)
                            .background(M3EColor.accent())
                    )
                }
            }
        }
    }
}

/** One release on the selected day. */
@Composable
private fun ReleaseRow(item: MediaItemEntity) {
    M3ETonalCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(M3ESpacing.inlineGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = null,
                tint = M3EColor.accent(),
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(M3ESpacing.inlineGap))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = M3EType.rowTitle(),
                    fontWeight = FontWeight.SemiBold,
                    color = M3EColor.text(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.type,
                    style = M3EType.rowSupporting(),
                    color = M3EColor.textMuted(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
