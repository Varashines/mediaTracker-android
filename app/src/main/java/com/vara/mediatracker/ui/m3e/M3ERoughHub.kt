package com.vara.mediatracker.ui.m3e

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.di.AppContainer
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Hub: Smart, Discover and Collections in one place, in the [M3E] vocabulary.
 *
 * The three tabs are the app's own structure — collections belong here rather than beside it.
 * Smart counts come from the library you already have (state and taste, which the entity carries),
 * Discover counts come from the facet columns it carries too, so nothing here needs a new query
 * beyond the collections it shows.
 *
 * The real Hub's smart categories come from `SmartCategoryMatcher`; these are the obvious ones the
 * data implies, which is enough to judge the layout.
 */
@Composable
fun M3ERoughHub(container: AppContainer, library: List<MediaItemEntity>) {
    var tab by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(M3EColor.page())
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = M3ESpacing.page)
        ) {
            Spacer(Modifier.height(M3ESpacing.pageVertical))
            M3EScreenHeader(eyebrow = "Curated", title = "Hub")
            Spacer(Modifier.height(M3ESpacing.cardGap))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(M3ESpacing.tightGap),
                verticalArrangement = Arrangement.spacedBy(M3ESpacing.tightGap)
            ) {
                listOf("Smart", "Discover", "Collections").forEachIndexed { index, label ->
                    FilterChip(
                        selected = tab == index,
                        onClick = { tab = index },
                        label = { Text(label, style = M3EType.action()) },
                        shape = M3EShape.chip()
                    )
                }
            }
            Spacer(Modifier.height(M3ESpacing.cardGap))
        }

        when (tab) {
            0 -> SmartTab(library)
            1 -> DiscoverTab(library)
            else -> M3ERoughCollections(container = container, library = library, showHeader = false)
        }
    }
}

// MARK: - Smart

private data class SmartTile(val label: String, val count: Int, val icon: ImageVector, val tint: Color)

@Composable
private fun SmartTab(library: List<MediaItemEntity>) {
    val tiles = remember(library) {
        listOf(
            SmartTile(
                "Continue watching",
                library.count { it.state.equals("Active", true) || it.state.equals("Re-watching", true) },
                Icons.Default.PlayArrow,
                M3EColor.accent()
            ),
            SmartTile("Loved", library.count { it.taste.equals("Love", true) }, Icons.Default.Favorite, M3EColor.positive()),
            SmartTile("Wishlist", library.count { it.state.equals("Wishlist", true) }, Icons.Default.Bookmark, M3EColor.accent()),
            SmartTile("Finished", library.count { it.state.equals("Completed", true) }, Icons.Default.Check, M3EColor.positive()),
            SmartTile("On hold", library.count { it.state.equals("On Hold", true) }, Icons.Default.PauseCircle, M3EColor.warning()),
            SmartTile("Top rated", library.count { it.taste.equals("Like", true) }, Icons.Default.Star, M3EColor.warning())
        )
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = M3ESpacing.page),
        horizontalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap),
        verticalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)
    ) {
        items(tiles, key = { it.label }) { tile ->
            M3ETonalCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(M3ESpacing.cardGap)) {
                    Icon(tile.icon, contentDescription = null, tint = tile.tint, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.height(M3ESpacing.rowGap))
                    Text(
                        text = "${tile.count}",
                        style = M3EType.metric(),
                        color = M3EColor.text()
                    )
                    Text(
                        text = tile.label,
                        style = M3EType.rowSupporting(),
                        color = M3EColor.textMuted(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(112.dp)) }
    }
}

// MARK: - Discover

