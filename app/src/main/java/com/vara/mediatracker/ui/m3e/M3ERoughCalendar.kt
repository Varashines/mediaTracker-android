package com.vara.mediatracker.ui.m3e

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
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
import com.vara.mediatracker.ui.common.LibraryCore
import com.vara.mediatracker.ui.common.LocalNowMillis
import com.vara.mediatracker.ui.home.HomeEligibility
import com.vara.mediatracker.data.model.MediaItemEntity
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ln

/**
 * Release Radar as a month, in the [M3E] vocabulary.
 *
 * A week of dates in a row tells you what is next; a month tells you the shape of it — which
 * weeks are busy, which are bare — and that is what a radar is for. Days carrying a release are
 * tinted, the selected day is filled with the emphasis tone, and the day's releases sit
 * underneath, so a tap always lands on something.
 *
 * Days are shaded by how much lands on them. The weight is episodes plus films, run through
 * log1p against the busiest day of the month, so one busy Friday does not flatten every other day
 * to nothing — the same scale the Mac's radar uses, including its opacity bounds.
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

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(M3EColor.page())
            .statusBarsPadding()
            .padding(horizontal = M3ESpacing.page),
        verticalArrangement = Arrangement.spacedBy(M3ESpacing.sectionGap)
    ) {
        item {
            Column {
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
                                    intensity = day?.let { intensityOf(it) } ?: 0f,
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
                            episodes = event.episodes,
                            time = event.time,
                            onClick = { onOpen(event.item) }
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(LocalNavClearance.current)) }
    }
}

/** The width of the selection ring. */
private val RING_WIDTH = 2.dp

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
 * One day.
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
    val hasReleases = intensity > 0f

    // The Mac's opacity bounds, chosen from the palette in use rather than the system setting so a
    // forced light or dark theme still shades correctly.
    val onDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val baseOpacity = if (onDark) 0.20f else 0.16f
    val maxExtra = if (onDark) 0.65f else 0.70f
    val fill = when {
        hasReleases -> M3EColor.accent().copy(alpha = baseOpacity + intensity * maxExtra)
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    // The Mac's label rule, matched: white on a heavy fill, otherwise on-surface held back so the
    // number sits inside the colour rather than on top of it.
    val textColor = if (intensity > 0.4f) {
        Color.White.copy(alpha = 0.9f)
    } else {
        M3EColor.text().copy(alpha = 0.7f)
    }

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
                    style = M3EType.rowTitle(),
                    fontWeight = FontWeight.SemiBold,
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
