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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Recommend
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialShapes
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
import androidx.compose.material3.carousel.HorizontalCenteredHeroCarousel
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.vara.mediatracker.data.TrackingLogic
import com.vara.mediatracker.data.api.TMDBClient
import com.vara.mediatracker.data.api.TMDBSearchItem
import com.vara.mediatracker.data.model.MediaIds
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.util.NetworkResolver
import com.vara.mediatracker.di.AppContainer
import com.vara.mediatracker.ui.common.LibraryCore
import java.util.Locale
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
    /** Held by the shell, because the control that changes it is in the nav bar. */
    tab: Int = 0,
    onTabChange: (Int) -> Unit = {},
    onOpen: (MediaItemEntity) -> Unit = {},
    onOpenList: (String, List<MediaItemEntity>, ListSubtitle, ListLayout) -> Unit = { _, _, _, _ -> }
) {
    val library = core.items
    val pagerState = rememberPagerState(
        initialPage = tab.coerceIn(0, HubTabs.lastIndex),
        pageCount = { HubTabs.size }
    )
    // The pager follows the bar rather than owning the answer: the bar is the control, and a pager
    // that kept its own index would drift from it every time the shell restored a tab.
    LaunchedEffect(tab) {
        if (pagerState.currentPage != tab) pagerState.animateScrollToPage(tab)
    }

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
        }

        // Not swipeable. The two pages are not a deck to be flicked through — Smart is a long
        // scroller, and a sideways drag that starts on one of its carousels was being taken by the
        // pager, so a carousel you meant to scroll jumped the tab instead.
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
            userScrollEnabled = false
        ) { page ->
            when (page) {
                0 -> SmartTab(library, core.networkLogos, container, onOpen, onOpenList)
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

/** A name and the titles behind it — a network, a genre, a language, a shelf. */
private data class Facet(
    val name: String,
    val items: List<MediaItemEntity>
) {
    val count: Int get() = items.size
}

private data class HubTab(val label: String, val icon: ImageVector)

private val HubTabs = listOf(
    HubTab("Smart", Icons.Default.AutoAwesome),
    HubTab("Collections", Icons.Default.Bookmarks)
)

// MARK: - Smart

/**
 * One tab, not two.
 *
 * Smart and Discover were separate tabs showing the same thing differently — six counts, then three
 * grids of counts — and neither answered the question a hub is for, which is "show me something".
 * The facets are the same facets either way; what was missing was the artwork.
 *
 * So each facet carries the poster of the first title in it, and the facets are carousels. That is
 * what a carousel is for: it masks its items to one shape as they scroll, which only works when the
 * items are images. A tile with a number on it got clipped like a photograph of nothing.
 */
@Composable
private fun SmartTab(
    library: List<MediaItemEntity>,
    networkLogos: Map<String, String>,
    container: AppContainer,
    onOpen: (MediaItemEntity) -> Unit,
    onOpenList: (String, List<MediaItemEntity>, ListSubtitle, ListLayout) -> Unit
) {
    // Tints are read here because `M3EColor` is composable and `remember`'s lambda is not. The cards
    // no longer use them, but reading them keeps the remember keyed to the theme.
    val accent = M3EColor.accent()
    val positive = M3EColor.positive()
    val warning = M3EColor.warning()
    val groups = remember(library, accent, positive, warning) {
        listOf(
            Facet(
                "Continue watching",
                library.filter { it.state.equals("Active", true) || it.state.equals("Re-watching", true) }
            ),
            Facet("Loved", library.filter { it.taste.equals("Love", true) }),
            Facet("Wishlist", library.filter { it.state.equals("Wishlist", true) }),
            Facet("Finished", library.filter { it.state.equals("Completed", true) }),
            Facet("On hold", library.filter { it.state.equals("On Hold", true) }),
            Facet("Liked", library.filter { it.taste.equals("Like", true) })
        ).filter { it.items.isNotEmpty() }
    }

    // Networks only. Studios were here behind a pill pair, and they were the weaker half: a studio
    // is a production credit, and browsing by it answers a question almost nobody asks — who made
    // this — while a network answers the one people do, where can I watch it. A network also has a
    // mark, which is what makes it recognisable at the size of a poster.
    val brands = remember(library) {
        facets(library.filter { it.type.contains("TV", ignoreCase = true) }) { it.network }.take(12)
    }
    val genres = remember(library) { facets(library) { it.genres }.take(12) }
    // "en" is a database value; the carousel is a label. Codes are shown as the language they name.
    val languages = remember(library) {
        facets(library) { it.language }.take(12).map { facet -> Facet(languageName(facet.name), facet.items) }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = M3ESpacing.page),
        verticalArrangement = Arrangement.spacedBy(M3ESpacing.sectionGap)
    ) {
        if (groups.isEmpty() && brands.isEmpty() && genres.isEmpty() && languages.isEmpty()) {
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
        item { ForYouRow(library = library, container = container, onOpen = onOpen) }

        if (groups.isNotEmpty()) {
            // The cookie shape is the app's one playful shape — the emphasis card on Home has it —
            // so it marks the section that is about you rather than about the catalogue.
            item {
                FacetCarousel(
                    title = "Your shelf",
                    facets = groups,
                    shape = MaterialShapes.Cookie9Sided.toShape(),
                    icon = Icons.Default.AutoAwesome,
                    tint = M3EColor.accent(),
                    onOpenList = onOpenList
                )
            }
        }
        if (brands.isNotEmpty()) {
            // A network is a mark. A poster says "something on this network"; the logo says which,
            // and it is the thing a reader scans for.
            item {
                FacetCarousel(
                    title = "Networks",
                    facets = brands,
                    icon = Icons.Default.LiveTv,
                    tint = M3EColor.positive(),
                    onOpenList = onOpenList,
                    logoFor = { name ->
                        networkLogos[name.lowercase(Locale.US)]?.let(TMDBClient::buildLogoUrl)
                    }
                )
            }
        }
        if (genres.isNotEmpty()) {
            item {
                FacetCarousel(
                    "Genres",
                    genres,
                    onOpenList,
                    icon = Icons.Default.Category,
                    tint = M3EColor.warning()
                )
            }
        }
        if (languages.isNotEmpty()) {
            item {
                FacetCarousel(
                    "Languages",
                    languages,
                    onOpenList,
                    icon = Icons.Default.Language,
                    tint = M3EColor.accent()
                )
            }
        }
        item { Spacer(Modifier.height(LocalNavClearance.current)) }
    }
}

/**
 * A row of facets you can push sideways, each one a poster.
 *
 * `preferredItemWidth` and a fixed height are the whole contract: the carousel works out how much of
 * the next item shows, and the item fills whatever it is given. The item must not have a shape of
 * its own — the carousel's mask is the shape, and two rounds fighting each other is a corner that
 * changes as you scroll.
 */
@Composable
private fun FacetCarousel(
    title: String,
    facets: List<Facet>,
    onOpenList: (String, List<MediaItemEntity>, ListSubtitle, ListLayout) -> Unit,
    shape: Shape = M3EShape.hero(),
    icon: ImageVector? = null,
    tint: Color = M3EColor.accent(),
    logoFor: (String) -> String? = { null }
) {
    M3ESectionHeader(title, icon = icon, tint = tint)
    // A breath between the heading and the row, the same one Home puts before each of its
    // sections. Without it the heading reads as part of the card it is sitting on.
    Spacer(Modifier.height(M3ESpacing.tightGap))
    val state = rememberCarouselState { facets.size }
    HorizontalMultiBrowseCarousel(
        state = state,
        preferredItemWidth = 140.dp,
        modifier = Modifier
            .fillMaxWidth()
            .height(210.dp),
        itemSpacing = M3ESpacing.cardGap
    ) { index ->
        val facet = facets[index]
        // `maskClip` shapes the carousel's mask rather than the item, so the corners stay consistent
        // between the item that is peeking and the one that is centred. Clipping the item instead
        // gives every card its own round edge and the row ends up with paired corners in each gap.
        PosterFacetCard(
            facet = facet,
            modifier = Modifier.maskClip(shape),
            logoUrl = logoFor(facet.name),
            onClick = {
                onOpenList(facet.name, facet.items, ListSubtitle.Episode, ListLayout.Grid)
            }
        )
    }
}

/**
 * A facet as the poster of its first title.
 *
 * The name and the count used to be the whole tile, which made every facet look like a statistic.
 * The poster says what the facet *is* at a glance and the label says what it is called; the count
 * stays because it is the reason to open one over another.
 */
@Composable
private fun PosterFacetCard(
    facet: Facet,
    modifier: Modifier = Modifier,
    logoUrl: String? = null,
    onClick: () -> Unit
) {
    val poster = facet.items.firstOrNull()?.posterUrl
    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(onClick = onClick)
    ) {
        if (poster != null) {
            AsyncImage(
                model = poster,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(Modifier.fillMaxSize().background(M3EColor.card()))
        }
        // The scrim is what makes white text readable on an arbitrary poster. It covers the bottom
        // third rather than the whole card, so the artwork stays artwork.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.55f to Color.Transparent,
                        1f to M3EColor.artworkScrim.copy(alpha = 0.86f)
                    )
                )
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(M3ESpacing.inlineGap),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (logoUrl != null) {
                // The mark instead of the word: on a poster, a network's logo is what you
                // recognise, and a name under it was the same information at a lower signal.
                //
                // On a white plate, because most marks are transparent PNGs of white or black type
                // and a poster is neither. Without it an Apple TV+ logo on a dark poster is a
                // smudge, and a Netflix one on a bright poster is nothing at all.
                Box(
                    modifier = Modifier
                        .background(M3EColor.onArtwork.copy(alpha = 0.92f), M3EShape.chip())
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    AsyncImage(
                        model = logoUrl,
                        contentDescription = facet.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .height(20.dp)
                            .widthIn(max = 88.dp)
                    )
                }
            } else {
                Text(
                    text = facet.name,
                    style = M3EType.rowTitleStrong(),
                    color = M3EColor.onArtwork,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 100.dp)
                )
            }
            Text(
                text = "${facet.count}",
                style = M3EType.rowSupporting(),
                color = M3EColor.onArtwork.copy(alpha = 0.8f),
                textAlign = TextAlign.Center
            )
        }
    }
}


