package com.vara.mediatracker.ui.m3e

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreHoriz
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
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

/** Section indices, so the bar can name the three it shows and the three it hides. */
private const val HOME = 0
private const val CALENDAR = 1
private const val LIBRARY = 2
private const val SETTINGS = 3


/**
 * What is open over the sections, or nothing.
 *
 * One slot rather than four booleans. With four, "which is on top" had to be decided by a priority
 * order written out twice — in the render and in the back handler — and nothing cleared the others
 * when one was set. So tapping Settings while Search was open set a flag and changed nothing on
 * screen, because Search outranked it. With one slot there is no order to keep in step: opening
 * replaces, and back closes.
 */
private sealed interface Overlay {
    data class Detail(val item: MediaItemEntity) : Overlay
    data class Titles(
        val title: String,
        val items: List<MediaItemEntity>,
        val subtitle: ListSubtitle,
        val layout: ListLayout
    ) : Overlay
    data object Hub : Overlay
    data object Finished : Overlay
    data object Search : Overlay
}

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
    var section by rememberSaveable { mutableIntStateOf(0) }
    // A stack, not a slot. One slot meant a title opened from Finished came back to Library — the
    // section — because the screen you actually came from had nowhere to be remembered.
    var stack by remember { mutableStateOf(listOf<Overlay>()) }
    val overlay = stack.lastOrNull()
    val haptics = rememberHaptics()

    val density = LocalDensity.current

    // The bar's height is the only unknown: the gap it keeps above the system bar and the system
    // bar's own height are both known, and the three together are the room a list must leave at its
    // foot. A single constant was right under gesture navigation and short by a system bar's worth
    // under three-button navigation, which put the last row under the bar.
    val heroOrigin = remember { HeroOrigin() }
    var navHeight by remember { mutableStateOf(0.dp) }
    val navClearance = navHeight + M3ESpacing.navGap +
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // Back unwinds one step at a time: a title opened from Finished returns to Finished, and
    // Finished returns to Library.
    //
    // A plain handler, deliberately. The predictive version — the gesture driving a scale and fade —
    // was tried and removed: its payoff was never going to arrive, because a predictive back peek
    // shows the screen underneath and this shell composes one screen at a time, so there is nothing
    // to show. What it did instead was leave the screen shrunk. The system-level animation is still
    // on (the manifest flag is unchanged); this is only the in-app half, and it is not worth code
    // that misbehaves for an effect with no audience.
    BackHandler(enabled = stack.isNotEmpty()) { stack = stack.dropLast(1) }

    // With nothing open, back from a section that is not Home goes Home rather than leaving the app
    // — what a bottom bar sets the expectation of everywhere else. Back at Home still exits.
    //
    // Gated on the stack being empty, so exactly one of these is ever enabled.
    BackHandler(enabled = stack.isEmpty() && section != HOME) { section = HOME }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(M3EColor.page())
    ) {
        // Each section keeps its own state — the calendar's month, the detail's season, a search
        // query — because leaving a section and returning otherwise resets it and makes the whole
        // thing feel like a demo.
        val holder = rememberSaveableStateHolder()
        // A second holder for the overlay stack. Without it, pushing a list on top of the Hub took
        // the Hub out of the composition and its tab came back as Smart — the state had nowhere to
        // be kept. Keyed by depth, so each step remembers its own.
        val overlayHolder = rememberSaveableStateHolder()
        CompositionLocalProvider(
            LocalNavClearance provides navClearance,
            LocalHeroOrigin provides heroOrigin
        ) {
        // The detail is the one overlay that is a sheet, and a sheet slides *over* the screen rather
        // than replacing it — so there has to be a screen for it to slide over, and the section stays
        // composed while it is up. Every other overlay still replaces, which is what they are.
        val detail = overlay as? Overlay.Detail
        if (overlay == null || detail != null) {
            // No transition between sections. Animating between two means composing two of them at
            // once, and each is a whole screen of derivation — so the animation spent its time racing
            // the work it was covering, which is what "weird" was. A bottom nav switches instantly on
            // Android, and instantly is what this does now.
            FadeIn(key = section) {
            holder.SaveableStateProvider(section) {
                when (section) {
                0 -> HomeSection(
                    core = core,
                    home = home,
                    onOpen = { stack = stack + Overlay.Detail(it) },
                    onSeeAll = { title, items, kind ->
                        stack = stack + Overlay.Titles(title, items, kind, ListLayout.Rows)
                    }
                )
                1 -> M3ERoughCalendar(core = core, onOpen = { stack = stack + Overlay.Detail(it) })
                2 -> LibrarySection(
                    core = core,
                    onOpen = { stack = stack + Overlay.Detail(it) },
                    onHub = { stack = stack + Overlay.Hub },
                    onFinished = { stack = stack + Overlay.Finished }
                )
                else -> M3ERoughSettings(
                    container = container,
                    onBack = { },
                    onThemePresetChanged = onThemePresetChanged,
                    onThemeModeChanged = onThemeModeChanged
                )
                }
            }
            }
        }

        if (detail != null) {
            DetailSheet(onDismiss = { stack = stack.dropLast(1) }) { rise, sheetHeight ->
                // The overlay holder still wraps it: pushing a related title on top takes this one
                // out of the composition, and without somewhere to keep its season and pickers they
                // would reset on the way back.
                overlayHolder.SaveableStateProvider(stack.lastIndex) {
                    M3ERoughDetail(
                        item = detail.item,
                        container = container,
                        // Only if this detail was opened from that title's poster. Read here rather
                        // than passed through every tap handler: the push is what makes it relevant,
                        // and the id makes a stale rectangle harmless.
                        heroFrom = if (heroOrigin.itemId == detail.item.id) heroOrigin.rect else null,
                        sheetRise = rise,
                        sheetHeight = sheetHeight,
                        onOpenMedia = { stack = stack + Overlay.Detail(it) }
                    )
                }
            }
        } else if (overlay != null) {
            FadeIn(key = overlay, rise = if (overlay is Overlay.Search) 28.dp else 0.dp) {
            overlayHolder.SaveableStateProvider(stack.lastIndex) {
            when (val current = overlay) {
                is Overlay.Titles -> M3ERoughList(
                    list = SectionList(current.title, current.items, current.subtitle, current.layout),
                    core = core,
                    onOpen = { stack = stack + Overlay.Detail(it) },
                    onBack = { stack = stack.dropLast(1) }
                )
                Overlay.Hub -> M3ERoughHub(
                    container = container,
                    core = core,
                    onOpen = { stack = stack + Overlay.Detail(it) },
                    onOpenList = { title, items, kind, layout ->
                        stack = stack + Overlay.Titles(title, items, kind, layout)
                    }
                )
                Overlay.Finished -> FinishedSection(
                    library = library,
                    onOpen = { stack = stack + Overlay.Detail(it) }
                )
                Overlay.Search -> M3ERoughSearch(
                    library = library,
                    container = container,
                    onOpen = { stack = stack + Overlay.Detail(it) },
                    onBack = { stack = stack.dropLast(1) }
                )
                else -> Unit
            }
            }
            }
        }
        }

        // While an overlay is open no section is current — Settings was being lit *alongside* Home,
        // which made it look like a child of whichever section you happened to be in.
        FloatingNav(
            selected = if (overlay == null) section else -1,
            onSelect = { target ->
                if (target != section || stack.isNotEmpty()) haptics.tap()
                // The bar means "take me to that section", from anywhere, so everything open is
                // cleared rather than left behind the screen.
                stack = emptyList()
                section = target
            },
            onSearch = {
                haptics.tap()
                stack = stack + Overlay.Search
            },
            searching = overlay is Overlay.Search,
            // Search keeps the four pills — you can move on from it without going back — and so do
            // the sections. Everything else is somewhere you came from a section, and the bar
            // offers the way back to it rather than four places that are behind the screen.
            // Named from the entry *below* the top, so a title opened from Finished says
            // "Back to Finished" and Finished says "Back to Library" — the real step, not the
            // section everything happens to sit in.
            // Search is the one screen whose way out is a close rather than a step back — there is
            // nothing behind it that you were looking at.
            parentLabel = when {
                overlay is Overlay.Search -> "Close"
                overlay != null -> when (val below = stack.getOrNull(stack.size - 2)) {
                    is Overlay.Detail -> below.item.title
                    is Overlay.Titles -> below.title
                    Overlay.Hub -> "Hub"
                    Overlay.Finished -> "Finished"
                    else -> sectionLabel(section)
                }
                else -> null
            },
            parentIcon = if (overlay is Overlay.Search) {
                Icons.Default.Close
            } else {
                Icons.AutoMirrored.Filled.ArrowBack
            },
            onBackToParent = { stack = stack.dropLast(1) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = M3ESpacing.navGap)
                .onSizeChanged { navHeight = with(density) { it.height.toDp() } }
        )
    }
}

