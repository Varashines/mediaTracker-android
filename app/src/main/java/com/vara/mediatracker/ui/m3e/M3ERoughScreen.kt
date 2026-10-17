package com.vara.mediatracker.ui.m3e

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
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
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.di.AppContainer

/**
 * A rough M3E shell, for looking at the design language on a device.
 *
 * Three content sections — Home, Library and Finished — switched by the floating capsule
 * navigation, with Search and Settings as overlay screens opened from the header, and the detail
 * as an overlay from any title. Everything is built only from the vocabulary in [M3E] and driven
 * by the real library. Actions are inert on purpose: this is the language, not the wiring.
 *
 * Flip [M3ERoughPreview] to false to get the existing UI back.
 */
const val M3ERoughPreview = true

@Composable
fun M3ERoughScreen(container: AppContainer) {
    val library by produceState(initialValue = emptyList<MediaItemEntity>()) {
        value = runCatching { container.database.mediaDao().getAllMediaList() }.getOrDefault(emptyList())
    }
    var section by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<MediaItemEntity?>(null) }
    var searching by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(M3EColor.page())
    ) {
        val opened = open
        when {
            opened != null -> M3ERoughDetail(item = opened, onBack = { open = null })
            searching -> M3ERoughSearch(library = library, onBack = { searching = false })
            settings -> M3ERoughSettings(onBack = { settings = false })
            else -> when (section) {
                0 -> HomeSection(
                    library = library,
                    onOpen = { open = it },
                    onSearch = { searching = true },
                    onSettings = { settings = true }
                )
                1 -> LibrarySection(library = library, onOpen = { open = it })
                else -> FinishedSection(library = library)
            }
        }

        FloatingNav(
            selected = section,
            onSelect = { section = it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp)
        )
    }
}

// MARK: - Sections

@Composable
private fun HomeSection(
    library: List<MediaItemEntity>,
    onOpen: (MediaItemEntity) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit
) {
    val inProgress = remember(library) {
        library.filter { it.state.equals("Active", true) || it.state.equals("Re-watching", true) }
            .sortedByDescending { it.lastInteractionDate ?: "" }
    }
    val recentlyAdded = remember(library) {
        library.sortedByDescending { it.dateAdded ?: it.lastInteractionDate ?: "" }.take(12)
    }
    val completed = remember(library) { library.count { it.state.equals("Completed", true) } }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = M3ESpacing.page),
        verticalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)
    ) {
        item {
            Spacer(Modifier.height(M3ESpacing.pageVertical))
            M3EScreenHeader(
                eyebrow = "Explore",
                title = "MediaTracker",
                trailing = {
                    Row {
                        IconButton(onClick = onSearch) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                        IconButton(onClick = onSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                }
            )
        }
        item { EmphasisBanner(count = inProgress.size) }
        item { MetricRow(library.size, inProgress.size, completed) }

        if (inProgress.isNotEmpty()) {
            item {
                Spacer(Modifier.height(M3ESpacing.tightGap))
                M3ESectionHeader("Continue watching", trailing = { TextButton(onClick = {}) { Text("See all") } })
            }
            items(inProgress.take(6), key = { it.id }) { item ->
                TitleRow(item, trailing = Icons.Default.PlayArrow, onClick = { onOpen(item) })
            }
        }
        if (recentlyAdded.isNotEmpty()) {
            item {
                Spacer(Modifier.height(M3ESpacing.tightGap))
                M3ESectionHeader("Recently added", trailing = { TextButton(onClick = {}) { Text("See all") } })
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)) {
                    items(recentlyAdded, key = { it.id }) { item ->
                        PosterCard(item, width = 120.dp, onClick = { onOpen(item) })
                    }
                }
            }
        }
        item { Spacer(Modifier.height(112.dp)) }
    }
}

