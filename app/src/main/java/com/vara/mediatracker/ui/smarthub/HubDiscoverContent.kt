package com.vara.mediatracker.ui.smarthub

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vara.mediatracker.data.badge.SmartBadge
import com.vara.mediatracker.data.model.EpisodeEntity
import com.vara.mediatracker.data.model.MediaBadgeEntity
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.ui.library.LibraryFacet
import com.vara.mediatracker.ui.library.LibraryQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** The watch-activity summary shown at the top of the Discover tab. */
data class WatchStats(
    val streakDays: Int = 0,
    val watchedThisMonth: Int = 0,
    val activeShows: Int = 0,
    val completed: Int = 0
)

/**
 * The Hub's second tab: watch stats, badges, genres and languages.
 *
 * The Smart tab answers "what should I watch now?"; this one is the browse/stats half —
 * the same facets the Library filters by, plus the streak and activity numbers that used
 * to sit on top of the Library.
 */
@Composable
fun HubDiscoverContent(
    items: List<MediaItemEntity>,
    allEpisodes: List<EpisodeEntity>,
    badgeRows: List<MediaBadgeEntity>,
    onSelectNode: (title: String, filtered: List<MediaItemEntity>) -> Unit,
    modifier: Modifier = Modifier
) {
    val badgeCounts = remember(badgeRows) {
        badgeRows.groupingBy { it.badgeLabel }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
    }
    val badgeLabels = remember(badgeRows) { badgeRows.associate { it.mediaId to it.badgeLabel } }
    val genres = remember(items) { LibraryQuery.facetValues(items, LibraryFacet.GENRE) }
    val languages = remember(items) { LibraryQuery.facetValues(items, LibraryFacet.LANGUAGE) }

    val stats by produceState(initialValue = WatchStats(), allEpisodes, items) {
        value = withContext(Dispatchers.Default) { computeWatchStats(allEpisodes, items) }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item(key = "stats") { WatchStatsCard(stats) }

        if (badgeCounts.isNotEmpty()) {
            item(key = "badges") {
                FacetSection(
                    title = "Badges",
                    icon = Icons.Default.AutoAwesome,
                    subtitle = "Your library, lately",
                    nodes = badgeCounts.map { DiscoveryTile(it.key, it.value) }
                ) { node ->
                    onSelectNode(node.name, items.filter { badgeLabels[it.id] == node.name })
                }
            }
        }

        if (genres.isNotEmpty()) {
            item(key = "genres") {
                FacetSection(
                    title = "Genres",
                    icon = Icons.Default.Movie,
                    subtitle = "${genres.size} represented",
                    nodes = genres.take(24).map { DiscoveryTile(it, countGenres(items, it)) }
                ) { node ->
                    onSelectNode(node.name, items.filter { matchesGenre(it, node.name) })
                }
            }
        }

        if (languages.isNotEmpty()) {
            item(key = "languages") {
                FacetSection(
                    title = "Languages",
                    icon = Icons.Default.Language,
                    subtitle = "${languages.size} represented",
                    nodes = languages.take(12).map { DiscoveryTile(LibraryQuery.languageLabel(it), countLanguages(items, it), code = it) }
                ) { node ->
                    onSelectNode(node.name, items.filter { it.language.equals(node.code, ignoreCase = true) })
                }
            }
        }
    }
}

/** A tile value with its count; [code] is the raw value when the label is prettified. */
data class DiscoveryTile(val name: String, val count: Int, val code: String? = null)

private fun matchesGenre(item: MediaItemEntity, genre: String): Boolean =
    item.genres?.split(',')?.any { it.trim().equals(genre, ignoreCase = true) } == true

private fun countGenres(items: List<MediaItemEntity>, genre: String): Int =
    items.count { matchesGenre(it, genre) }

private fun countLanguages(items: List<MediaItemEntity>, code: String): Int =
    items.count { it.language.equals(code, ignoreCase = true) }

private fun computeWatchStats(episodes: List<EpisodeEntity>, items: List<MediaItemEntity>): WatchStats {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val monthPrefix = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())

    val watchedDates = mutableSetOf<String>()
    var thisMonth = 0
    for (ep in episodes) {
        if (!ep.isWatched) continue
        val datePart = ep.watchedDate?.take(10) ?: ep.airDate?.take(10)
        if (!datePart.isNullOrBlank()) {
            watchedDates.add(datePart)
            if (datePart.startsWith(monthPrefix)) thisMonth++
        }
    }
    for (item in items) {
        if (item.state.equals("Completed", ignoreCase = true)) {
            val datePart = item.lastStateChangeDate?.take(10) ?: item.lastInteractionDate?.take(10)
            if (!datePart.isNullOrBlank()) {
                watchedDates.add(datePart)
                if (datePart.startsWith(monthPrefix)) thisMonth++
            }
        }
    }

    var streak = 0
    val checkCal = Calendar.getInstance()
    var checkStr = dateFormat.format(checkCal.time)
    if (!watchedDates.contains(checkStr)) {
        checkCal.add(Calendar.DAY_OF_YEAR, -1)
        checkStr = dateFormat.format(checkCal.time)
    }
    while (watchedDates.contains(checkStr)) {
        streak++
        checkCal.add(Calendar.DAY_OF_YEAR, -1)
        checkStr = dateFormat.format(checkCal.time)
    }

    return WatchStats(
        streakDays = streak,
        watchedThisMonth = thisMonth,
        activeShows = items.count { it.state.equals("Watching", true) || it.state.equals("Active", true) },
        completed = items.count { it.state.equals("Completed", true) }
    )
}

@Composable
private fun WatchStatsCard(stats: WatchStats) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (stats.streakDays > 0) "🔥" else "✨",
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "WATCH STATS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.0.sp
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = when {
                    stats.streakDays >= 7 -> "Unstoppable momentum!"
                    stats.streakDays >= 3 -> "You're in the zone!"
                    stats.streakDays >= 1 -> "Great start! Keep going!"
                    else -> "Pick something to watch!"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                StatCell(stats.streakDays, "day streak")
                StatCell(stats.watchedThisMonth, "this month")
                StatCell(stats.activeShows, "active")
                StatCell(stats.completed, "completed")
            }
        }
    }
}

@Composable
private fun StatCell(value: Int, label: String) {
    Column(modifier = Modifier.width(84.dp)) {
        Text(
            text = "$value",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FacetSection(
    title: String,
    icon: ImageVector,
    subtitle: String,
    nodes: List<DiscoveryTile>,
    onNodeClick: (DiscoveryTile) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                modifier = Modifier.size(30.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        nodes.chunked(2).forEach { rowNodes ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                rowNodes.forEach { node ->
                    FacetTile(node = node, onClick = { onNodeClick(node) }, modifier = Modifier.weight(1f))
                }
                if (rowNodes.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FacetTile(node: DiscoveryTile, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val badge = SmartBadge.fromLabel(node.name)
    val markerColor: Color = badge?.backgroundColor ?: MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    val markerTextColor: Color = badge?.textColor ?: MaterialTheme.colorScheme.primary
    val markerLabel = node.name.take(2).uppercase(Locale.US)

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = RoundedCornerShape(9.dp), color = markerColor, modifier = Modifier.size(32.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = markerLabel,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        color = markerTextColor
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = node.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${node.count} ${if (node.count == 1) "title" else "titles"}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
