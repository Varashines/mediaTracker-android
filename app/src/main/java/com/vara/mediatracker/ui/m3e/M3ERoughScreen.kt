package com.vara.mediatracker.ui.m3e

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
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
import com.vara.mediatracker.data.model.EpisodeEntity
import com.vara.mediatracker.data.model.MediaIds
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.ui.common.HomeData
import com.vara.mediatracker.ui.common.LibraryCore
import com.vara.mediatracker.ui.common.LibraryViewModel
import com.vara.mediatracker.ui.home.HomeEligibility
import com.vara.mediatracker.ui.library.LibraryFilters
import com.vara.mediatracker.ui.library.LibraryQuery
import com.vara.mediatracker.ui.common.rememberHaptics
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

/** Home, Hub, Calendar, Library, Finished — the five the nav draws. */
private const val SECTION_COUNT = 5

@Composable
fun M3ERoughScreen(
    container: AppContainer,
    onThemePresetChanged: (Int) -> Unit = {},
    onThemeModeChanged: (Int) -> Unit = {}
) {
    // The same activity-scoped holder the shipping shell collects: the library joined once per DB
    // change off the UI thread, plus Home's own derivations. Querying Room here instead would mean
    // a second build of the same data, and — because it was a one-shot read — a shell that never
    // noticed anything the app wrote.
    val libraryVm: LibraryViewModel = viewModel(factory = LibraryViewModel.factory(container))
    val core by libraryVm.core.collectAsState()
    val home by libraryVm.home.collectAsState()
    val library = core.items
    // The pager owns which section is showing, so the nav and a sideways swipe are the same thing
    // rather than two states that have to be kept in step. Saveable, which is what keeps the
    // earlier fix — leaving for a title and coming back lands where you were, not on Home.
    val pagerState = rememberPagerState(pageCount = { SECTION_COUNT })
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf<MediaItemEntity?>(null) }
    var list by remember { mutableStateOf<SectionList?>(null) }
    var searching by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    val haptics = rememberHaptics()

    // System back closes the topmost overlay before it leaves the app. The real shell routes this
    // through its overlay dispatcher; the sketch does the same job directly, and the habit of
    // handling it is what matters when this replaces the real one.
    BackHandler(enabled = open != null || list != null || searching || settings) {
        when {
            open != null -> open = null
            list != null -> list = null
            searching -> searching = false
            else -> settings = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(M3EColor.page())
    ) {
        // Each section keeps its own state — the calendar's month, the detail's season, a search
        // query — because leaving a section and returning otherwise resets it and makes the whole
        // thing feel like a demo.
        val holder = rememberSaveableStateHolder()
        val opened = open
        val shownList = list
        when {
            opened != null -> M3ERoughDetail(
                item = opened,
                container = container,
                onOpenMedia = { open = it }
            )
            shownList != null -> M3ERoughList(
                list = shownList,
                core = core,
                onOpen = { open = it },
                onBack = { list = null }
            )
            searching -> M3ERoughSearch(
                library = library,
                container = container,
                onOpen = { open = it },
                onBack = { searching = false }
            )
            settings -> M3ERoughSettings(
                    container = container,
                    onBack = { settings = false },
                    onThemePresetChanged = onThemePresetChanged,
                    onThemeModeChanged = onThemeModeChanged
                )
            else -> HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            holder.SaveableStateProvider(page) {
                when (page) {
                0 -> HomeSection(
                    core = core,
                    home = home,
                    onOpen = { open = it },
                    onSeeAll = { title, items, kind -> list = SectionList(title, items, kind) },
                    onSearch = { searching = true },
                    onSettings = { settings = true }
                )
                1 -> M3ERoughHub(
                    container = container,
                    core = core,
                    onOpen = { open = it },
                    onOpenList = { title, items, kind, layout ->
                        list = SectionList(title, items, kind, layout)
                    }
                )
                2 -> M3ERoughCalendar(core = core, onOpen = { open = it })
                3 -> LibrarySection(core = core, onOpen = { open = it })
                else -> FinishedSection(library = library, onOpen = { open = it })
                }
            }
            }
        }

        FloatingNav(
            selected = pagerState.currentPage,
            onSelect = { target ->
                if (target != pagerState.currentPage) haptics.tap()
                scope.launch { pagerState.animateScrollToPage(target) }
            },
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
    core: LibraryCore,
    home: HomeData,
    onOpen: (MediaItemEntity) -> Unit,
    onSeeAll: (String, List<MediaItemEntity>, ListSubtitle) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit
) {
    // Rails come from the same derivations the shipping Home uses — HomeEligibility's eligibility
    // rules decide Continue watching, next-airing instants decide Coming soon — rather than this
    // sketch's own date comparisons. Which rail a title belongs in is decided there, not here.
    val inProgress = home.resumeItems
    val comingSoon = home.comingSoonItems
    val recentlyAdded = home.recentlyAddedItems
    val completed = remember(core.items) {
        core.items.count { it.state.equals("Completed", true) }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = M3ESpacing.page),
        verticalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)
    ) {
        item {
            Column {
            Spacer(Modifier.height(M3ESpacing.pageVertical))
            M3EScreenHeader(
                eyebrow = "Explore",
                title = "MediaTracker",
                trailing = {
                    Row {
                        IconButton(onClick = onSearch) {
                            Icon(Icons.Default.Search, contentDescription = "Search", tint = M3EColor.text())
                        }
                        IconButton(onClick = onSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = M3EColor.text())
                        }
                    }
                }
            )
            }
        }
        item { EmphasisBanner(count = inProgress.size) }
        item { MetricRow(core.items.size, inProgress.size, completed) }

        if (inProgress.isNotEmpty()) {
            item {
                Spacer(Modifier.height(M3ESpacing.tightGap))
                M3ESectionHeader(
                    "Continue watching",
                    trailing = {
                        TextButton(onClick = { onSeeAll("Continue watching", inProgress, ListSubtitle.Episode) }) {
                            Text("See all")
                        }
                    }
                )
            }
            items(inProgress.take(RAIL_SIZE), key = { it.id }) { item ->
                TitleRow(
                    item = item,
                    trailing = Icons.Default.PlayArrow,
                    onClick = { onOpen(item) },
                    subtitle = nextEpisodeSubtitle(item, core)
                )
            }
        }
        if (comingSoon.isNotEmpty()) {
            item {
                Spacer(Modifier.height(M3ESpacing.tightGap))
                M3ESectionHeader(
                    "Coming soon",
                    trailing = {
                        TextButton(onClick = { onSeeAll("Coming soon", comingSoon, ListSubtitle.Arrival) }) {
                            Text("See all")
                        }
                    }
                )
            }
            items(comingSoon.take(RAIL_SIZE), key = { it.id }) { item ->
                TitleRow(
                    item = item,
                    trailing = Icons.Default.Schedule,
                    onClick = { onOpen(item) },
                    subtitle = comingSoonSubtitle(item, core)
                )
            }
        }
        if (recentlyAdded.isNotEmpty()) {
            item {
                Spacer(Modifier.height(M3ESpacing.tightGap))
                M3ESectionHeader(
                    "Recently added",
                    trailing = {
                        TextButton(onClick = { onSeeAll("Recently added", recentlyAdded, ListSubtitle.None) }) {
                            Text("See all")
                        }
                    }
                )
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
private fun LibrarySection(core: LibraryCore, onOpen: (MediaItemEntity) -> Unit) {
    // Real filtering and sorting: LibraryQuery is the same pure routine the shipping Library uses,
    // including its sort orders, rather than a second set invented for this screen.
    // Held as the two plain values rather than as a LibraryFilters, so they can be saveable: the
    // filter set is a data class of sets and has no saver, and these are the only two the screen
    // offers. Same reason as the Hub's tab — a plain remember is lost the moment the section leaves
    // the composition, which opening any title does.
    var filterType by rememberSaveable { mutableStateOf(LibraryFilters.TYPE_ALL) }
    var filterSort by rememberSaveable { mutableStateOf(LibraryFilters.SORT_RECENT) }
    val filters = remember(filterType, filterSort) {
        LibraryFilters(type = filterType, sort = filterSort)
    }
    val badgeLabels = remember(core) { core.badges.mapValues { it.value.badge.label } }
    val library = remember(core, filters) {
        LibraryQuery.apply(core.items, "", filters, badgeLabels)
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = M3ESpacing.page),
        horizontalArrangement = Arrangement.spacedBy(M3ESpacing.inlineGap),
        verticalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)
    ) {
        // A lazy item's content is not a container: several children are drawn at the same
        // offset. Anything with more than one visible child has to bring its own Column.
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Spacer(Modifier.height(M3ESpacing.pageVertical))
                M3EScreenHeader(eyebrow = "${library.size} titles", title = "Library")
                Spacer(Modifier.height(M3ESpacing.tightGap))
                ChipRow(
                    options = listOf(LibraryFilters.TYPE_ALL, "Movies", "TV Shows"),
                    selected = filterType,
                    onSelect = { filterType = it }
                )
                Spacer(Modifier.height(M3ESpacing.tightGap))
                ChipRow(
                    options = LibraryFilters.SORTS,
                    selected = filterSort,
                    onSelect = { filterSort = it }
                )
                Spacer(Modifier.height(M3ESpacing.cardGap))
            }
        }
        items(library, key = { it.id }) { item ->
            M3EArtworkCard(title = item.title, onClick = { onOpen(item) }) {
                Poster(item, modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f))
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Spacer(Modifier.height(112.dp))
        }
    }
}

