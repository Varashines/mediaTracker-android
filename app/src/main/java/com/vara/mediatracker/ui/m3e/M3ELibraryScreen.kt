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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.toShape
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import com.vara.mediatracker.data.TrackingLogic
import com.vara.mediatracker.data.sync.SyncScheduler
import com.vara.mediatracker.ui.common.AppToast
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
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
import com.vara.mediatracker.ui.common.ManualSync
import com.vara.mediatracker.ui.common.UpNextShortcut
import com.vara.mediatracker.ui.home.HomeEligibility
import com.vara.mediatracker.ui.library.LibraryFilters
import com.vara.mediatracker.ui.library.LibraryQuery
import com.vara.mediatracker.ui.common.rememberHaptics
import java.time.Year
import com.vara.mediatracker.di.AppContainer

@Composable
internal fun LibrarySection(
    core: LibraryCore,
    onOpen: (MediaItemEntity) -> Unit,
    onHub: () -> Unit,
    onFinished: () -> Unit,
    onHistory: () -> Unit,
    onSearch: () -> Unit
) {
    // A cold start renders the empty state before Room answers — "0 titles", "Your library is up
    // to date" — which reads as an empty library rather than a loading one.
    if (!core.loaded) {
        M3ESkeleton()
        return
    }

    // Real filtering and sorting: LibraryQuery is the same pure routine the shipping Library uses,
    // including its sort orders, rather than a second set invented for this screen.
    // Held as the two plain values rather than as a LibraryFilters, so they can be saveable: the
    // filter set is a data class of sets and has no saver, and these are the only two the screen
    // offers. Same reason as the Hub's tab — a plain remember is lost the moment the section leaves
    // the composition, which opening any title does.
    var filterType by rememberSaveable { mutableStateOf(LibraryFilters.TYPE_ALL) }
    var filterSort by rememberSaveable { mutableStateOf(LibraryFilters.SORT_RECENT) }
    // Posters alone fail for titles whose artwork tells you nothing. The list is one column of the
    // same grid rather than a second screen, so the filters, the sort and the scroll position cannot
    // diverge between the two.
    var listView by rememberSaveable { mutableStateOf(false) }
    val filters = remember(filterType, filterSort) {
        LibraryFilters(type = filterType, sort = filterSort)
    }
    val badgeLabels = remember(core) { core.badges.mapValues { it.value.badge.label } }
    val library = remember(core, filters) {
        LibraryQuery.apply(core.items, "", filters, badgeLabels)
    }

    // The frame is the scaffold's; the block at the top is not a header but a control panel — the
    // view toggle, the way into the other three views, and both filters — so it keeps its own slot
    // rather than being squeezed into a title's trailing area.
    GridScaffold(
        title = "Library",
        columns = if (listView) 1 else 3,
        header = false,
        horizontalArrangement = Arrangement.spacedBy(M3ESpacing.inlineGap)
    ) {
        // A lazy item's content is not a container: several children are drawn at the same
        // offset. Anything with more than one visible child has to bring its own Column.
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Spacer(Modifier.height(M3ESpacing.pageVertical))
                M3EScreenHeader(
                    eyebrow = "${library.size} titles",
                    title = "Library",
                    trailing = {
                        // Icon as the label, not in the `icon` slot.
                        //
                        // `SegmentedButton`'s icon slot is only drawn beside a label — it is meant
                        // for "a glyph and a word", so an icon with an empty label slot renders
                        // nothing at all. The label slot takes a composable, so the icon is the label
                        // here. Tinted explicitly rather than left to LocalContentColor, which was
                        // how the nav's icons ended up black on dark earlier.
                        SingleChoiceSegmentedButtonRow {
                            SegmentedButton(
                                selected = !listView,
                                onClick = { listView = false },
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.GridView,
                                    contentDescription = "Grid",
                                    tint = if (!listView) {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            SegmentedButton(
                                selected = listView,
                                onClick = { listView = true },
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.List,
                                    contentDescription = "List",
                                    tint = if (listView) {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                )
                Spacer(Modifier.height(M3ESpacing.tightGap))
                // The two views that are not sections. They live here because Library is where you
                // are when you want either of them, and because a screen you open is a screen back
                // can leave — which is what a section could never do.
                Row(horizontalArrangement = Arrangement.spacedBy(M3ESpacing.tightGap)) {
                    LibraryShortcut(Icons.Default.AutoAwesome, "Hub", onHub)
                    LibraryShortcut(Icons.Default.Check, "Finished", onFinished)
                    LibraryShortcut(Icons.Default.History, "History", onHistory)
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

        // Two different kinds of nothing. An empty library needs a way in; an empty *filter* needs
        // to say the filters are the reason, or it looks like the library vanished.
        if (core.items.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(
                    title = "Your library's a blank slate.",
                    body = "Find something you love in Search and it'll settle in here — posters, air dates, all of it.",
                    action = {
                        Button(onClick = onSearch) { Text("Search for something") }
                    }
                )
            }
        } else if (library.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(
                    title = "Nothing matches those filters.",
                    body = "Loosen one of them and the shelf fills back up."
                )
            }
        }

        items(library, key = { it.id }) { item ->
            if (listView) {
                TitleRow(
                    item = item,
                    trailing = Icons.Default.ChevronRight,
                    onClick = { onOpen(item) },
                    subtitle = "${item.type} · ${item.state}"
                )
            } else {
                val hero = rememberTileOrigin(item.id)
                M3EArtworkCard(title = item.title, onClick = { hero.record(); onOpen(item) }) {
                    PosterTile(
                        posterUrl = item.posterUrl,
                        title = item.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(2f / 3f)
                            .then(hero.modifier)
                    )
                }
            }
        }
    }
}

@Composable
internal fun FinishedSection(library: List<MediaItemEntity>, onOpen: (MediaItemEntity) -> Unit) {
    val finished = remember(library) {
        library.filter { it.state.equals("Completed", true) }
            .sortedByDescending { it.lastStateChangeDate ?: it.lastInteractionDate ?: "" }
    }
    ScreenScaffold(
        title = "Finished",
        eyebrow = "${finished.size} watched"
    ) {
        if (finished.isEmpty()) {
            item {
                EmptyState(
                    title = "Nothing finished yet.",
                    body = "The ones you see through will end up here."
                )
            }
        }
        items(finished, key = { it.id }) { item ->
            TitleRow(item, trailing = Icons.Default.Check, onClick = { onOpen(item) })
        }
    }
}

/** One of the Library's two ways into a view that is not a section. */
@Composable
internal fun LibraryShortcut(icon: ImageVector, label: String, onClick: () -> Unit) {
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
internal fun ChipRow(options: List<String>, selected: String, onSelect: (String) -> Unit) {
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
internal fun FadeIn(key: Any?, rise: Dp = 0.dp, content: @Composable () -> Unit) {
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
            // Both the fade and the rise are set in the draw phase, not in composition. Reading
            // `progress` for `.alpha`/`.offset` put it in composition, so every frame of the
            // animation recomposed the whole section it was covering — the animation raced the
            // work it was meant to hide. In `graphicsLayer` the read happens at draw time and the
            // subtree is never re-invoked.
            .graphicsLayer {
                alpha = progress
                // A rise, when one is asked for: search comes up from the pill that opened it
                // rather than appearing over the sections. Not a morph — that needs a
                // shared-element transition — but it reads as the same movement.
                translationY = rise.toPx() * (1f - progress)
            }
    ) { content() }
}

// MARK: - Building blocks

/** The emphasised container: the single thing on a screen meant to be noticed. */
@Composable
internal fun EmphasisBanner(count: Int, onClick: (() -> Unit)? = null) {
    Card(
        onClick = { onClick?.invoke() },
        enabled = onClick != null,
        modifier = Modifier.fillMaxWidth(),
        // An expressive silhouette rather than a rounded rectangle. This is the one card the
        // language says should be noticed, so it is the one place a shape from `MaterialShapes`
        // reads as deliberate instead of decorative.
        shape = MaterialShapes.Cookie9Sided.toShape(),
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
                    style = M3EType.rowTitleStrong(),
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

/** One tonal card per row: poster, title, supporting line, trailing action. */
@Composable
internal fun TitleRow(
    item: MediaItemEntity,
    trailing: ImageVector,
    onClick: (() -> Unit)? = null,
    subtitle: String? = null
) {
    val hero = rememberTileOrigin(item.id)
    M3ETonalCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = if (onClick == null) null else { { hero.record(); onClick() } }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(M3ESpacing.inlineGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PosterTile(
                posterUrl = item.posterUrl,
                title = item.title,
                modifier = Modifier
                    .width(44.dp)
                    .height(66.dp)
                    .then(hero.modifier)
            )
            Spacer(Modifier.width(M3ESpacing.inlineGap))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = M3EType.rowTitleStrong(),
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

