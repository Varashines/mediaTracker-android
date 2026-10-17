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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vara.mediatracker.data.model.MediaItemEntity
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Release Radar, roughly: a strip of the next seven days, the selected day's releases, and what is
 * coming after that — so an empty day still leads somewhere.
 *
 * Dates are compared as day strings rather than resolved instants. That is crude and deliberate
 * for a sketch: the real screen resolves an air time to the minute through the streaming rules the
 * app already has, and this is here to judge the layout, not the timekeeping.
 */
@Composable
fun M3ERoughCalendar(library: List<MediaItemEntity>) {
    val today = remember { LocalDate.now() }
    val dayFormat = remember { DateTimeFormatter.ofPattern("EEE d", Locale.US) }
    val days = remember(today) { (0..6).map { today.plusDays(it.toLong()) } }
    var selected by remember { mutableIntStateOf(0) }

    // Pure: no theme reads inside `remember`.
    val releases = remember(library) {
        val rows = mutableListOf<Release>()
        for (item in library) {
            val date = item.nextAiringInstant?.take(10) ?: item.releaseDate?.take(10)
            if (!date.isNullOrBlank()) rows.add(Release(date, item))
        }
        rows.sortedBy { it.date }
    }

    val selectedDate = days[selected].toString()
    val onSelected = remember(releases, selectedDate) { releases.filter { it.date == selectedDate } }
    val upcoming = remember(releases, today, selectedDate) {
        releases
            .filter { it.date >= today.toString() && it.date != selectedDate }
            .distinctBy { it.item.id }
            .take(8)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(M3EColor.page())
            .statusBarsPadding()
            .padding(horizontal = M3ESpacing.page),
        verticalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)
    ) {
        item {
            Spacer(Modifier.height(M3ESpacing.pageVertical))
            M3EScreenHeader(eyebrow = "Radar", title = "Calendar")
            Spacer(Modifier.height(M3ESpacing.cardGap))
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(M3ESpacing.tightGap)) {
                days.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(M3ESpacing.tightGap)) {
                        row.forEach { day ->
                            val index = days.indexOf(day)
                            val count = releases.count { it.date == day.toString() }
                            FilterChip(
                                selected = selected == index,
                                onClick = { selected = index },
                                label = {
                                    Text(
                                        text = if (count > 0) "${day.format(dayFormat)} · $count" else day.format(dayFormat),
                                        style = M3EType.action()
                                    )
                                },
                                shape = M3EShape.chip()
                            )
                        }
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)) {
                M3ESectionHeader(days[selected].format(dayFormat))
                if (onSelected.isEmpty()) {
                    M3ETonalCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Nothing lands on this day.",
                            style = M3EType.rowSupporting(),
                            color = M3EColor.textMuted(),
                            modifier = Modifier.padding(M3ESpacing.cardGap)
                        )
                    }
                } else {
                    onSelected.forEach { release -> ReleaseRow(release) }
                }
            }
        }

        if (upcoming.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)) {
                    M3ESectionHeader("Coming up")
                    upcoming.forEach { release -> ReleaseRow(release) }
                }
            }
        }

        item { Spacer(Modifier.height(112.dp)) }
    }
}

/** A release date and the title it belongs to. */
private data class Release(val date: String, val item: MediaItemEntity)

@Composable
private fun ReleaseRow(release: Release) {
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
                    text = release.item.title,
                    style = M3EType.rowTitle(),
                    fontWeight = FontWeight.SemiBold,
                    color = M3EColor.text(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${release.item.type} · ${release.date}",
                    style = M3EType.rowSupporting(),
                    color = M3EColor.textMuted(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