/**
 * The detail, arriving as a sheet from the bottom.
 *
 * The scrim is not decoration: it is what consumes taps. The section is still composed underneath —
 * that is the whole point of a sheet — so without something taking the touch, taps would fall through
 * to the grid behind it.
 *
 * The corners round while the sheet is travelling and flatten as it lands, because a full-height
 * sheet with rounded corners would show the scrim through them at rest. Both are set in the draw
 * phase, so the rise never recomposes the detail it is carrying.
 */
@Composable
private fun DetailSheet(
    onDismiss: () -> Unit,
    content: @Composable (rise: State<Float>, height: Dp) -> Unit
) {
    var landed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { landed = true }
    val rise = animateFloatAsState(
        targetValue = if (landed) 1f else 0f,
        animationSpec = tween(durationMillis = M3E_DETAIL_ARRIVAL_MILLIS),
        label = "sheetRise"
    )
    val density = LocalDensity.current
    // The height the sheet is travelling from. Handed to the detail so the poster can cancel it out:
    // the poster's flight is in screen coordinates, and the sheet's own travel would otherwise be
    // added on top of it — which put the poster a full screen below the tile it came from.
    var height by remember { mutableStateOf(0.dp) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { height = with(density) { it.height.toDp() } }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim)
                .graphicsLayer { alpha = 0.45f * rise.value }
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = (1f - rise.value) * size.height
                    if (rise.value < 1f) {
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                        clip = true
                    }
                }
                .background(M3EColor.page())
                // Consumes taps on blank space. Without it a tap between two buttons falls through
                // to the scrim below, which dismisses the sheet — the detail would close whenever
                // you touched nothing in particular.
                .pointerInput(Unit) { detectTapGestures { } }
        ) { content(rise, height) }
    }
}

