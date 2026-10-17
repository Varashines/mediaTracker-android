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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Surface
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.di.AppContainer

/**
 * The Hub: Smart, Discover and Collections in one place, in the [M3E] vocabulary.
 *
 * The three tabs are the app's own structure — collections belong here rather than beside it.
 * Smart counts come from the library the shell already loaded (state and taste, which the entity
 * carries) and Discover counts come from the facet columns it carries too, so nothing here needs
 * a query beyond the collections it embeds.
 *
 * The real Hub's smart categories come from `SmartCategoryMatcher`; these are the ones the data
 * obviously implies, which is enough to judge the layout. Grids are laid out by chunking into
 * rows: for six tiles, a lazy grid buys nothing and costs a second set of scope types.
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
            Row(horizontalArrangement = Arrangement.spacedBy(M3ESpacing.tightGap)) {
                val tabs = listOf("Smart", "Discover", "Collections")
                tabs.forEachIndexed { index, label ->
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

private data class SmartTile(
    val label: String,
    val count: Int,
    val icon: ImageVector,
    val tint: Color
)

@Composable
private fun SmartTab(library: List<MediaItemEntity>) {
    // The tints are read here, in a composable context: `M3EColor` reads the theme, so calling
    // it inside `remember`'s calculation is not allowed — the lambda is not composable.
    val accent = M3EColor.accent()
    val positive = M3EColor.positive()
    val warning = M3EColor.warning()
    val tiles = remember(library, accent, positive, warning) {
        listOf(
            SmartTile(
                label = "Continue watching",
                count = library.count { it.state.equals("Active", true) || it.state.equals("Re-watching", true) },
                icon = Icons.Default.PlayArrow,
                tint = accent
            ),
            SmartTile(
                label = "Loved",
                count = library.count { it.taste.equals("Love", true) },
                icon = Icons.Default.Favorite,
                tint = positive
            ),
            SmartTile(
                label = "Wishlist",
                count = library.count { it.state.equals("Wishlist", true) },
                icon = Icons.Default.Bookmark,
                tint = accent
            ),
            SmartTile(
                label = "Finished",
                count = library.count { it.state.equals("Completed", true) },
                icon = Icons.Default.Check,
                tint = positive
            ),
            SmartTile(
                label = "On hold",
                count = library.count { it.state.equals("On Hold", true) },
                icon = Icons.Default.PauseCircle,
                tint = warning
            ),
            SmartTile(
                label = "Liked",
                count = library.count { it.taste.equals("Like", true) },
                icon = Icons.Default.Star,
                tint = warning
            )
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = M3ESpacing.page),
        verticalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)) {
                tiles.chunked(2).forEach { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)
                    ) {
                        pair.forEach { tile -> SmartCard(tile, Modifier.weight(1f)) }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        item { Spacer(Modifier.height(112.dp)) }
    }
}

@Composable
private fun SmartCard(tile: SmartTile, modifier: Modifier = Modifier) {
    M3ETonalCard(modifier = modifier) {
        Column(modifier = Modifier.padding(M3ESpacing.cardGap)) {
            Icon(tile.icon, contentDescription = null, tint = tile.tint, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(M3ESpacing.rowGap))
            Text("${tile.count}", style = M3EType.metric(), color = M3EColor.text())
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

// MARK: - Discover

private data class Facet(val name: String, val count: Int)

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
        item {
            Column(verticalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)) {
                if (genres.isNotEmpty()) GenreGrid(genres)
                if (networks.isNotEmpty()) FacetGroup("Networks", networks)
                if (languages.isNotEmpty()) LanguageChips(languages)
            }
        }
        item { Spacer(Modifier.height(112.dp)) }
    }
}

@Composable
private fun FacetGroup(title: String, facets: List<Facet>) {
    if (facets.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)) {
        M3ESectionHeader(title)
        facets.forEach { facet -> FacetLine(facet) }
    }
}

/** Comma-separated facet counts, most common first. */
private fun facets(
    library: List<MediaItemEntity>,
    selector: (MediaItemEntity) -> String?
): List<Facet> {
    val counts = LinkedHashMap<String, Int>()
    for (item in library) {
        val raw = selector(item) ?: continue
        for (part in raw.split(",")) {
            val name = part.trim()
            if (name.isNotEmpty()) counts[name] = (counts[name] ?: 0) + 1
        }
    }
    return counts.entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .map { Facet(it.key, it.value) }
}

