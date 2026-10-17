package com.vara.mediatracker.ui.m3e

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.di.AppContainer

/**
 * A rough M3E shell, for looking at the design language on a device.
 *
 * This is a sketch, not a migration: it shows one screen built only from the vocabulary in
 * [M3E] — the screen header, an emphasised banner, a metric row, tonal cards, a poster rail and
 * the floating capsule navigation — driven by the real library so it reads like the app rather
 * than a mockup. Navigation between sections, search, detail and settings are not wired.
 *
 * Flip [M3ERoughPreview] to false to get the existing UI back.
 */
const val M3ERoughPreview = true

@Composable
fun M3ERoughScreen(container: AppContainer) {
    val library by produceState(initialValue = emptyList<MediaItemEntity>()) {
        value = runCatching { container.database.mediaDao().getAllMediaList() }.getOrDefault(emptyList())
    }

    val inProgress = remember(library) {
        library.filter { it.state.equals("Active", true) || it.state.equals("Re-watching", true) }
            .sortedByDescending { it.lastInteractionDate ?: "" }
    }
    val recentlyAdded = remember(library) {
        library.sortedByDescending { it.dateAdded ?: it.lastInteractionDate ?: "" }.take(12)
    }
    val completed = remember(library) { library.count { it.state.equals("Completed", true) } }

    Box(modifier = Modifier.fillMaxSize().padding(horizontal = M3ESpacing.page)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)
        ) {
            item {
                Spacer(Modifier.height(M3ESpacing.pageVertical))
                M3EScreenHeader(
                    eyebrow = "Explore",
                    title = "MediaTracker",
                    trailing = {
                        IconButton(onClick = {}) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                )
            }

            // The one emphasised container on the screen — Material's colour tactic.
            item {
                EmphasisBanner(count = inProgress.size)
            }

            item {
                MetricRow(
                    library = library.size,
                    inProgress = inProgress.size,
                    completed = completed
                )
            }

            if (inProgress.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(M3ESpacing.tightGap))
                    M3ESectionHeader(
                        title = "Continue watching",
                        trailing = { TextButton(onClick = {}) { Text("See all") } }
                    )
                }
                items(inProgress.take(6), key = { it.id }) { item ->
                    ContinueRow(item)
                }
            }

            if (recentlyAdded.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(M3ESpacing.tightGap))
                    M3ESectionHeader(
                        title = "Recently added",
                        trailing = { TextButton(onClick = {}) { Text("See all") } }
                    )
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)) {
                        items(recentlyAdded, key = { it.id }) { item ->
                            PosterCard(item)
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(96.dp)) }
        }

        FloatingNav(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
    }
}

/** The emphasised container: the single thing on this screen meant to be noticed. */
@Composable
private fun EmphasisBanner(count: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = M3EShape.hero(),
        colors = CardDefaults.cardColors(
            containerColor = M3EColor.emphasis(),
            contentColor = M3EColor.onEmphasis()
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(M3ESpacing.cardGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null)
            Spacer(Modifier.width(M3ESpacing.inlineGap))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (count > 0) "$count titles in progress" else "Your library is up to date",
                    style = M3EType.rowTitle(),
                    fontWeight = FontWeight.SemiBold,
                    color = M3EColor.onEmphasis()
                )
                Text(
                    text = if (count > 0) "Pick up where you left off" else "Add something to watch",
                    style = M3EType.rowSupporting(),
                    color = M3EColor.onEmphasis()
                )
            }
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Text(
                    text = "Resume",
                    style = M3EType.action(),
                    color = M3EColor.text(),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}

/** Three numbers, on the page rather than in a card — Material's size tactic. */
@Composable
private fun MetricRow(library: Int, inProgress: Int, completed: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)
    ) {
        Metric("Library", library, Modifier.weight(1f))
        Metric("Watching", inProgress, Modifier.weight(1f))
        Metric("Finished", completed, Modifier.weight(1f))
    }
}

@Composable
private fun Metric(label: String, value: Int, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = "$value",
            style = M3EType.metric(),
            color = M3EColor.text()
        )
        Text(
            text = label.uppercase(),
            style = M3EType.rowSupporting(),
            color = M3EColor.textMuted()
        )
    }
}

/** One tonal card per row: poster, title, supporting line, trailing action. */
@Composable
private fun ContinueRow(item: MediaItemEntity) {
    M3ETonalCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(M3ESpacing.inlineGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Poster(item, width = 44.dp, height = 66.dp)
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
                    text = "${item.type} · ${item.state}",
                    style = M3EType.rowSupporting(),
                    color = M3EColor.textMuted(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(M3ESpacing.tightGap))
            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = M3EColor.accent())
        }
    }
}

@Composable
private fun PosterCard(item: MediaItemEntity) {
    Column(modifier = Modifier.width(120.dp)) {
        Poster(item, width = 120.dp, height = 180.dp)
        Spacer(Modifier.height(M3ESpacing.tightGap))
        Text(
            text = item.title,
            style = M3EType.rowTitle(),
            color = M3EColor.text(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = item.type,
            style = M3EType.rowSupporting(),
            color = M3EColor.textMuted(),
            maxLines = 1
        )
    }
}

@Composable
private fun Poster(item: MediaItemEntity, width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp) {
    AsyncImage(
        model = item.posterUrl,
        contentDescription = item.title,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(width = width, height = height)
            .clip(M3EShape.chip())
    )
}

/**
 * The floating capsule navigation: the headline M3E cue. The active item carries a contained
 * pill with a stroke; the rest are bare icons.
 */
@Composable
private fun FloatingNav(modifier: Modifier = Modifier) {
    var selected by remember { mutableIntStateOf(0) }
    val items: List<Triple<String, ImageVector, String>> = listOf(
        Triple("Home", Icons.Default.Home, "Home"),
        Triple("Library", Icons.Default.Bookmark, "Library"),
        Triple("Done", Icons.Default.Check, "Finished")
    )

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = M3EColor.card(),
        shadowElevation = 12.dp
    ) {
        Row(
            modifier = Modifier.padding(M3ESpacing.tightGap),
            horizontalArrangement = Arrangement.spacedBy(M3ESpacing.tightGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { index, (_, icon, label) ->
                val active = index == selected
                Surface(
                    onClick = { selected = index },
                    shape = RoundedCornerShape(50),
                    color = if (active) MaterialTheme.colorScheme.surface else M3EColor.card(),
                    border = if (active) {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    } else {
                        null
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(
                            horizontal = if (active) 16.dp else 12.dp,
                            vertical = 10.dp
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(M3ESpacing.tightGap)
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = label,
                            tint = if (active) M3EColor.text() else M3EColor.textMuted()
                        )
                        if (active) {
                            Text(
                                text = label,
                                style = M3EType.action(),
                                color = M3EColor.text()
                            )
                        }
                    }
                }
            }
        }
    }
}