@Composable
private fun FinishedSection(library: List<MediaItemEntity>, onOpen: (MediaItemEntity) -> Unit) {
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
            Column {
                Spacer(Modifier.height(M3ESpacing.pageVertical))
                M3EScreenHeader(eyebrow = "${finished.size} watched", title = "Finished")
            }
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
            TitleRow(item, trailing = Icons.Default.Check, onClick = { onOpen(item) })
        }
        item { Spacer(Modifier.height(112.dp)) }
    }
}

/** Which supporting line an opened list shows. Shared with the Hub, which pushes lists too. */
internal enum class ListSubtitle { Episode, Arrival, None }

/**
 * How an opened list presents its titles.
 *
 * A rail pushes rows, because the line beside each poster is the point of it — the episode you are
 * on, when the next one lands. A facet pushes the Library's grid, because you are browsing rather
 * than resuming, and artwork is what you browse by.
 */
internal enum class ListLayout { Rows, Grid }

/** A list pushed by a rail's "See all". The subtitle is derived on render, so it stays current. */
private data class SectionList(
    val title: String,
    val items: List<MediaItemEntity>,
    val subtitle: ListSubtitle,
    val layout: ListLayout = ListLayout.Rows
)

/**
 * A "See all" list: the titles behind a rail, as a screen.
 *
 * A rail is a top-N view of a list that is usually longer, so "See all" pushes the whole thing
 * rather than growing the rail on Home. The supporting line is whatever that rail was showing —
 * progress for one, an arrival day for another — so the list reads as a continuation of it.
 */