@Composable
private fun DiscoverTab(library: List<MediaItemEntity>) {
    val genres = remember(library) { facets(library) { it.genres }.take(8) }
    val networks = remember(library) { facets(library) { it.network }.take(6) }
    val languages = remember(library) { facets(library) { it.language }.take(6) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = M3ESpacing.page),
        verticalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)
    ) {
        if (genres.isEmpty() && networks.isEmpty() && languages.isEmpty()) {
            item {
                M3ETonalCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Nothing to explore yet — enrich some titles first.",
                        style = M3EType.rowSupporting(),
                        color = M3EColor.textMuted(),
                        modifier = Modifier.padding(M3ESpacing.cardGap)
                    )
                }
            }
        }
        if (genres.isNotEmpty()) {
            item { M3ESectionHeader("Genres") }
            items(genres) { (name, count) -> FacetLine(name, count, "titles") }
        }
        if (networks.isNotEmpty()) {
            item {
                Spacer(Modifier.height(M3ESpacing.tightGap))
                M3ESectionHeader("Networks")
            }
            items(networks) { (name, count) -> FacetLine(name, count, "titles") }
        }
        if (languages.isNotEmpty()) {
            item {
                Spacer(Modifier.height(M3ESpacing.tightGap))
                M3ESectionHeader("Languages")
            }
            items(languages) { (name, count) -> FacetLine(name, count, "titles") }
        }
        item { Spacer(Modifier.height(112.dp)) }
    }
}

/** Comma-separated facet counts, most common first. */
private fun facets(
    library: List<MediaItemEntity>,
    selector: (MediaItemEntity) -> String?
): List<Pair<String, Int>> =
    library.asSequence()
        .mapNotNull { selector(it) }
        .flatMap { it.split(",").asSequence() }
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .map { it.key to it.value }

@Composable
private fun FacetLine(name: String, count: Int, unit: String) {
    M3ETonalCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(M3ESpacing.inlineGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = name,
                style = M3EType.rowTitle(),
                fontWeight = FontWeight.SemiBold,
                color = M3EColor.text(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "$count $unit",
                style = M3EType.rowSupporting(),
                color = M3EColor.textMuted()
            )
        }
    }
}

// MARK: - Calendar

/**
 * Release Radar, roughly: a strip of the next seven days and what lands on the selected one.
 *
 * Dates are compared as day strings rather than parsed instants, which is crude but honest for a
 * sketch — the real screen resolves air times to the minute through the streaming rules the app
 * already has.
 */
@Composable
fun M3ERoughCalendar(library: List<MediaItemEntity>) {
    val dayFormat = remember { DateTimeFormatter.ofPattern("EEE d", Locale.US) }
    val today = remember { LocalDate.now() }
    val days = remember { (0..6).map { today.plusDays(it.toLong()) } }
    var selected by remember { mutableIntStateOf(0) }

    val dated = remember(library) {
        library.mapNotNull { item ->
            val date = item.nextAiringInstant?.take(10) ?: item.releaseDate?.take(10)
            if (date.isNullOrBlank()) null else date to item
        }.sortedBy { it.first }
    }
    val selectedDate = days[selected].toString()
    val onSelected = dated.filter { it.first == selectedDate }.map { it.second }
    val upcoming = dated.filter { it.first >= today.toString() && it.first != selectedDate }
        .distinctBy { it.second.id }
        .take(8)
        .map { it.second }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = M3ESpacing.page),
        verticalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)
    ) {
        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(M3ESpacing.tightGap),
                verticalArrangement = Arrangement.spacedBy(M3ESpacing.tightGap)
            ) {
                days.forEachIndexed { index, day ->
                    val count = dated.count { it.first == day.toString() }
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

        item { M3ESectionHeader(days[selected].format(dayFormat)) }
        if (onSelected.isEmpty()) {
            item {
                M3ETonalCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Nothing lands on this day.",
                        style = M3EType.rowSupporting(),
                        color = M3EColor.textMuted(),
                        modifier = Modifier.padding(M3ESpacing.cardGap)
                    )
                }
            }
        }
        items(onSelected, key = { it.id }) { item -> CalendarRow(item, selectedDate) }

        if (upcoming.isNotEmpty()) {
            item {
                Spacer(Modifier.height(M3ESpacing.tightGap))
                M3ESectionHeader("Coming up")
            }
            items(upcoming, key = { "up-${it.id}" }) { item ->
                CalendarRow(item, item.nextAiringInstant?.take(10) ?: item.releaseDate?.take(10) ?: "")
            }
        }
        item { Spacer(Modifier.height(112.dp)) }
    }
}

@Composable
private fun CalendarRow(item: MediaItemEntity, date: String) {
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
                    text = "${item.type} · $date",
                    style = M3EType.rowSupporting(),
                    color = M3EColor.textMuted(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
