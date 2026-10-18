package com.vara.mediatracker.ui.m3e

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.vara.mediatracker.data.api.TMDBClient
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.util.NetworkResolver
import com.vara.mediatracker.di.AppContainer
import com.vara.mediatracker.ui.common.LibraryCore
import kotlinx.coroutines.launch

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
internal fun M3ERoughHub(
    container: AppContainer,
    core: LibraryCore,
    onOpen: (MediaItemEntity) -> Unit = {},
    onOpenList: (String, List<MediaItemEntity>, ListSubtitle, ListLayout) -> Unit = { _, _, _, _ -> }
) {
    val library = core.items
    // The pager owns the tab, and it is saveable, which is what makes the Hub come back to the tab
    // you left — opening a list or a title replaces this section in the shell's `when`, and a plain
    // remember would return as its default.
    val pagerState = rememberPagerState(pageCount = { HubTabs.size })
    val scope = rememberCoroutineScope()

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
                HubTabs.forEachIndexed { index, label ->
                    FilterChip(
                        selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        label = { Text(label, style = M3EType.action()) },
                        shape = M3EShape.chip()
                    )
                }
            }
            Spacer(Modifier.height(M3ESpacing.cardGap))
        }

        // Swipeable as well as tappable: the three are peers, so moving between them sideways is
        // what the hand expects, and the chips stay as the labels for where you are.
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
            when (page) {
                0 -> SmartTab(library, onOpenList)
                1 -> DiscoverTab(library, core.networkLogos, core.studioLogos, onOpenList)
                else -> M3ERoughCollections(
                    container = container,
                    core = core,
                    onOpenList = onOpenList,
                    showHeader = false
                )
            }
        }
    }
}

private val HubTabs = listOf("Smart", "Discover", "Collections")

// MARK: - Smart

private data class SmartTile(
    val label: String,
    val icon: ImageVector,
    val tint: Color,
    val items: List<MediaItemEntity>
) {
    val count: Int get() = items.size
}