@Composable
private fun M3ERoughList(
    list: SectionList,
    core: LibraryCore,
    onOpen: (MediaItemEntity) -> Unit,
    onBack: () -> Unit
) {
    when (list.layout) {
        ListLayout.Grid -> GridList(list, onOpen, onBack)
        ListLayout.Rows -> RowList(list, core, onOpen, onBack)
    }
}

/** The Library's own presentation: three columns of posters, captions left to the long press. */
@Composable
private fun GridList(list: SectionList, onOpen: (MediaItemEntity) -> Unit, onBack: () -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier
            .fillMaxSize()
            .background(M3EColor.page())
            .statusBarsPadding()
            .padding(horizontal = M3ESpacing.page),
        horizontalArrangement = Arrangement.spacedBy(M3ESpacing.inlineGap),
        verticalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Spacer(Modifier.height(M3ESpacing.pageVertical))
                M3EScreenHeader(
                    eyebrow = "${list.items.size} titles",
                    title = list.title,
                    // A pushed title can be a word in another script, and at display size a single
                    // word is laid out as two lines — Devanagari offers break opportunities inside
                    // a word, so "हिन्दी" split. Smaller and single-line keeps it one word.
                    compact = true
                )
            }
        }
        items(list.items, key = { it.id }) { item ->
            M3EArtworkCard(title = item.title, onClick = { onOpen(item) }) {
                Poster(item, modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f))
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Spacer(Modifier.height(M3ESpacing.bottomInset))
        }
    }
}

@Composable
private fun RowList(
    list: SectionList,
    core: LibraryCore,
    onOpen: (MediaItemEntity) -> Unit,
    onBack: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(M3EColor.page())
            .statusBarsPadding()
            .padding(horizontal = M3ESpacing.page),
        verticalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)
    ) {
        item {
            Column {
                Spacer(Modifier.height(M3ESpacing.pageVertical))
                M3EScreenHeader(
                    eyebrow = "${list.items.size} titles",
                    title = list.title,
                    // A pushed title can be a word in another script, and at display size a single
                    // word is laid out as two lines — Devanagari offers break opportunities inside
                    // a word, so "हिन्दी" split. Smaller and single-line keeps it one word.
                    compact = true
                )
            }
        }
        items(list.items, key = { it.id }) { item ->
            val subtitle = when (list.subtitle) {
                ListSubtitle.Episode -> nextEpisodeSubtitle(item, core)
                ListSubtitle.Arrival -> arrivalLabel(item, core)
                ListSubtitle.None -> null
            }
            TitleRow(
                item = item,
                trailing = Icons.Default.PlayArrow,
                onClick = { onOpen(item) },
                subtitle = subtitle
            )
        }
        item { Spacer(Modifier.height(M3ESpacing.bottomInset)) }
    }
}