/**
 * TMDB's own recommendations for what this library loves most.
 *
 * Three seeds rather than one: a single seed drifts to one franchise, and the first row on a browse
 * screen that shows the same show five times is worse than no row. Anything already owned is
 * dropped — a recommendation you have is a chore, not a discovery.
 */
@Composable
private fun ForYouRow(
    library: List<MediaItemEntity>,
    container: AppContainer,
    onOpen: (MediaItemEntity) -> Unit
) {
    val scope = rememberCoroutineScope()
    val owned = remember(library) { library.map { it.id }.toSet() }
    val seeds = remember(library) {
        library
            .filter { it.taste.equals("Love", true) || it.state.equals("Completed", true) }
            .sortedByDescending { it.lastInteractionDate ?: "" }
            .take(3)
    }
    val results by produceState(emptyList<TMDBSearchItem>(), seeds) {
        if (seeds.isEmpty()) return@produceState
        val client = container.tmdbClient()
        val seen = linkedSetOf<String>()
        val out = mutableListOf<TMDBSearchItem>()
        for (seed in seeds) {
            val tmdbId = MediaIds.tmdbIdOf(seed.id)
            if (tmdbId <= 0) continue
            val recs = if (seed.type.contains("TV", true)) {
                client.getTVRecommendations(tmdbId).getOrNull()
            } else {
                client.getMovieRecommendations(tmdbId).getOrNull()
            }?.results.orEmpty()
            for (rec in recs) {
                val id = (if (rec.media_type == "movie") "movie_" else "tv_") + rec.id
                if (id in owned || !seen.add(id)) continue
                out.add(rec)
            }
            if (out.size >= 12) break
        }
        value = out.take(12)
    }

    if (results.isEmpty()) return
    M3ESectionHeader("For you", icon = Icons.Default.Recommend, tint = M3EColor.accent())
    Spacer(Modifier.height(M3ESpacing.tightGap))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)) {
        items(results, key = { "${it.media_type}_${it.id}" }) { result ->
            Column(modifier = Modifier.width(120.dp)) {
                M3ETonalCard(
                    onClick = {
                        val isMovie = result.media_type == "movie"
                        val mediaId = (if (isMovie) "movie_" else "tv_") + result.id
                        val item = MediaItemEntity(
                            id = mediaId,
                            title = result.title ?: result.name ?: return@M3ETonalCard,
                            overview = result.overview.orEmpty(),
                            posterUrl = TMDBClient.buildPosterUrl(result.poster_path),
                            backdropUrl = TMDBClient.buildBackdropUrl(result.backdrop_path),
                            releaseDate = result.release_date ?: result.first_air_date,
                            type = if (isMovie) "Movie" else "TV",
                            state = "Wishlist",
                            dateAdded = TrackingLogic.isoNow()
                        )
                        scope.launch {
                            container.mediaDao.upsertMedia(item)
                            container.syncManager().pushMediaUpdate(
                                mediaId = item.id,
                                type = item.type,
                                state = item.state,
                                taste = item.taste,
                                isStateChange = false,
                                dateAdded = item.dateAdded
                            )
                            onOpen(item)
                        }
                    }
                ) {
                    AsyncImage(
                        model = TMDBClient.buildPosterUrl(result.poster_path),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = result.title ?: result.name ?: "",
                    style = M3EType.rowSupporting(),
                    color = M3EColor.textMuted(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
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