@Composable
private fun SmartTab(
    library: List<MediaItemEntity>,
    onOpenList: (String, List<MediaItemEntity>, ListSubtitle, ListLayout) -> Unit
) {
    // The tints are read here, in a composable context: `M3EColor` reads the theme, so calling
    // it inside `remember`'s calculation is not allowed — the lambda is not composable.
    val accent = M3EColor.accent()
    val positive = M3EColor.positive()
    val warning = M3EColor.warning()
    val tiles = remember(library, accent, positive, warning) {
        fun of(predicate: (MediaItemEntity) -> Boolean) = library.filter(predicate)
        listOf(
            SmartTile(
                label = "Continue watching",
                icon = Icons.Default.PlayArrow,
                tint = accent,
                items = of { it.state.equals("Active", true) || it.state.equals("Re-watching", true) }
            ),
            SmartTile(
                label = "Loved",
                icon = Icons.Default.Favorite,
                tint = positive,
                items = of { it.taste.equals("Love", true) }
            ),
            SmartTile(
                label = "Wishlist",
                icon = Icons.Default.Bookmark,
                tint = accent,
                items = of { it.state.equals("Wishlist", true) }
            ),
            SmartTile(
                label = "Finished",
                icon = Icons.Default.Check,
                tint = positive,
                items = of { it.state.equals("Completed", true) }
            ),
            SmartTile(
                label = "On hold",
                icon = Icons.Default.PauseCircle,
                tint = warning,
                items = of { it.state.equals("On Hold", true) }
            ),
            SmartTile(
                label = "Liked",
                icon = Icons.Default.Star,
                tint = warning,
                items = of { it.taste.equals("Like", true) }
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
                        pair.forEach { tile ->
                            SmartCard(
                                tile = tile,
                                modifier = Modifier.weight(1f),
                                onClick = { onOpenList(tile.label, tile.items, ListSubtitle.Episode, ListLayout.Grid) }
                            )
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        item { Spacer(Modifier.height(112.dp)) }
    }
}

@Composable
private fun SmartCard(
    tile: SmartTile,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    M3ETonalCard(modifier = modifier, onClick = onClick) {
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

private data class Facet(
    val name: String,
    val items: List<MediaItemEntity>
) {
    val count: Int get() = items.size
}

@Composable
private fun DiscoverTab(
    library: List<MediaItemEntity>,
    networkLogos: Map<String, String>,
    studioLogos: Map<String, String>,
    onOpenList: (String, List<MediaItemEntity>, ListSubtitle, ListLayout) -> Unit
) {
    // Networks and studios are different things — one airs a show, one makes it — so they sit
    // behind a pill pair rather than pooled into one list of "brands", which is how the Mac splits
    // them and why it has a Networks/Studios switch.
    var brand by rememberSaveable { mutableStateOf(BrandTab.Networks) }
    val brands = remember(library, brand) {
        // A network airs a show, a studio makes a film — so the two tabs read different titles, not
        // just different fields of the same ones. A show's studio and a film's network are both
        // populated by TMDB and neither belongs here.
        val source = when (brand) {
            BrandTab.Networks -> library.filter { it.type.contains("TV", ignoreCase = true) }
            BrandTab.Studios -> library.filter { it.type.contains("Movie", ignoreCase = true) }
        }
        facets(source) { if (brand == BrandTab.Networks) it.network else it.studios }.take(12)
    }
    val genres = remember(library) { facets(library) { it.genres }.take(8) }
    val languages = remember(library) { facets(library) { it.language }.take(6) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = M3ESpacing.page),
        verticalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)
    ) {
        if (brands.isEmpty() && genres.isEmpty() && languages.isEmpty()) {
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
                Row(horizontalArrangement = Arrangement.spacedBy(M3ESpacing.tightGap)) {
                    BrandTab.all.forEach { candidate ->
                        FilterChip(
                            selected = brand == candidate,
                            onClick = { brand = candidate },
                            label = { Text(candidate.label, style = M3EType.action()) },
                            shape = M3EShape.chip()
                        )
                    }
                }
                BrandGrid(
                    title = brand.label,
                    facets = brands,
                    // The network's own logo for a network, the studio's for a studio — never the
                    // provider's, which is a different asset with a background behind it.
                    logos = if (brand == BrandTab.Networks) networkLogos else studioLogos,
                    onOpenList = onOpenList
                )
                if (genres.isNotEmpty()) GenreGrid(genres, onOpenList)
                if (languages.isNotEmpty()) LanguageGrid(languages, onOpenList)
            }
        }
        item { Spacer(Modifier.height(M3ESpacing.bottomInset)) }
    }
}

/**
 * Which brand list Discover is showing.
 *
 * A network airs a title, a studio makes it, and a library holds both — so they are separated the
 * way the Mac separates them rather than merged into one list of names.
 */
private enum class BrandTab(val label: String) {
    Networks("Networks"),
    Studios("Studios");

    companion object {
        val all = listOf(Networks, Studios)
    }
}

/**
 * Brands as tiles, the shape the Mac's `DiscoveryCard` uses with `style: .logo`: a card tall enough
 * to hold the mark at size, tinted with the brand's own colour where one is known, and the count
 * underneath.
 *
 * The logos were already being fetched and cached by the enrichment pass; nothing on this screen
 * read them until now, which is most of why it looked like a list of words.
 */
@Composable
private fun BrandGrid(
    title: String,
    facets: List<Facet>,
    logos: Map<String, String>,
    onOpenList: (String, List<MediaItemEntity>, ListSubtitle, ListLayout) -> Unit
) {
    M3ESectionHeader(title)
    if (facets.isEmpty()) {
        Text(
            text = "None yet.",
            style = M3EType.rowSupporting(),
            color = M3EColor.textMuted()
        )
        return
    }
    // Three to a row rather than two: these are marks, not posters, and a phone does not need a
    // 96dp card to recognise one. Smaller is also more of them on screen, which is the point of a
    // browse grid.
    Column(verticalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)) {
        facets.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)
            ) {
                row.forEach { facet ->
                    BrandTile(
                        facet = facet,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onOpenList(facet.name, facet.items, ListSubtitle.Episode, ListLayout.Grid)
                        }
                    )
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Ink for a white brand plate. Fixed, because the plate is white in both themes. */
private val BrandPlateInk = Color(0xFF1F2933)

/** One brand: the mark on a white plate, and how many titles it holds. */
@Composable
private fun BrandTile(
    facet: Facet,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    // Text, in the theme's own colours.
    //
    // The logos were the wrong answer twice over: a mark on transparency disappears on a dark page,
    // and the white plate that fixed it fought whatever palette the user had chosen. Text takes the
    // theme's colours, so the tile is legible in both modes and belongs to the palette in use
    // rather than to a third scheme invented for it.
    M3ETonalCard(modifier = modifier.height(76.dp), onClick = onClick) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(M3ESpacing.tightGap),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = facet.name,
                style = M3EType.rowTitle(),
                fontWeight = FontWeight.SemiBold,
                color = M3EColor.text(),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.height(M3ESpacing.tightGap))
            Text(
                text = "${facet.count}",
                style = M3EType.rowSupporting(),
                color = BrandPlateInk.copy(alpha = 0.55f)
            )
        }
    }
}

