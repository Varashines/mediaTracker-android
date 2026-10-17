package com.vara.mediatracker.ui.m3e

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.vara.mediatracker.data.model.EpisodeEntity
import com.vara.mediatracker.data.model.MediaIds
import com.vara.mediatracker.ui.common.LibraryCore
import com.vara.mediatracker.ui.common.LocalNowMillis
import com.vara.mediatracker.ui.home.HomeEligibility
import com.vara.mediatracker.data.model.MediaItemEntity
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Release Radar as a month, in the [M3E] vocabulary.
 *
 * A week of dates in a row tells you what is next; a month tells you the shape of it — which
 * weeks are busy, which are bare — and that is what a radar is for. Days carrying a release are
 * tinted, the selected day is filled with the emphasis tone, and the day's releases sit
 * underneath, so a tap always lands on something.
 *
 * A release is a *resolved* date, not a stored one. The stored date is the show's first-air date —
 * years old for anything still running — so reading it left every month empty; resolving each
 * episode instead gives the whole run its real dates and its air times.
 *
 * Everything is placed, not only what is still to come. A calendar you cannot look back through
 * is a countdown, and the question "when did this air" is asked as often as "when does it air".
 *
 * Dates are compared as day strings rather than resolved instants. Deliberately crude: the real
 * screen resolves an air time to the minute through the streaming rules the app already has, and
 * this is here to judge the layout, not the timekeeping.
 */
@Composable
fun M3ERoughCalendar(
    core: LibraryCore,
    onOpen: (MediaItemEntity) -> Unit = {}
) {
    // Reading the app clock is what recomposes this as it ticks, so `today` re-derives rather
    // than being fixed at first composition. Keyed to the minute: no need to rebuild the grid
    // sixty times a minute to notice a date change once.
    val nowMillis = LocalNowMillis.current
    val today = remember(nowMillis / 60_000) { LocalDate.now() }
    var month by rememberSaveable { mutableStateOf(YearMonth.from(today).toString()) }
    var selected by rememberSaveable { mutableStateOf(today.toString()) }

    val zone = remember { ZoneId.systemDefault() }
    val monthLabel = remember { DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US) }
    val dayLabel = remember { DateTimeFormatter.ofPattern("d", Locale.US) }
    val fullLabel = remember { DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.US) }

    // Pure: no theme reads inside `remember`.
    //
    // Every dated episode of every show, and the release date of every film — aired, watched,
    // upcoming alike. Each gets its own entry, so the month is a schedule rather than a countdown
    // and checking an episode off elsewhere moves it in place instead of making the show vanish.
    val events = remember(core) {
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
                            episode = null
                        )
                    )
                    continue
                }
                val key = MediaIds.tvShowKey(item.id)
                for (episode in core.episodesByShow[key].orEmpty()) {
                    val instant = HomeEligibility.episodeInstant(item, episode) ?: continue
                    val zoned = instant.toInstant().atZone(zone)
                    add(
                        CalendarEvent(
                            item = item,
                            day = zoned.toLocalDate(),
                            time = zoned.toLocalTime().takeIf { it.hour != 0 || it.minute != 0 },
                            episode = episode
                        )
                    )
                }
            }
        }
    }
    val byDay: Map<LocalDate, List<CalendarEvent>> = remember(events) { events.groupBy { it.day } }
    val monthValue = remember(month) { YearMonth.parse(month) }
    val selectedDay = remember(selected) { LocalDate.parse(selected) }
    val cells = remember(monthValue) { monthCells(monthValue) }
    val releases = remember(byDay, selectedDay) { byDay[selectedDay].orEmpty() }

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
                eyebrow = monthLabel.format(monthValue),
                title = "Calendar",
                trailing = {
                    Row {
                        IconButton(onClick = { month = monthValue.minusMonths(1).toString() }) {
                            Icon(Icons.Default.ChevronLeft, contentDescription = "Previous month", tint = M3EColor.text())
                        }
                        IconButton(onClick = { month = monthValue.plusMonths(1).toString() }) {
                            Icon(Icons.Default.ChevronRight, contentDescription = "Next month", tint = M3EColor.text())
                        }
                    }
                }
            )
        }

        item {
            M3ETonalCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(M3ESpacing.cardGap),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    WeekdayHeader()
                    Spacer(Modifier.height(M3ESpacing.tightGap))
                    cells.chunked(7).forEach { week ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            week.forEach { day ->
                                DayCell(
                                    day = day,
                                    today = today,
                                    selected = selectedDay,
                                    count = day?.let { byDay[it]?.size ?: 0 } ?: 0,
                                    label = dayLabel,
                                    onSelect = { selected = it.toString() },
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
                    title = fullLabel.format(selectedDay),
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
                    // Ordered by the resolved air time, so a day reads as a running order.
                    releases.sortedBy { it.time ?: LocalTime.MIDNIGHT }.forEach { event ->
                        ReleaseRow(
                            item = event.item,
                            episode = event.episode,
                            time = event.time,
                            onClick = { onOpen(event.item) }
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(112.dp)) }
    }
}

/** One dated release: the title, the day it lands, its resolved local air time, and the episode. */
private data class CalendarEvent(
    val item: MediaItemEntity,
    val day: LocalDate,
    val time: LocalTime?,
    val episode: EpisodeEntity?
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
 * One day.
 *
 * Three states, told apart by colour and fill rather than by text: a day carrying releases is
 * tinted, the selected day is filled with the emphasis tone, and today is ringed so it is findable
 * in a month that is not the current one. Today and selected are deliberately different treatments
 * — "what is today" and "where am I looking" are different questions.
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
        Spacer(modifier = modifier.aspectRatio(0.9f))
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

    Box(modifier = modifier.aspectRatio(0.9f)) {
        Surface(
            onClick = { onSelect(day) },
            shape = M3EShape.chip(),
            color = fill,
            contentColor = textColor,
            border = if (isToday && !isSelected) {
                BorderStroke(1.dp, M3EColor.accent())
            } else {
                null
            },
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
                    fontWeight = if (isSelected || isToday || hasReleases) FontWeight.SemiBold else FontWeight.Normal,
                    color = textColor
                )
            }
        }
    }
}

/** One release on the selected day. */
@Composable
private fun ReleaseRow(
    item: MediaItemEntity,
    episode: EpisodeEntity?,
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
                    style = M3EType.rowTitle(),
                    fontWeight = FontWeight.SemiBold,
                    color = M3EColor.text(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = releaseSupport(item.type, episode, time),
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
private fun releaseSupport(type: String, episode: EpisodeEntity?, time: LocalTime?): String {
    val code = episode?.let { "S${it.seasonNumber}E${it.episodeNumber}" }
    // The resolved local time, not the raw airTime field: the field is the broadcaster's clock,
    // and a title airing at 21:00 in New York is not 21:00 here.
    val clock = time?.let { DateTimeFormatter.ofPattern("h:mm a", Locale.US).format(it) }
    return when {
        code != null && clock != null -> "$code · $clock"
        code != null -> code
        clock != null -> "$type · $clock"
        else -> type
    }
}