@Composable
private fun LibrarySection(library: List<MediaItemEntity>, onOpen: (MediaItemEntity) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = M3ESpacing.page),
        horizontalArrangement = Arrangement.spacedBy(M3ESpacing.inlineGap),
        verticalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Spacer(Modifier.height(M3ESpacing.pageVertical))
            M3EScreenHeader(eyebrow = "${library.size} titles", title = "Library")
            Spacer(Modifier.height(M3ESpacing.cardGap))
        }
        items(library, key = { it.id }) { item ->
            Column(modifier = Modifier.clickable { onOpen(item) }) {
                Poster(item, modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                Spacer(Modifier.height(M3ESpacing.tightGap))
                Text(
                    text = item.title,
                    style = M3EType.rowSupporting(),
                    color = M3EColor.text(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Spacer(Modifier.height(112.dp))
        }
    }
}

@Composable
private fun FinishedSection(library: List<MediaItemEntity>) {
    val finished = remember(library) {
        library.filter { it.state.equals("Completed", true) }
            .sortedByDescending { it.lastStateChangeDate ?: it.lastInteractionDate ?: "" }
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = M3ESpacing.page),
        verticalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)
    ) {
        item {
            Spacer(Modifier.height(M3ESpacing.pageVertical))
            M3EScreenHeader(eyebrow = "${finished.size} watched", title = "Finished")
        }
        if (finished.isEmpty()) {
            item {
                M3ETonalCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Nothing finished yet.",
                        style = M3EType.rowSupporting(),
                        color = M3EColor.textMuted(),
                        modifier = Modifier.padding(M3ESpacing.cardGap)
                    )
                }
            }
        }
        items(finished, key = { it.id }) { item ->
            TitleRow(item, trailing = Icons.Default.Check)
        }
        item { Spacer(Modifier.height(112.dp)) }
    }
}

// MARK: - Building blocks

/** The emphasised container: the single thing on a screen meant to be noticed. */
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
        Text("$value", style = M3EType.metric(), color = M3EColor.text())
        Text(label.uppercase(), style = M3EType.rowSupporting(), color = M3EColor.textMuted())
    }
}

/** One tonal card per row: poster, title, supporting line, trailing action. */
@Composable
private fun TitleRow(item: MediaItemEntity, trailing: ImageVector, onClick: (() -> Unit)? = null) {
    M3ETonalCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(M3ESpacing.inlineGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Poster(item, modifier = Modifier.width(44.dp).height(66.dp))
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
            Icon(trailing, contentDescription = null, tint = M3EColor.accent())
        }
    }
}

@Composable
private fun PosterCard(item: MediaItemEntity, width: Dp, onClick: (() -> Unit)? = null) {
    Column(
        modifier = Modifier
            .width(width)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Poster(item, modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f))
        Spacer(Modifier.height(M3ESpacing.tightGap))
        Text(
            text = item.title,
            style = M3EType.rowSupporting(),
            color = M3EColor.text(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * A poster tile.
 *
 * The tonal plate is painted underneath on purpose: while an image loads — or when a title has
 * none — the tile keeps the card tone instead of showing the page through as a black hole.
 */
@Composable
private fun Poster(item: MediaItemEntity, modifier: Modifier = Modifier) {
    AsyncImage(
        model = item.posterUrl,
        contentDescription = item.title,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .clip(M3EShape.chip())
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    )
}

/**
 * The floating capsule navigation: the headline M3E cue. The active item carries a contained
 * pill with a stroke; the rest are bare icons.
 */
@Composable
private fun FloatingNav(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val items: List<Pair<String, ImageVector>> = listOf(
        "Home" to Icons.Default.Home,
        "Library" to Icons.Default.VideoLibrary,
        "Finished" to Icons.Default.Check
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
            items.forEachIndexed { index, (label, icon) ->
                val active = index == selected
                Surface(
                    onClick = { onSelect(index) },
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
                            Text(label, style = M3EType.action(), color = M3EColor.text())
                        }
                    }
                }
            }
        }
    }
}