/** Comma-separated facet counts, most common first. */
private fun facets(
    library: List<MediaItemEntity>,
    selector: (MediaItemEntity) -> String?
): List<Facet> {
    val buckets = LinkedHashMap<String, MutableList<MediaItemEntity>>()
    for (item in library) {
        val raw = selector(item) ?: continue
        for (part in raw.split(",")) {
            val name = part.trim()
            if (name.isNotEmpty()) buckets.getOrPut(name) { mutableListOf() }.add(item)
        }
    }
    return buckets.entries
        .sortedWith(
            compareByDescending<Map.Entry<String, MutableList<MediaItemEntity>>> { it.value.size }
                .thenBy { it.key }
        )
        .map { Facet(it.key, it.value) }
}

@Composable
private fun GenreGrid(
    facets: List<Facet>,
    onOpenList: (String, List<MediaItemEntity>, ListSubtitle, ListLayout) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)) {
        M3ESectionHeader("Genres")
        facets.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)
            ) {
                pair.forEach { facet ->
                    GenreTile(
                        facet = facet,
                        modifier = Modifier.weight(1f),
                        onClick = { onOpenList(facet.name, facet.items, ListSubtitle.Episode, ListLayout.Grid) }
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun GenreTile(
    facet: Facet,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    M3ETonalCard(modifier = modifier, onClick = onClick) {
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
/**
 * Languages as tiles rather than chips.
 *
 * The name is the whole point of this facet and a chip gives it a label's size — the endonym is
 * what a reader scans for, so it gets the tile's headline. Two to a row, because a language name is
 * a word and a word needs room.
 */
@Composable
private fun LanguageGrid(
    facets: List<Facet>,
    onOpenList: (String, List<MediaItemEntity>, ListSubtitle, ListLayout) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)) {
        M3ESectionHeader("Languages")
        facets.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)
            ) {
                pair.forEach { facet ->
                    M3ETonalCard(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onOpenList(
                                languageName(facet.name),
                                facet.items,
                                ListSubtitle.Episode,
                                ListLayout.Grid
                            )
                        }
                    ) {
                        Column(modifier = Modifier.padding(M3ESpacing.cardGap)) {
                            Text(
                                text = languageName(facet.name),
                                style = M3EType.sectionTitle(),
                                color = M3EColor.text(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${facet.count} titles",
                                style = M3EType.rowSupporting(),
                                color = M3EColor.textMuted()
                            )
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * A language code as the name that language gives itself.
 *
 * Not the English name. A language list is read by someone who speaks one of them, and a Japanese
 * reader looks for "日本語", not "Japanese" — the English name is the one they are least likely to
 * scan for. The English name is also what `Locale` returns for a tag it does not know, which is
 * treated as a failure here rather than shown.
 */
private fun languageName(code: String): String {
    val tag = code.trim()
    if (tag.isEmpty()) return tag
    val locale = java.util.Locale.forLanguageTag(tag)
    val native = locale.getDisplayLanguage(locale)
    val unusable = native.isBlank() ||
        native.equals(tag, ignoreCase = true) ||
        native.contains("Unknown", ignoreCase = true)
    // No re-casing: "Deutsch" and "Español" already arrive capitalised, and scripts without case
    // are unaffected by it either way.
    return if (unusable) tag else native
}