// MARK: - Sections

@Composable
private fun HomeSection(
    core: LibraryCore,
    home: HomeData,
    onOpen: (MediaItemEntity) -> Unit,
    onSeeAll: (String, List<MediaItemEntity>, ListSubtitle) -> Unit
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
            // No search or settings icon here any more: both are on the bar, where a thumb can
            // reach them, and a second entry point at the top of the screen was only ever a
            // duplicate in the worst place.
            M3EScreenHeader(eyebrow = "Explore", title = "MediaTracker")
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
        item { Spacer(Modifier.height(LocalNavClearance.current)) }
    }
}

@Composable
private fun LibrarySection(
    core: LibraryCore,
    onOpen: (MediaItemEntity) -> Unit,
    onHub: () -> Unit,
    onFinished: () -> Unit
) {
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
                // The two views that are not sections. They live here because Library is where you
                // are when you want either of them, and because a screen you open is a screen back
                // can leave — which is what a section could never do.
                Row(horizontalArrangement = Arrangement.spacedBy(M3ESpacing.tightGap)) {
                    LibraryShortcut(Icons.Default.AutoAwesome, "Hub", onHub)
                    LibraryShortcut(Icons.Default.Check, "Finished", onFinished)
                }
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
            val hero = rememberTileOrigin(item.id)
            M3EArtworkCard(
                title = item.title,
                modifier = hero.modifier,
                onClick = { hero.record(); onOpen(item) }
            ) {
                Poster(item, modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f))
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Spacer(Modifier.height(LocalNavClearance.current))
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
        item { Spacer(Modifier.height(LocalNavClearance.current)) }
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
            val hero = rememberTileOrigin(item.id)
            M3EArtworkCard(
                title = item.title,
                modifier = hero.modifier,
                onClick = { hero.record(); onOpen(item) }
            ) {
                Poster(item, modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f))
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Spacer(Modifier.height(LocalNavClearance.current))
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
        item { Spacer(Modifier.height(LocalNavClearance.current)) }
    }
}

/** One of the Library's two ways into a view that is not a section. */
@Composable
private fun LibraryShortcut(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = M3EShape.chip(),
        color = M3EColor.card(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = M3ESpacing.cardGap, vertical = M3ESpacing.tightGap),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(M3ESpacing.tightGap)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = M3EColor.textMuted(),
                modifier = Modifier.size(18.dp)
            )
            Text(text = label, style = M3EType.action(), color = M3EColor.text())
        }
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

/**
 * Fades its content in on first composition.
 *
 * Deliberately *not* an `AnimatedContent`. That composes the outgoing and incoming trees together
 * during a transition, and the section tree contains a `SaveableStateProvider` — two of them alive
 * at once, handed the same key, which Compose rejects outright ("Key 0 was used multiple times").
 * The crash was structural, not incidental.
 *
 * This wraps nothing: the outgoing section is disposed as usual and the incoming one animates its
 * own alpha up. Same effect, one tree alive at a time.
 */