/** A wrapping row of single-select chips, for the Library's type and sort controls. */
@Composable
private fun ChipRow(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(M3ESpacing.tightGap),
        verticalArrangement = Arrangement.spacedBy(M3ESpacing.tightGap)
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(option, style = M3EType.action()) },
                shape = M3EShape.chip()
            )
        }
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
private fun TitleRow(
    item: MediaItemEntity,
    trailing: ImageVector,
    onClick: (() -> Unit)? = null,
    subtitle: String? = null
) {
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
                    text = subtitle ?: "${item.type} · ${item.state}",
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
    M3EArtworkCard(title = item.title, modifier = Modifier.width(width), onClick = onClick) {
        Poster(item, modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f))
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
        "Hub" to Icons.Default.AutoAwesome,
        "Calendar" to Icons.Default.Schedule,
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
                // Filled means chosen, the same as the calendar's selected day: a solid accent
                // pill, so the current section is obvious at a glance rather than by a hairline.
                Surface(
                    onClick = { onSelect(index) },
                    shape = RoundedCornerShape(50),
                    color = if (active) M3EColor.accent() else M3EColor.card()
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
                            tint = if (active) MaterialTheme.colorScheme.onPrimary else M3EColor.textMuted()
                        )
                        if (active) {
                            Text(label, style = M3EType.action(), color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
            }
        }
    }
}

/** The day a title lands: an air date for a show, a release date for a movie. */
private fun releaseDay(item: MediaItemEntity): String? =
    item.nextAiringInstant?.take(10) ?: item.releaseDate?.take(10)

/** How many titles a rail shows before "See all" takes over. */
private const val RAIL_SIZE = 5

/**
 * The next episode of a show: the first unwatched one, or — for a rail about what is coming — the
 * first one still to air. Both the Continue watching and Coming soon rails show this rather than a
 * watched count, because the episode is the thing you would actually do next.
 */
private fun nextEpisodeFor(
    item: MediaItemEntity,
    core: LibraryCore,
    futureOnly: Boolean
): EpisodeEntity? {
    if (!item.type.contains("TV", ignoreCase = true)) return null
    val episodes = core.episodesByShow[MediaIds.tvShowKey(item.id)].orEmpty()
        .sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))
    if (!futureOnly) return episodes.firstOrNull { !it.isWatched }
    val now = java.util.Date()
    return episodes.firstOrNull {
        !it.isWatched && HomeEligibility.episodeInstant(item, it)?.after(now) == true
    }
}

private fun episodeCode(episode: EpisodeEntity): String =
    "S${episode.seasonNumber} E${episode.episodeNumber}"

/** "S2 E5", or null for a film or a show with nothing left to watch. */
private fun nextEpisodeSubtitle(item: MediaItemEntity, core: LibraryCore): String? =
    nextEpisodeFor(item, core, futureOnly = false)?.let { episodeCode(it) }

/** "S2 E5 · Arrives today · 21:00" — the episode, and when it lands. */
private fun comingSoonSubtitle(item: MediaItemEntity, core: LibraryCore): String? {
    val code = nextEpisodeFor(item, core, futureOnly = true)?.let { episodeCode(it) }
    val when_ = arrivalLabel(item, core)
    return when {
        code != null && when_ != null -> "$code · $when_"
        code != null -> code
        else -> when_
    }
}

/**
 * When a Coming soon title lands, from its resolved air instant rather than its date.
 *
 * A date cannot tell a premiere that airs at 21:00 from one that lands at midnight, and it cannot
 * tell whether today has already passed — an instant can. Today and tomorrow are named because
 * that is how a person reads a radar, and the local time is shown whenever the app knows one, so
 * "Arrives today · 21:00" is a fact rather than an approximation.
 */
private fun arrivalLabel(item: MediaItemEntity, core: LibraryCore): String? {
    val key = if (item.type.contains("TV", ignoreCase = true)) MediaIds.tvShowKey(item.id) else null
    val episodes = key?.let { core.episodesByShow[it] }.orEmpty()
    val instant = HomeEligibility.nextAiringInstant(item, episodes)
        ?: return releaseDay(item)?.let { "Arrives $it" }

    val zoned = instant.toInstant().atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    val dayLabel = when (zoned.toLocalDate()) {
        today -> "today"
        today.plusDays(1) -> "tomorrow"
        else -> zoned.toLocalDate().toString()
    }
    val time = zoned.toLocalTime()
    val known = time.hour != 0 || time.minute != 0
    return if (known) {
        "Arrives $dayLabel · " + String.format(Locale.US, "%02d:%02d", time.hour, time.minute)
    } else {
        "Arrives $dayLabel"
    }
}