@Composable
private fun FacetLine(facet: Facet) {
    M3ETonalCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(M3ESpacing.inlineGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = facet.name,
                style = M3EType.rowTitle(),
                fontWeight = FontWeight.SemiBold,
                color = M3EColor.text(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${facet.count} titles",
                style = M3EType.rowSupporting(),
                color = M3EColor.textMuted()
            )
        }
    }
}

/**
 * Genres as a grid of tiles.
 *
 * A list made every genre look equally incidental; tiles give the counts somewhere to sit and let
 * the grid itself carry the browsing — which is the point of a facet. The count is the prominent
 * number and the genre is the label under it, the same shape the Smart tiles use.
 */
@Composable
private fun GenreGrid(facets: List<Facet>) {
    Column(verticalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)) {
        M3ESectionHeader("Genres")
        facets.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)
            ) {
                pair.forEach { facet -> GenreTile(facet, Modifier.weight(1f)) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun GenreTile(facet: Facet, modifier: Modifier = Modifier) {
    M3ETonalCard(modifier = modifier) {
        Column(modifier = Modifier.padding(M3ESpacing.cardGap)) {
            Text(
                text = "${facet.count}",
                style = M3EType.metric(),
                color = M3EColor.text()
            )
            Text(
                text = facet.name,
                style = M3EType.rowSupporting(),
                color = M3EColor.textMuted(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Languages as chips.
 *
 * Language names are one or two words, so a row of them reads faster than a list, and the counts
 * are small enough to sit inline. Codes are shown as the language they name — "en" is "English",
 * because a code is a database value, not something to put in front of a person.
 */
@Composable
private fun LanguageChips(facets: List<Facet>) {
    Column(verticalArrangement = Arrangement.spacedBy(M3ESpacing.tightGap)) {
        M3ESectionHeader("Languages")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(M3ESpacing.tightGap),
            verticalArrangement = Arrangement.spacedBy(M3ESpacing.tightGap)
        ) {
            facets.forEach { facet ->
                Surface(
                    shape = M3EShape.chip(),
                    color = M3EColor.card()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = M3ESpacing.inlineGap, vertical = M3ESpacing.tightGap),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = languageName(facet.name),
                            style = M3EType.action(),
                            color = M3EColor.text()
                        )
                        Spacer(Modifier.width(M3ESpacing.tightGap))
                        Text(
                            text = "${facet.count}",
                            style = M3EType.rowSupporting(),
                            color = M3EColor.textMuted()
                        )
                    }
                }
            }
        }
    }
}

/**
 * A language code as its English name.
 *
 * Anything Locale cannot resolve — a stored full name, an unknown tag — falls back to the raw
 * value rather than rendering "Unknown language", which is what Locale returns for a tag it does
 * not recognise.
 */
private fun languageName(code: String): String {
    val tag = code.trim()
    if (tag.isEmpty()) return tag
    val resolved = java.util.Locale.forLanguageTag(tag).getDisplayLanguage(java.util.Locale.ENGLISH)
    val unusable = resolved.isBlank() ||
        resolved.equals(tag, ignoreCase = true) ||
        resolved.contains("Unknown", ignoreCase = true)
    return if (unusable) {
        tag.replaceFirstChar { it.uppercase(java.util.Locale.US) }
    } else {
        resolved.replaceFirstChar { it.uppercase(java.util.Locale.US) }
    }
}