@Composable
private fun FadeIn(key: Any?, rise: Dp = 0.dp, content: @Composable () -> Unit) {
    // Keyed, and that is the whole point of the parameter: unkeyed, this ran once on first
    // composition and never again, so changing what is inside it animated nothing. The fade
    // appeared to work only because it was never watched twice.
    var shown by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) { shown = true }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(durationMillis = if (rise > 0.dp) 240 else 180),
        label = "sectionFade"
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(progress)
            // A rise, when one is asked for: search comes up from the pill that opened it rather
            // than appearing over the sections. Not a morph — that needs a shared-element
            // transition — but it reads as the same movement, which is what the eye is following.
            .offset(y = rise * (1f - progress))
    ) { content() }
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
    val hero = rememberTileOrigin(item.id)
    M3ETonalCard(
        modifier = Modifier.fillMaxWidth().then(hero.modifier),
        onClick = if (onClick == null) null else { { hero.record(); onClick() } }
    ) {
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
    val hero = rememberTileOrigin(item.id)
    M3EArtworkCard(
        title = item.title,
        modifier = Modifier.width(width).then(hero.modifier),
        onClick = if (onClick == null) null else { { hero.record(); onClick() } }
    ) {
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
private fun FloatingNav(
    selected: Int,
    onSelect: (Int) -> Unit,
    onSearch: () -> Unit,
    searching: Boolean,
    /** Where the back pill goes, when what is on screen is not one of the four. */
    parentLabel: String? = null,
    parentIcon: ImageVector = Icons.AutoMirrored.Filled.ArrowBack,
    onBackToParent: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(M3ESpacing.tightGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = M3EColor.card(),
            shadowElevation = 12.dp,
            // Capped, because weighted children take everything they are offered — and four equal
            // pills across a full screen is a toolbar, not a capsule.
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Row(
                modifier = Modifier.padding(M3ESpacing.tightGap),
                horizontalArrangement = Arrangement.spacedBy(M3ESpacing.tightGap),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (parentLabel != null) {
                    // Not one of the four: a title, Hub, Finished or a pushed list. The bar says
                    // where it goes instead of offering four places that are behind the screen.
                    NavPill(
                        icon = parentIcon,
                        label = if (parentLabel == "Close") "Close" else "Back to $parentLabel",
                        active = true,
                        onClick = onBackToParent
                    )
                } else {
                NavPill(Icons.Default.Home, "Home", selected == HOME) { onSelect(HOME) }
                NavPill(Icons.Default.CalendarMonth, "Calendar", selected == CALENDAR) {
                    onSelect(CALENDAR)
                }
                NavPill(Icons.Default.VideoLibrary, "Library", selected == LIBRARY) {
                    onSelect(LIBRARY)
                }
                // Settings is a section like the other three, not an overlay. It was the fourth
                // button but behaved differently — no lit pill, and back closed it — which is what
                // made it feel like it belonged to whichever section you came from.
                NavPill(Icons.Default.Settings, "Settings", selected == SETTINGS) {
                    onSelect(SETTINGS)
                }
                }
            }
        }

        // Search, on its own: an action rather than a place you stay. Lit while the overlay is
        // open, because then it is the only thing on screen saying where you are.
        Surface(
            onClick = onSearch,
            shape = RoundedCornerShape(50),
            color = if (searching) M3EColor.accent() else M3EColor.card(),
            shadowElevation = 12.dp,
            modifier = Modifier.size(56.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = if (searching) MaterialTheme.colorScheme.onPrimary else M3EColor.textMuted()
                )
            }
        }
    }
}

/** One section in the bar: icon always, name only when it is the current one. */
@Composable
private fun NavPill(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (active) M3EColor.accent() else M3EColor.card()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (active) 16.dp else 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(M3ESpacing.tightGap)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (active) MaterialTheme.colorScheme.onPrimary else M3EColor.textMuted()
            )
            if (active) {
                Text(
                    text = label,
                    style = M3EType.action(),
                    color = MaterialTheme.colorScheme.onPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** The bar's name for a section, so the back pill can say where it goes. */
private fun sectionLabel(index: Int): String = when (index) {
    HOME -> "Home"
    CALENDAR -> "Calendar"
    LIBRARY -> "Library"
    else -> "Settings"
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
