package com.vara.mediatracker.ui.m3e

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.vara.mediatracker.data.TrackingLogic
import com.vara.mediatracker.data.WatchHistoryRecorder
import com.vara.mediatracker.data.api.TMDBCastMember
import com.vara.mediatracker.data.api.TMDBClient
import com.vara.mediatracker.data.api.TMDBSearchItem
import com.vara.mediatracker.data.model.EpisodeEntity
import com.vara.mediatracker.data.model.MediaIds
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.data.sync.EpisodeEnrichment
import com.vara.mediatracker.di.AppContainer
import com.vara.mediatracker.ui.common.rememberHaptics
import com.vara.mediatracker.ui.home.HomeEligibility
import com.vara.mediatracker.ui.theme.AppTextStyles
import com.vara.mediatracker.ui.theme.LocalSemanticColors
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * How long the sheet takes to fill in, once the poster has landed.
 *
 * The poster's own travel is [M3E_DETAIL_ARRIVAL_MILLIS], shared with the shell's sheet rise; this
 * runs after it, so the order reads as: the sheet arrives carrying the poster, and then the rest of
 * the title fills in behind it.
 */
private const val REVEAL_MILLIS = 380

/**
 * One stage of the reveal: 0 before [from], 1 after [to], linear between.
 *
 * Stages rather than four animations, so they cannot drift apart — one clock, four windows.
 */
private fun revealStage(progress: Float, from: Float, to: Float): Float =
    ((progress - from) / (to - from)).coerceIn(0f, 1f)


/**
 * The detail screen, in the [M3E] vocabulary: a header, one primary action, and tabs.
 *
 * The screen used to be one column — status, taste, every season, every episode, overview,
 * details — which meant a long scroll to reach anything and no way to say what you came for.
 * Status and taste now live in a popup (they are both "how I feel about this title", and they are
 * set together), and the body is split into sections you switch between: a show has Seasons, Info
 * and Related, a film has Overview and Related.
 *
 * Every control writes through the same path the shipping screen uses: episodes and films through
 * [TrackingLogic], which owns the watched ledger and the state progression that follows from it;
 * status and taste as a DAO update plus the matching cloud push, with status moving the ledger too
 * because it bypasses the toggle.
 */
@Composable
fun M3ERoughDetail(
    item: MediaItemEntity,
    container: AppContainer,
    /** Where the poster that opened this was, when it came from one. See [HeroOrigin]. */
    heroFrom: Rect? = null,
    /** The sheet's rise, 0..1, and the height it is travelling from. See [Title]. */
    sheetRise: State<Float>? = null,
    sheetHeight: Dp = 0.dp,
    onOpenMedia: (MediaItemEntity) -> Unit = {}
) {
    val db = container.database
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()

    val isTv = remember(item.id) { item.type.contains("TV", ignoreCase = true) }
    val showKey = remember(item.id) { MediaIds.tvShowKey(item.id) }

    var live by remember(item.id) { mutableStateOf(item) }
    var season by remember(item.id) { mutableStateOf<Int?>(null) }
    var statusPickerOpen by rememberSaveable(item.id) { mutableStateOf(false) }
    var pickingSeason by rememberSaveable(item.id) { mutableStateOf(false) }
    var seasonMenu by rememberSaveable(item.id) { mutableStateOf(false) }
    var tastePickerOpen by rememberSaveable(item.id) { mutableStateOf(false) }
    // The sheet fills in rather than arriving complete: the poster lands, then the title, then the
    // controls, then the content. Only when arriving from a tile — a detail pushed on top of another
    // has nothing to fill in after.
    val reveal = remember(item.id) { Animatable(if (heroFrom == null) 1f else 0f) }
    LaunchedEffect(item.id, heroFrom) {
        if (heroFrom == null) {
            reveal.snapTo(1f)
        } else {
            reveal.snapTo(0f)
            reveal.animateTo(1f, tween(durationMillis = REVEAL_MILLIS, delayMillis = 100))
        }
    }
    var related by remember(item.id) { mutableStateOf(emptyList<TMDBSearchItem>()) }
    var cast by remember(item.id) { mutableStateOf(emptyList<TMDBCastMember>()) }
    var directors by remember(item.id) { mutableStateOf(emptyList<String>()) }
    var creators by remember(item.id) { mutableStateOf(emptyList<String>()) }

    // Re-read the library's copy, then fill in what TMDB knows. A title added from search is a
    // stub, and one that arrived through sync can be missing its metadata entirely — which is how a
    // title ends up on screen under its own id. Only fields TMDB actually returns are written, so a
    // good local value is never replaced by an empty one.
    LaunchedEffect(item.id) {
        val stored = db.mediaDao().getMediaById(item.id) ?: item
        live = stored

        val tmdbId = MediaIds.tmdbIdOf(item.id)
        if (tmdbId <= 0) return@LaunchedEffect

        val client = container.tmdbClient()
        val details = runCatching {
            if (isTv) client.getTVDetails(tmdbId) else client.getMovieDetails(tmdbId)
        }.getOrNull()?.getOrNull()

        if (details != null) {
            val enriched = stored.copy(
                title = details.title ?: details.name ?: stored.title,
                overview = details.overview?.takeIf { it.isNotBlank() } ?: stored.overview,
                posterUrl = TMDBClient.buildPosterUrl(details.poster_path) ?: stored.posterUrl,
                backdropUrl = TMDBClient.buildBackdropUrl(details.backdrop_path) ?: stored.backdropUrl,
                releaseDate = details.release_date ?: details.first_air_date ?: stored.releaseDate
            )
            if (enriched != stored) {
                db.mediaDao().upsertMedia(enriched)
                live = enriched
            }
        }

        // Episodes are not fetched on open by anything else: they arrive from sync, or from the
        // enrichment worker running later. A show added from search therefore had a Seasons tab
        // with nothing in it.
        //
        // The same TVMaze path the worker uses, so both give the same answer — including the exact
        // air instant, which TMDB's season endpoint does not carry. Only asked for when the show has
        // no episodes here at all: watched state is the user's, and this must never be a refresh.
        if (isTv) {
            val local = runCatching { db.mediaDao().getEpisodesForShow(showKey).first() }
                .getOrDefault(emptyList())
            if (local.isEmpty()) {
                EpisodeEnrichment.enrichShow(db, client, container.tvmaze, live)
            }
        }

        // A show is credited to its creators, a film to its director; TMDB keeps them in
        // different places, which is why the byline under the title reads from both.
        creators = details?.created_by?.mapNotNull { it.name }?.take(2).orEmpty()

        val credits = runCatching {
            if (isTv) client.getTVCredits(tmdbId) else client.getMovieCredits(tmdbId)
        }.getOrNull()?.getOrNull()
        if (credits != null) {
            cast = credits.cast.sortedBy { it.order }.take(15)
            directors = credits.crew
                .filter { it.job == "Director" }
                .mapNotNull { it.name }
                .distinct()
                .take(2)
        }

        related = runCatching {
            if (isTv) client.getTVRecommendations(tmdbId) else client.getMovieRecommendations(tmdbId)
        }.getOrNull()?.getOrNull()?.results
            .orEmpty()
            .filter { it.media_type == "movie" || it.media_type == "tv" }
            .take(12)
    }

    val episodeFlow = remember(item.id) {
        if (isTv) db.mediaDao().getEpisodesForShow(showKey) else flowOf(emptyList())
    }
    val episodes by episodeFlow.collectAsState(initial = emptyList())
    val ordered = remember(episodes) {
        episodes.sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))
    }
    val seasons = remember(ordered) { ordered.map { it.seasonNumber }.distinct().sorted() }
    // The Mac's rule for which season to open on, so both land on the same one.
    val openingSeason = remember(ordered) { openingSeasonFor(ordered) }
    // Falls back if the chosen season has no episodes at all, which can happen when a show is
    // re-fetched underneath it.
    val currentSeason = season?.takeIf { chosen -> seasons.contains(chosen) } ?: openingSeason
    val seasonEpisodes = remember(ordered, currentSeason) {
        ordered.filter { it.seasonNumber == currentSeason }
    }
    val nextEpisode = remember(ordered) { ordered.firstOrNull { !it.isWatched && it.seasonNumber > 0 } }

    fun setStatus(next: String) {
        val now = TrackingLogic.isoNow()
        val wasCompleted = live.state.equals("Completed", ignoreCase = true)
        val isCompleted = next.equals("Completed", ignoreCase = true)
        haptics.toggle()
        live = live.copy(state = next, lastStateChangeDate = now, lastInteractionDate = now)
        scope.launch {
            db.mediaDao().updateState(item.id, next, now)
            container.syncManager().pushMediaUpdate(
                mediaId = item.id,
                type = item.type,
                state = next,
                taste = live.taste,
                isStateChange = true,
                dateAdded = item.dateAdded
            )

            // The ledger is not the state column, so a status change has to move it too.
            val historyDao = db.watchHistoryDao()
            if (isCompleted && !wasCompleted) {
                if (!isTv) {
                    WatchHistoryRecorder.recordWatch(
                        dao = historyDao,
                        mediaId = item.id,
                        kind = WatchHistoryRecorder.KIND_MOVIE,
                        episodeId = null,
                        watchedAt = now,
                        runtimeMinutes = null
                    )
                }
                WatchHistoryRecorder.completeCycle(historyDao, item.id, now)
            } else if (!isCompleted && wasCompleted) {
                if (!isTv) WatchHistoryRecorder.voidWatch(historyDao, item.id, null, now)
                WatchHistoryRecorder.reopenCycle(historyDao, item.id, now)
            }
        }
    }

    fun setTaste(next: String) {
        val now = TrackingLogic.isoNow()
        haptics.toggle()
        live = live.copy(taste = next, lastInteractionDate = now)
        scope.launch {
            db.mediaDao().updateTaste(item.id, next, now)
            container.syncManager().pushMediaUpdate(
                mediaId = item.id,
                type = item.type,
                state = live.state,
                taste = next,
                // A taste change is not a state change; claiming otherwise would advance
                // last_state_change_date in the cloud.
                isStateChange = false,
                dateAdded = item.dateAdded
            )
        }
    }

    fun setEpisodeWatched(episode: EpisodeEntity, watched: Boolean) {
        haptics.tap()
        scope.launch {
            TrackingLogic.markEpisodeWatched(container, episode, watched) { updated -> live = updated }
        }
    }

    fun setSeasonWatched(watched: Boolean) {
        if (seasonEpisodes.isEmpty()) return
        haptics.toggle()
        scope.launch {
            TrackingLogic.markSeasonWatched(
                container = container,
                showId = showKey,
                currentSeason,
                seasonEpisodes,
                watched
            ) { updated -> live = updated }
        }
    }

    fun toggleMovie() {
        haptics.toggle()
        scope.launch {
            TrackingLogic.toggleMovieWatched(container, live) { updated -> live = updated }
        }
    }

    /** A related title opens if it is already in the library, and is added first if it is not. */
    fun openRelated(result: TMDBSearchItem) {
        val isMovie = result.media_type == "movie"
        val mediaId = (if (isMovie) "movie_" else "tv_") + result.id
        scope.launch {
            val existing = db.mediaDao().getMediaById(mediaId)
            if (existing != null) {
                onOpenMedia(existing)
                return@launch
            }
            val title = result.title ?: result.name ?: return@launch
            val added = MediaItemEntity(
                id = mediaId,
                title = title,
                overview = result.overview.orEmpty(),
                posterUrl = TMDBClient.buildPosterUrl(result.poster_path),
                backdropUrl = TMDBClient.buildBackdropUrl(result.backdrop_path),
                releaseDate = result.release_date ?: result.first_air_date,
                type = if (isMovie) "Movie" else "TV",
                state = "Wishlist",
                dateAdded = TrackingLogic.isoNow()
            )
            db.mediaDao().upsertMedia(added)
            container.syncManager().pushMediaUpdate(
                mediaId = added.id,
                type = added.type,
                state = added.state,
                taste = added.taste,
                isStateChange = false,
                dateAdded = added.dateAdded
            )
            onOpenMedia(added)
        }
    }

    val sections = remember(isTv) {
        if (isTv) {
            listOf(DetailSection.Seasons, DetailSection.Info, DetailSection.Related)
        } else {
            listOf(DetailSection.Overview, DetailSection.Related)
        }
    }

    // Swipe between the sections, the same way the Hub's tabs move. They are peers, so moving
    // sideways is what the hand expects, and the chips stay as the labels for where you are. The
    // pager state is saveable, so leaving for another title and coming back keeps the section.
    val pagerState = rememberPagerState(pageCount = { sections.size })

    // The Mac's palette, which is what makes this screen feel like *this* title rather than the
    // app. Per field: the muted tone is the better wash but is absent on the rows an older Mac path
    // wrote, so it falls back to the primary rather than to nothing.
    val rawAccent = themeColor(live.themeColorHex)
    // Adjusted for the scheme, the way the Mac adjusts it: the same poster reads differently on a
    // white page and a black one, and a colour lifted from artwork is usually neither vivid enough
    // for dark nor deep enough for light to carry text on its own.
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val accentColor = rawAccent?.let { highContrastAccent(it, isDark) }

    // The poster's colour becomes the screen's accent, rather than a decoration on top of the
    // palette's. Everything that reads `M3EColor.accent()` — the eyebrow, the selected chips, the
    // progress figures, the primary action — is this colour, so the detail is themed *by* the title
    // instead of merely tinted near it. Falls back to the app palette when the Mac has no colour.
    val baseScheme = MaterialTheme.colorScheme
    val detailScheme = if (accentColor == null) {
        baseScheme
    } else {
        baseScheme.copy(
            primary = accentColor,
            onPrimary = if (accentColor.luminance() > 0.5f) Color.Black else Color.White,
            primaryContainer = accentColor.copy(alpha = 0.22f),
            // The page itself leans towards the title: a tenth of the poster's colour mixed into
            // the palette's background. Enough to read as "this title", not enough to fight the
            // user's chosen palette.
            // The page tint uses the *raw* colour — a background is not text — and takes more of it
            // on a dark page, where a little colour disappears into the black.
            background = mix(rawAccent, baseScheme.background, if (isDark) 0.14f else 0.10f)
        )
    }

    // The header stays put and only what is under the tabs slides. A pager carrying the whole page
    // moved the title, the poster and the controls sideways with it, which is not what a tab means:
    // the section changes, not the title.
    // Nothing to mark yet is not the same as nothing left to watch. A show whose next episode has
    // not aired, and a film that has not come out, both used to read "Everything watched" and grey
    // out — wrong twice over, because nothing is watched and there is nothing you could mark. A date
    // is the honest thing to put in its place.
    //
    // The test is the resolved instant, not the bare air date: a date parses to midnight, so today's
    // episode would read as already aired and offer to be marked.
    //
    // Declared here rather than inside the action because the compact bar shows the same answer in
    // miniature, and two copies of this rule would eventually disagree.
    // The poster's grow, hoisted out of the tab it is drawn in.
    //
    // It lived inside `Title`, which now sits in each tab's scroller — so scrolling it out of view or
    // switching tabs disposed it, and `remember` came back empty and replayed the whole animation.
    // The poster grew again every time you looked at it. Hoisted, it plays once, when the screen
    // opens, and never again.
    val heroGrow = remember { Animatable(if (heroFrom == null) 1f else 0f) }
    val heroPlayed = remember { mutableStateOf(false) }

    val now = Date()
    val upcoming = nextEpisode?.takeIf {
        HomeEligibility.episodeInstant(live, it)?.after(now) == true
    }
    val upcomingAt = upcoming?.let { HomeEligibility.episodeInstant(live, it) }
    val unreleased = isoDay(live.releaseDate)?.takeIf {
        it.isAfter(LocalDate.now()) && !live.state.equals("Completed", ignoreCase = true)
    }

    // The top section folds to a title row as you read, and the fold is safe here for one reason:
    // the header is *outside* the scroller. Its height cannot change the scroll offset that drives
    // it, so there is no loop to oscillate against — which is what every version inside a list did.
    //
    // The fold consumes the scroll while it runs, so the section closes first and the content only
    // starts moving once it has. Two motions at once is what reads as broken.
    var collapse by remember { mutableFloatStateOf(0f) }
    var expandedHeight by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val collapsePx = remember(density) { with(density) { COLLAPSE_DISTANCE.toPx() } }
    val collapseConnection = remember(collapsePx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                if (delta == 0f) return Offset.Zero
                val next = (collapse - delta / collapsePx).coerceIn(0f, 1f)
                val used = (next - collapse) * collapsePx
                collapse = next
                return Offset(0f, used)
            }

            // Releasing halfway is not a state the header has.
            //
            // It is a section or a title row, never a bit of both — so on release the fold finishes
            // the way it was already going. Nearest end wins, which is what makes it feel magnetic
            // rather than sticky: past halfway it commits, short of it it lets go. The fling is
            // consumed while it snaps, so the list does not also move under the animation.
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (collapse <= 0f || collapse >= 1f) return Velocity.Zero
                animate(
                    initialValue = collapse,
                    targetValue = if (collapse >= 0.5f) 1f else 0f,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                ) { value, _ -> collapse = value }
                return available
            }
        }
    }

    MaterialTheme(colorScheme = detailScheme) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(M3EColor.page())
            .statusBarsPadding()
    ) {
        // Fixed and shared. A header that scrolls would have to live in each tab's scroller, and
        // each tab scrolls on its own — so swiping between them would move the top, because there
        // would be no single top to move. It folds instead, which it can do from here.
        Column(modifier = Modifier.padding(horizontal = M3ESpacing.page)) {
        // Measured unbounded, so the height it reports is the content's own and not the height this
        // box is deciding. Measuring a value that is also an output of the same layout is what
        // jittered the calendar; here the box is outside the scroller as well, so there is nothing
        // for it to chase.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // Wraps its content until there is a measurement to fold with.
                //
                // The height comes from measuring this box's own child, so on the first frames there
                // is no number yet — and a box told to be zero tall clips its contents away, which is
                // why the poster vanished entirely rather than merely folding. Wrapping first means
                // the content is on screen, the measurement lands, and the fold takes over from
                // there.
                .then(
                    if (expandedHeight > 0f) {
                        Modifier.height(
                            with(density) {
                                val collapsed = COLLAPSED_HEIGHT.toPx()
                                (collapsed + (expandedHeight - collapsed) * (1f - collapse)).toDp()
                            }
                        )
                    } else {
                        Modifier
                    }
                )
                .clipToBounds()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(unbounded = true)
                    .onGloballyPositioned {
                        if (collapse == 0f && it.size.height > 0) {
                            expandedHeight = it.size.height.toFloat()
                        }
                    }
                    // Faded the whole way, and blurred only while it is in motion.
                    //
                    // The fade is what actually takes the section away, so it runs from the first
                    // pixel of the drag to the last. The blur is a transition effect and nothing
                    // else: it peaks halfway through and returns to zero as the section finishes
                    // leaving, because blurring something that is already gone is work for nothing.
                    .graphicsLayer { alpha = 1f - collapse }
                    .blur(BLUR_MAX * (4f * collapse * (1f - collapse)))
            ) {
    Spacer(Modifier.height(M3ESpacing.tightGap))

    val byline = when {
        isTv && creators.isNotEmpty() -> "Created by ${creators.joinToString(", ")}"
        !isTv && directors.isNotEmpty() -> "Directed by ${directors.joinToString(", ")}"
        else -> null
    }
    // All of it beside the poster. In the controls row, status takes 70% of the width,
    // taste 25%, and the last 5% is left as space: a reaction is a glyph and does not need
    // the room a state does, but the tenth that was reserved for nothing read as a gap.
    Title(
        item = live,
        byline = byline,
        heroFrom = heroFrom,
        reveal = reveal,
        heroGrow = heroGrow,
        heroPlayed = heroPlayed,
        sheetRise = sheetRise,
        sheetHeight = sheetHeight
    ) {
        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(M3ESpacing.tightGap)
        ) {
            PickerPill(
                label = "Status",
                value = live.state,
                tint = statusTint(live.state),
                modifier = Modifier.weight(0.7f).fillMaxHeight(),
                onClick = { statusPickerOpen = true }
            )
            TastePill(
                taste = live.taste,
                modifier = Modifier.weight(0.25f).fillMaxHeight(),
                onClick = { tastePickerOpen = true }
            )
            Spacer(Modifier.weight(0.05f))
        }
    }
    Spacer(Modifier.height(M3ESpacing.cardGap))

    // A Column, not a Box: this is a spacer, an action and a row of chips, and a Box draws
    // its children at the same offset — which is what put the watch button on top of the
    // section chips.
    Column(modifier = Modifier.graphicsLayer { alpha = revealStage(reveal.value, 0.4f, 0.85f) }) {
    if (upcoming != null) {
        // One line, and no "Upcoming": the clock says when, which is the whole message. The
        // word only repeated what the glyph and the position already had.
        UpcomingNotice(
            icon = Icons.Default.Schedule,
            text = listOfNotNull(
                "S${upcoming.seasonNumber} E${upcoming.episodeNumber}",
                landingLabel(upcoming.airDate ?: upcoming.airstamp, upcomingAt)
            ).joinToString(" · ")
        )
    } else if (unreleased != null) {
        // Same shape for a film, and for a show with no episodes at all — where the first
        // air date is the date that matters. No time is known for either, so it is just the
        // day.
        UpcomingNotice(
            icon = Icons.Default.Schedule,
            text = landingLabel(live.releaseDate, null).orEmpty()
        )
    } else if (isTv) {
        PrimaryAction(
            label = nextEpisode
                ?.let { "Mark S${it.seasonNumber} · E${it.episodeNumber} watched" }
                ?: "Everything watched",
            icon = Icons.Default.Check,
            enabled = nextEpisode != null,
            onClick = { nextEpisode?.let { setEpisodeWatched(it, true) } }
        )
    } else {
        val watched = live.state.equals("Completed", ignoreCase = true)
        PrimaryAction(
            label = if (watched) "Watched" else "Mark watched",
            icon = if (watched) Icons.Default.Check else Icons.Default.Bookmark,
            enabled = true,
            onClick = { toggleMovie() }
        )
    }
    }
            }
            // What it folds into: the title, and the one action worth reaching. Faded in as the
            // section fades out, so it reads as the same words moving rather than a swap.
            CompactTitleRow(
                title = live.title,
                nextEpisode = nextEpisode,
                upcoming = upcoming,
                // It waits for the header to be most of the way gone.
                //
                // Fading in from the first pixel meant the title row sat over the poster for the
                // first half of the fold — both visible, one on top of the other. Holding it back
                // until the header has faded to a third turns the overlap into a hand-off: by the
                // time this appears there is nothing behind it to obscure.
                visible = ((collapse - 0.5f) * 2f).coerceIn(0f, 1f),
                onMarkWatched = { nextEpisode?.let { setEpisodeWatched(it, true) } }
            )
        }
            Spacer(Modifier.height(M3ESpacing.cardGap))
            // The chips, always here. They are the only way to reach the other two tabs,
            // and a fixed top section is what makes "frozen" true rather than nearly true.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(M3ESpacing.tightGap),
                    verticalArrangement = Arrangement.spacedBy(M3ESpacing.tightGap)
                ) {
                    sections.forEachIndexed { index, candidate ->
                        FilterChip(
                            selected = pagerState.currentPage == index,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                            label = { Text(candidate.label, style = M3EType.action()) },
                            shape = M3EShape.chip(),
                            // Filled when selected, not tinted.
                            //
                            // The default is the accent at container alpha, which on a light palette
                            // is a faint wash of the page — you could not tell which tab you were on.
                            // Filled with the accent and labelled on it, the current one is
                            // unambiguous in both modes, and the accent is the poster's own colour.
                            border = if (pagerState.currentPage == index) {
                                // The tint alone is what was failing in light mode: the accent at
                                // container alpha is a wash of the page, and with no border and a
                                // muted label there was nothing crisp to read. Keeping the tint and
                                // adding a definite border plus the accent in the label gives the
                                // selected chip an edge, which is what "distinct" needs — not a
                                // flood of colour.
                                FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = true,
                                    borderColor = Color.Transparent,
                                    selectedBorderColor = M3EColor.accent(),
                                    selectedBorderWidth = 1.5.dp
                                )
                            } else {
                                null
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = M3EColor.accent().copy(alpha = 0.22f),
                                selectedLabelColor = M3EColor.accent()
                            )
                        )
                    }
                }
        }

        // Only this slides. The top section above it is fixed and shared, which is what keeps the
        // top from moving when the tabs change — each tab scrolls on its own, so a header inside one
        // would be a different header in each.
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .graphicsLayer { alpha = revealStage(reveal.value, 0.5f, 1f) }
        ) { page ->
            val section = sections.getOrElse(page) { sections.first() }
            // A LazyColumn, not a grid.
            //
            // The grid's `stickyHeader` exists and compiles and does not pin — chips, headings and
            // the bar all scrolled away with the content. A column's sticky headers are the
            // well-trodden path, and everything here was already full-width except the Related
            // posters, which are now rows of three built where they are used.
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = M3ESpacing.page)
                    .nestedScroll(collapseConnection),
                verticalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)
            ) {
                // The bar carries the name from the start, and that is deliberate.
                //
                // Gating its height on "the section has scrolled away" shook the screen — the bar
                // growing from nothing moved the content, which brought the section back, which
                // shrank the bar again. Gating only its alpha left the space reserved and empty,
                // which is a blank strip above the poster. Showing the name in it is the one version
                // that is neither: no layout change, and nothing empty.
        when (section) {
            DetailSection.Seasons -> {
                // The heading and its button pin, so the season you are reading is never a
                // question. The picker under it does not: it is set once a visit, and pinning it too
                // would leave three pinned rows on a phone.
                stickyHeader(key = "season") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(M3EColor.page())
                            .padding(vertical = M3ESpacing.tightGap)
                    ) {
                        val allWatched =
                            seasonEpisodes.isNotEmpty() && seasonEpisodes.all { it.isWatched }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // The heading is the picker.
                            //
                            // It already says which season you are in, so tapping it to change it is
                            // the most direct thing there is, and the caret is what says it is
                            // tappable. The pills it opens are the same ones that used to sit on the
                            // page — they were not the problem, the row they occupied was, and a
                            // show with thirty-one seasons turned that row into a scrubber.
                            // A show with one season has nothing to pick, so the heading stays a
                            // heading: no caret, and nothing to tap. Offering a picker for a list of
                            // one is a control that can only disappoint.
                            val canPickSeason = seasons.size > 1
                            Row(
                                modifier = Modifier
                                    .clip(M3EShape.chip())
                                    .then(
                                        if (canPickSeason) {
                                            Modifier.clickable {
                                                haptics.tap()
                                                pickingSeason = true
                                            }
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .padding(horizontal = M3ESpacing.tightGap, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Season $currentSeason",
                                    style = M3EType.sectionTitle(),
                                    color = M3EColor.text()
                                )
                                if (canPickSeason) {
                                    Spacer(Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.ExpandMore,
                                        contentDescription = "Change season",
                                        tint = M3EColor.accent(),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            // A split button: the action the heading is about, and the other
                            // direction of it in the menu. The primary is what you mean nine times
                            // in ten, so it gets the face; "the opposite of this" is a menu item,
                            // not a second button competing with it.
                            Box {
                                SplitButtonLayout(
                                    leadingButton = {
                                        Button(onClick = { setSeasonWatched(!allWatched) }) {
                                            Text(
                                                text = if (allWatched) "Clear season" else "Watch season",
                                                style = M3EType.action()
                                            )
                                        }
                                    },
                                    trailingButton = {
                                        FilledTonalIconButton(onClick = { seasonMenu = true }) {
                                            Icon(
                                                imageVector = Icons.Default.ArrowDropDown,
                                                contentDescription = "More season actions"
                                            )
                                        }
                                    }
                                )
                                DropdownMenu(
                                    expanded = seasonMenu,
                                    onDismissRequest = { seasonMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Mark all watched") },
                                        onClick = {
                                            seasonMenu = false
                                            setSeasonWatched(true)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Clear all") },
                                        onClick = {
                                            seasonMenu = false
                                            setSeasonWatched(false)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                if (seasonEpisodes.isEmpty()) {
                    item {
                        M3ETonalCard(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "No episodes here yet.",
                                style = M3EType.rowSupporting(),
                                color = M3EColor.textMuted(),
                                modifier = Modifier.padding(M3ESpacing.cardGap)
                            )
                        }
                    }
                } else {
                    items(
                        items = seasonEpisodes,
                        key = { it.uniqueId }
                    ) { episode ->
                        EpisodeRow(
                            episode = episode,
                            watched = episode.isWatched,
                            onToggle = { setEpisodeWatched(episode, it) }
                        )
                    }
                }
            }

            DetailSection.Info, DetailSection.Overview -> {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)) {
                        if (live.overview.isNotBlank()) {
                            M3ESectionHeader("Overview")
                            Text(
                                text = live.overview,
                                style = M3EType.rowSupporting(),
                                color = M3EColor.textMuted()
                            )
                        }
                        M3ESectionHeader("Details")
                        M3ETonalCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(vertical = M3ESpacing.tightGap)) {
                                // Read once: `live` is a delegated property, so its fields cannot
                                // be smart-cast after a null check.
                                val orderedCount = ordered.size
                                val watchedCount = ordered.count { it.isWatched }
                                val genres = live.genres
                                val network = live.network
                                val released = live.releaseDate
                                if (orderedCount > 0) {
                                    DetailRow("Progress", "$watchedCount / $orderedCount episodes")
                                }
                                if (!genres.isNullOrBlank()) {
                                    DetailRow("Genres", genres, lines = 2)
                                }
                                if (!network.isNullOrBlank()) DetailRow("Network", network)
                                if (!released.isNullOrBlank()) {
                                    DetailRow("Released", indianDate(released) ?: released)
                                }
                                DetailRow("Added", indianDate(live.dateAdded) ?: "—", divider = false)
                            }
                        }

                        if (cast.isNotEmpty()) {
                            M3ESectionHeader("Cast")
                            // A strip, because a cast is browsed sideways: you are looking for a
                            // face, not reading a list, and a horizontal run shows far more of them
                            // in the same height.
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)) {
                                items(cast.take(15), key = { it.id }) { member ->
                                    CastCard(member)
                                }
                            }
                        }
                    }
                }
            }

            DetailSection.Related -> {
                item {
                    M3ESectionHeader("More like this")
                }
                if (related.isEmpty()) {
                    item {
                        M3ETonalCard(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Nothing to suggest yet.",
                                style = M3EType.rowSupporting(),
                                color = M3EColor.textMuted(),
                                modifier = Modifier.padding(M3ESpacing.cardGap)
                            )
                        }
                    }
                } else {
                    // Rows of three, built here rather than by the grid. A row of weighted cards
                    // each carrying its own aspect ratio is the same shape the grid gave them, and
                    // it is what a column can hold.
                    related.chunked(3).forEach { row ->
                        item(key = "related_${row.first().media_type}_${row.first().id}") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(M3ESpacing.inlineGap)
                            ) {
                                row.forEach { result ->
                                    RelatedCard(
                                        result = result,
                                        modifier = Modifier.weight(1f),
                                        onClick = { openRelated(result) }
                                    )
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }

        // Clears the floating navigation bar: at 48 the last row sat underneath it.
        item { Spacer(Modifier.height(LocalNavClearance.current)) }
            }
        }
    }
    }

    if (pickingSeason) {
        // The shared picker, so all three dialogs are the same shape by construction. It had its own
        // layout before, and that is exactly how two dialogs that look alike drift apart.
        ChoicePicker(
            title = "Season",
            // The numbers alone. The title says "Season", so repeating it in every cell is what made
            // them wide enough to truncate at "Season 10".
            options = seasons.map { it.toString() },
            selected = currentSeason.toString(),
            tintFor = { M3EColor.accent() },
            onSelect = { label ->
                label.toIntOrNull()?.let {
                    // The dialog closes on the tap, so the confirmation has to be felt rather than
                    // seen — there is no moment afterwards in which to show anything.
                    haptics.toggle()
                    season = it
                }
            },
            onDismiss = { pickingSeason = false },
            columns = 5
        )
    }

    if (statusPickerOpen) {
        ChoicePicker(
            title = "Status",
            options = TrackingStatuses,
            selected = live.state,
            tintFor = { statusTint(it) },
            onSelect = { if (!it.equals(live.state, true)) setStatus(it) },
            onDismiss = { statusPickerOpen = false }
        )
    }

    if (tastePickerOpen) {
        ChoicePicker(
            title = "Taste",
            options = listOf("None", "Dislike", "Like", "Love"),
            selected = live.taste,
            tintFor = { tasteTint(it) },
            onSelect = { if (!it.equals(live.taste, true)) setTaste(it) },
            onDismiss = { tastePickerOpen = false }
        )
    }
}

private enum class DetailSection(val label: String) {
    Seasons("Seasons"),
    Info("Info"),
    Overview("Overview"),
    Related("Related")
}

/**
 * A labelled pill showing the current value, in the colour that value means.
 *
 * The tint does the work: a Completed title's pill is green, a Dropped one is red, a loved one
 * carries the taste colour — so the state is legible before the word is read.
 */
@Composable
private fun PickerPill(
    label: String,
    value: String,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = M3EShape.chip(),
        color = tint.copy(alpha = 0.18f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.5f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = M3ESpacing.inlineGap, vertical = M3ESpacing.tightGap),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = label,
                style = M3EType.rowSupporting(),
                color = M3EColor.textMuted(),
                maxLines = 1
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = value,
                    style = M3EType.rowTitle(),
                    fontWeight = FontWeight.SemiBold,
                    color = tint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = tint
                )
            }
        }
    }
}

/**
 * The taste as a single emoji.
 *
 * A reaction is recognised, not read — nobody needs the word "Like" next to a thumbs-up — which is
 * what lets it be small beside a status pill rather than competing with it for the same room.
 * Nothing chosen shows the neutral face held back, so it reads as set to nothing rather than as
 * broken.
 */
@Composable
private fun TastePill(
    taste: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val unset = taste.equals("None", ignoreCase = true)
    val tint = tasteTint(taste)
    Surface(
        onClick = onClick,
        shape = M3EShape.chip(),
        color = if (unset) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            tint.copy(alpha = 0.18f)
        },
        border = BorderStroke(
            1.dp,
            if (unset) MaterialTheme.colorScheme.outlineVariant else tint.copy(alpha = 0.5f)
        ),
        modifier = modifier
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(
                text = tasteEmoji(taste),
                fontSize = 28.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    // Width rather than padding, so the glyph is centred on the pill: an emoji
                    // carries its own side bearing, and padding around it only centres that box.
                    .fillMaxWidth()
                    .alpha(if (unset) 0.4f else 1f)
            )
        }
    }
}

/** The glyph for a reaction: the face for it, and a neutral one when there is none. */
private fun tasteEmoji(taste: String): String = when {
    taste.equals("Love", ignoreCase = true) -> "\uD83D\uDE0D"
    taste.equals("Like", ignoreCase = true) -> "\uD83D\uDC4D"
    taste.equals("Dislike", ignoreCase = true) -> "\uD83D\uDC4E"
    else -> "\uD83D\uDE10"
}

/** One popup for one decision: a row of chips, applied as it is chosen. */
@Composable
private fun ChoicePicker(
    title: String,
    options: List<String>,
    selected: String,
    tintFor: @Composable (String) -> Color,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    /**
     * When set, the options are drawn as a grid of that many columns instead of wrapping chips.
     *
     * For a list of numbers. A chip has to carry its own meaning, so a season chip said "Season 10"
     * — and that is what made it too wide for a third of a dialog and too long to line up. With the
     * dialog titled "Season", a cell only needs the number, and a number is small enough to be a
     * cell in a table rather than a chip in a row.
     */
    columns: Int? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            // Capped and scrollable here rather than in one caller, so a list too long for the dialog
            // scrolls instead of being cut off — and so every picker has the same geometry. The
            // season list was showing up to season 22 and losing the rest, with nothing to say there
            // was more.
            Box(
                modifier = Modifier
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                val pick: (String) -> Unit = {
                    onSelect(it)
                    // Choosing is the whole interaction. A Done button asks you to confirm a decision
                    // you made a moment ago and cannot have meant differently.
                    onDismiss()
                }
                if (columns != null) {
                    NumberGrid(options, selected, tintFor, pick, columns)
                } else {
                    ChoiceRow(options, selected, tintFor, pick)
                }
            }
        },
        // Empty on purpose: selecting closes it, and so does tapping outside.
        confirmButton = {}
    )
}

/**
 * Numbers as a table.
 *
 * Roomier than a chip row on purpose: a cell is a target you aim at, and a number you are hunting for
 * — season 24 of 31 — is found by position, so the position has to be legible. A larger figure in a
 * square cell with real air around it is a lookup; a row of small chips is a search.
 */
@Composable
private fun NumberGrid(
    options: List<String>,
    selected: String,
    tintFor: @Composable (String) -> Color,
    onSelect: (String) -> Unit,
    columns: Int
) {
    Column(verticalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)) {
        options.chunked(columns).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)
            ) {
                row.forEach { option ->
                    val isSelected = option.equals(selected, ignoreCase = true)
                    val tint = tintFor(option)
                    Surface(
                        onClick = { onSelect(option) },
                        shape = M3EShape.card(),
                        color = if (isSelected) tint.copy(alpha = 0.20f) else M3EColor.card(),
                        contentColor = if (isSelected) tint else M3EColor.text(),
                        border = if (isSelected) BorderStroke(2.dp, tint) else null,
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = option,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** The app's six tracking states, raw values — what sync and the Mac both read. */
private val TrackingStatuses = listOf(
    "Wishlist", "Active", "Completed", "On Hold", "Dropped", "Re-watching"
)

/** Poster, eyebrow and the editorial title — the display heading this app uses everywhere. */
@Composable
private fun Title(
    item: MediaItemEntity,
    byline: String? = null,
    heroFrom: Rect? = null,
    reveal: Animatable<Float, *>? = null,
    heroGrow: Animatable<Float, *>? = null,
    heroPlayed: MutableState<Boolean>? = null,
    titleAlpha: Float = 1f,
    sheetRise: State<Float>? = null,
    sheetHeight: Dp = 0.dp,
    below: @Composable (() -> Unit)? = null
) {
    // The poster grows from where the tapped tile was. Not a shared element — the tile is already
    // gone by the time this composes — so the geometry is carried across instead, and the poster is
    // drawn at the old rectangle and animated to its own.
    //
    // Both rectangles are in root coordinates, so the difference between them is exactly the offset
    // and scale to start from. Where the target is not known yet (first frame) or the origin is not
    // this title's, the animation is skipped and the poster simply appears.
    var target by remember { mutableStateOf(Rect.Zero) }
    val grow = heroGrow ?: remember { Animatable(1f) }
    // Only ever plays once, and the flag that says so lives above the tabs rather than in here —
    // otherwise leaving and returning to the tab would start it again.
    LaunchedEffect(heroFrom, target) {
        val played = heroPlayed?.value == true
        if (heroFrom != null && target != Rect.Zero && target.width > 0f && !played) {
            heroPlayed?.value = true
            grow.snapTo(0f)
            grow.animateTo(1f, tween(durationMillis = M3E_DETAIL_ARRIVAL_MILLIS))
        } else if (grow.value == 0f) {
            grow.snapTo(1f)
        }
    }
    // Everything is beside the poster: the heading, and the row of controls under it.
    Row(verticalAlignment = Alignment.Top) {
        AsyncImage(
            model = item.posterUrl,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(132.dp)
                .aspectRatio(2f / 3f)
                .onGloballyPositioned { target = it.boundsInRoot() }
                .graphicsLayer {
                    val origin = heroFrom
                    val p = grow.value
                    if (origin != null && p < 1f && target.width > 0f) {
                        // Read in the draw phase, so the animation redraws without recomposing.
                        scaleX = 1f + (origin.width / target.width - 1f) * (1f - p)
                        scaleY = 1f + (origin.height / target.height - 1f) * (1f - p)
                        translationX = (origin.left - target.left) * (1f - p)
                        // The sheet's travel is cancelled out of the flight, so the poster moves in
                        // screen coordinates and the sheet rises to meet it — rather than the poster
                        // being carried down a screen and flying back up.
                        translationY = (origin.top - target.top) * (1f - p) -
                            (1f - (sheetRise?.value ?: 1f)) * sheetHeight.toPx()
                    }
                }
                .clip(M3EShape.card())
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        )
        Spacer(Modifier.width(M3ESpacing.cardGap))
        // The poster above is exempt on purpose: it is the thing travelling, so it has to be there
        // from the start. Everything beside and below it fills in.
        Column(
            modifier = Modifier
                .weight(1f)
                // Two fades: the reveal as the sheet arrives, and the hand-off to the compact bar as
                // the section leaves. The title beside the poster is what becomes the title at the
                // top, so they cross rather than one replacing the other.
                .graphicsLayer {
                    alpha = revealStage(reveal?.value ?: 1f, 0f, 0.45f) * titleAlpha
                }
        ) {
            Text(
                text = item.title,
                style = M3EType.screenTitle(),
                color = M3EColor.text(),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            if (byline != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = byline,
                    style = M3EType.rowSupporting(),
                    color = M3EColor.accent(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(M3ESpacing.tightGap))
            Text(
                text = buildString {
                    append(item.type)
                    item.releaseDate?.take(4)?.let { if (it.isNotBlank()) append(" · ").append(it) }
                },
                style = M3EType.rowSupporting(),
                color = M3EColor.textMuted()
            )
            if (below != null) {
                Spacer(Modifier.height(M3ESpacing.cardGap))
                Box(modifier = Modifier.graphicsLayer { alpha = revealStage(reveal?.value ?: 1f, 0.25f, 0.7f) }) {
                    below()
                }
            }
        }
    }
}

/**
 * What stands in for the action when there is nothing to act on yet.
 *
 * A card rather than a disabled button: a greyed-out button says "you cannot do this", and the point
 * here is the opposite — there is nothing to do *because* it has not happened. The accent and the
 * calendar glyph carry it instead.
 */
@Composable
private fun UpcomingNotice(
    icon: ImageVector,
    text: String
) {
    M3ETonalCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(M3ESpacing.cardGap),
            verticalAlignment = Alignment.CenterVertically,
            // Centred as a group, and built the same way as the action it replaces: same glyph size,
            // same gap, same type role. The two are the same element in different states, so they
            // should not sit differently — the button only looked right because a Button centres its
            // own content for free.
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = M3EColor.accent(),
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(M3ESpacing.tightGap))
            Text(
                text = text,
                style = M3EType.action(),
                color = M3EColor.text(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * The top section in miniature: the name, and the one thing you might want to do.
 *
 * Marking the next episode is the reason half these visits happen, so it rides in the folded row
 * rather than being scrolled out of reach — a tick with the episode when there is one to mark, a
 * clock with the episode when it has not aired, and nothing when there is nothing to say. The same
 * `upcoming` rule decides this and the big action, so the two cannot disagree.
 */
@Composable
private fun CompactTitleRow(
    title: String,
    nextEpisode: EpisodeEntity?,
    upcoming: EpisodeEntity?,
    visible: Float,
    onMarkWatched: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The layer comes before the background, and that order is the whole of it.
            //
            // Modifiers apply outside-in, so a background written first is painted by a node *outside*
            // the alpha layer — the fill stayed at full opacity while only its contents faded, and at
            // rest that was a page-coloured strip sitting over the header. Inside the layer, the fill
            // fades with the row, which is what makes it opaque at the end and absent at the start.
            .graphicsLayer { alpha = visible }
            // Opaque at the end, because it sits over the section that is fading out. Without a
            // background the blurred content shows through the row replacing it, and the two read as
            // one muddled thing instead of a hand-off.
            .background(M3EColor.page())
            .padding(vertical = M3ESpacing.tightGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = M3EType.rowTitle(),
            fontWeight = FontWeight.SemiBold,
            color = M3EColor.text(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(M3ESpacing.tightGap))
        val waiting = upcoming != null
        val episode = upcoming ?: nextEpisode
        if (episode != null) {
            Surface(
                onClick = { if (!waiting) onMarkWatched() },
                enabled = !waiting,
                shape = M3EShape.chip(),
                color = M3EColor.accent().copy(alpha = if (waiting) 0.12f else 0.18f),
                contentColor = M3EColor.accent()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = M3ESpacing.rowGap, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (waiting) Icons.Default.Schedule else Icons.Default.Check,
                        contentDescription = if (waiting) {
                            "Next episode not aired yet"
                        } else {
                            "Mark S${episode.seasonNumber} E${episode.episodeNumber} watched"
                        },
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "S${episode.seasonNumber} E${episode.episodeNumber}",
                        style = M3EType.action()
                    )
                }
            }
        }
    }
}

/** How far the top section blurs as it folds. Enough to read as depth, not as damage. */
private val BLUR_MAX = 10.dp

/** How far the finger travels to fold the top section. */
private val COLLAPSE_DISTANCE = 96.dp

/** What the folded row is worth. A constant, because the fold needs a number to move between. */
private val COLLAPSED_HEIGHT = 52.dp

/** One emphasised action, deliberately the largest element on the screen. */
@Composable
private fun PrimaryAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    tint: Color? = null,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = if (tint != null) {
            ButtonDefaults.buttonColors(containerColor = tint)
        } else {
            ButtonDefaults.buttonColors()
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(M3ESpacing.rowMinHeight)
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(M3ESpacing.tightGap))
        Text(text = label, style = M3EType.action())
    }
}

/**
 * A row of chips wrapping to as many lines as it needs.
 *
 * Each option carries its own tint, so a selected chip says what it means — a finished title reads
 * green, a dropped one red, a loved one the taste colour — rather than every selection looking
 * alike. That is Material's colour tactic: colour as information, not decoration.
 */
@Composable
private fun ChoiceRow(
    options: List<String>,
    selected: String,
    tintFor: @Composable (String) -> Color,
    onSelect: (String) -> Unit
) {
    // Dynamic, with a floor.
    //
    // Fixed columns were wrong twice over: three of them made "Season 10" wider than its third of the
    // dialog, so it ellipsised, and the count that suits "1" does not suit "Re-watching". A `FlowRow`
    // sizes each chip to its own label and wraps where it runs out — dynamic, and nothing truncates.
    //
    // The floor is what keeps it from looking accidental. Without it, a list of one- and two-digit
    // seasons packs tight and a list with "Re-watching" in it goes ragged; with it, every chip is at
    // least a comfortable tap and the rows read as rows whatever the labels are.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(M3ESpacing.tightGap),
        verticalArrangement = Arrangement.spacedBy(M3ESpacing.tightGap)
    ) {
        options.forEach { option ->
            val isSelected = option.equals(selected, ignoreCase = true)
            val tint = tintFor(option)
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(option) },
                label = {
                    Text(
                        text = option,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isSelected) tint else M3EColor.textMuted(),
                        maxLines = 1,
                        textAlign = TextAlign.Center
                    )
                },
                shape = M3EShape.chip(),
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Color.Transparent,
                    labelColor = M3EColor.textMuted(),
                    selectedContainerColor = tint.copy(alpha = 0.18f),
                    selectedLabelColor = tint,
                    selectedLeadingIconColor = tint
                ),
                modifier = Modifier.widthIn(min = 76.dp)
            )
        }
    }
}

/** Status colour by meaning: what the state *is*, not just that it is selected. */
@Composable
private fun statusTint(state: String): Color = when {
    state.equals("Completed", true) -> M3EColor.positive()
    state.equals("Active", true) -> M3EColor.accent()
    state.equals("Re-watching", true) -> M3EColor.accent()
    state.equals("On Hold", true) -> M3EColor.warning()
    state.equals("Dropped", true) -> MaterialTheme.colorScheme.error
    else -> M3EColor.textMuted()
}

/** Taste colour, matching the reaction tones the real detail uses. */
@Composable
private fun tasteTint(taste: String): Color {
    val semantic = LocalSemanticColors.current
    return when {
        taste.equals("Love", true) -> semantic.loved
        taste.equals("Like", true) -> semantic.liked
        taste.equals("Dislike", true) -> semantic.disliked
        else -> M3EColor.textMuted()
    }
}

/**
 * One episode: the number large, the name beside it, a Material checkbox trailing.
 *
 * The checkbox is the only thing saying "watched" — a tick already means that, and spelling it out
 * beside the tick said it twice. The episode number leads at display size because it is what an
 * episode is called when you are scanning a season, the name takes two lines so a long one is read
 * rather than elided, and the air date sits under it in the muted tone — the one line of supporting
 * text that was worth keeping, because a date is information where "watched" was repetition.
 */
/**
 * A stored date as it is read here: dd-MM-yyyy.
 *
 * The database keeps ISO dates and always will — they sort, compare and round-trip unchanged. This
 * is the display side only, and it is a reordering of the parts rather than a parse, so a value
 * that is not a plain ISO date is passed through instead of throwing.
 */
/**
 * A "yyyy-MM-dd", or the date half of an ISO instant, as a date — or null when it will not parse.
 *
 * `java.time` is available unguarded: minSdk here is 26, which is exactly where it arrived.
 */
private fun isoDay(iso: String?): LocalDate? =
    runCatching { LocalDate.parse(iso?.take(10)) }.getOrNull()

/**
 * When it lands, in the words the Coming soon list already uses.
 *
 * Today and tomorrow are named, because that is how a person reads a radar — "in 1 day" is not what
 * anyone says. Past that the date is a date, and the time is shown whenever the app knows one, so
 * "Today · 21:00" is a fact rather than an approximation.
 */
private fun landingLabel(iso: String?, instant: Date?): String? {
    val zoned = instant?.toInstant()?.atZone(ZoneId.systemDefault())
    val day = zoned?.toLocalDate() ?: isoDay(iso) ?: return null
    val today = LocalDate.now()
    val name = when (day) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> day.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()))
    }
    val time = zoned?.toLocalTime()
    return if (time != null && (time.hour != 0 || time.minute != 0)) {
        "$name · " + String.format(Locale.US, "%02d:%02d", time.hour, time.minute)
    } else {
        name
    }
}

private fun indianDate(iso: String?): String? {
    val raw = iso?.take(10) ?: return null
    val parts = raw.split("-")
    if (parts.size != 3) return raw
    return "${parts[2]}-${parts[1]}-${parts[0]}"
}

/**
 * The Mac's stored hex as a colour, or null when there is none to use.
 *
 * Stored without the leading `#` (`E83B0A`), which `parseColor` does not accept, so it is added —
 * and tolerated if a future writer includes it. Anything unparseable returns null rather than
 * throwing: a bad value should cost the tint, not the screen.
 */
/**
 * [accent] mixed into [base], for a page that leans towards a title without leaving the palette.
 *
 * A flat tint at this strength would wash the whole screen and fight whatever palette the user
 * chose; a blend keeps the palette's own value and only bends it.
 */
private fun mix(accent: Color, base: Color, amount: Float = 0.12f): Color = Color(
    red = base.red + (accent.red - base.red) * amount,
    green = base.green + (accent.green - base.green) * amount,
    blue = base.blue + (accent.blue - base.blue) * amount,
    alpha = base.alpha
)

/**
 * The poster's colour, made fit to carry text in this scheme.
 *
 * The Mac does this in OKLCH — perceptual lightness and chroma — which is the better space for it.
 * This works in HSV, which Android converts to and from natively: the intent is the same (vivid and
 * bright on a dark page, deep and saturated on a light one) but the numbers are not identical, so a
 * poster can land on a slightly different shade here than on the Mac.
 *
 * A near-grey has no hue worth pushing, so it goes to a legible neutral instead of being forced into
 * a colour that was never in the artwork.
 */
private fun highContrastAccent(color: Color, isDark: Boolean): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    val (hue, saturation, value) = hsv
    if (saturation < 0.05f) {
        return if (isDark) Color(0xFFEDEDED) else Color(0xFF2B2B2B)
    }
    val adjusted = if (isDark) {
        floatArrayOf(hue, maxOf(saturation, 0.55f), maxOf(value, 0.92f))
    } else {
        floatArrayOf(hue, maxOf(saturation, 0.50f), minOf(value, 0.55f))
    }
    return Color(android.graphics.Color.HSVToColor(adjusted))
}

private fun themeColor(hex: String?): Color? {
    val raw = hex?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching {
        Color(android.graphics.Color.parseColor(if (raw.startsWith("#")) raw else "#$raw"))
    }.getOrNull()
}

/**
 * The season to open on, the Mac's rule exactly: the first season still holding an unwatched
 * episode; if every season is finished, the last one. Specials only win when there is nothing else,
 * because a show with real seasons is never opened on season 0.
 *
 * The two branches are one rule — a finished show has no season with an unwatched episode, so it
 * falls through to the last — but they are written separately because that is how they read.
 */
private fun openingSeasonFor(episodes: List<EpisodeEntity>): Int {
    val bySeason = episodes.groupBy { it.seasonNumber }
    val active = bySeason.keys.filter { it > 0 }.sorted()
    if (active.isEmpty()) return bySeason.keys.minOrNull() ?: 1

    val allWatched = active.all { season -> bySeason[season].orEmpty().all { it.isWatched } }
    if (allWatched) return active.last()

    return active.firstOrNull { season -> bySeason[season].orEmpty().any { !it.isWatched } }
        ?: active.last()
}

@Composable
private fun EpisodeRow(episode: EpisodeEntity, watched: Boolean, onToggle: (Boolean) -> Unit) {
    // A sweep either way toggles it.
    //
    // The row never actually leaves the list — an episode is not a thing you dismiss — so the state
    // is told not to settle: the gesture fires the toggle and the row springs back. That is the
    // difference between a swipe *action* and a swipe *dismissal*, and it is one boolean.
    val sweepState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) onToggle(!watched)
            false
        }
    )
    SwipeToDismissBox(
        state = sweepState,
        backgroundContent = {
            val tint = if (watched) M3EColor.textMuted() else M3EColor.positive()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(M3EShape.card())
                    .background(tint.copy(alpha = 0.18f))
                    .padding(horizontal = M3ESpacing.cardGap),
                contentAlignment = Alignment.CenterStart
            ) {
                Icon(
                    imageVector = if (watched) Icons.Default.Undo else Icons.Default.Check,
                    contentDescription = null,
                    tint = tint
                )
            }
        }
    ) {
    M3ETonalCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(M3ESpacing.inlineGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "E${episode.episodeNumber}",
                style = M3EType.metric(),
                color = if (watched) M3EColor.textMuted() else M3EColor.accent(),
                maxLines = 1,
                modifier = Modifier.width(56.dp)
            )
            Spacer(Modifier.width(M3ESpacing.inlineGap))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = episode.name.ifBlank { "Episode ${episode.episodeNumber}" },
                    style = M3EType.rowTitle(),
                    fontWeight = FontWeight.SemiBold,
                    color = M3EColor.text(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                // Its own line, not a trailing column: an intrinsic-width sibling beside a weighted
                // one takes the space the name needs and squeezes it, which is what used to happen
                // to the labels in the Details card.
                val aired = indianDate(episode.airDate)
                if (!aired.isNullOrBlank()) {
                    Text(
                        text = aired,
                        style = M3EType.rowSupporting(),
                        color = M3EColor.textMuted(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Checkbox(checked = watched, onCheckedChange = onToggle)
        }
    }
    }
}

/** One suggestion, as the Library shows a title: poster over name. */
@Composable
private fun RelatedCard(result: TMDBSearchItem, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val title = result.title ?: result.name ?: "Untitled"
    M3EArtworkCard(title = title, modifier = modifier, onClick = onClick) {
        AsyncImage(
            model = TMDBClient.buildPosterUrl(result.poster_path),
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(M3EShape.chip())
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        )
    }
}

/** One cast member in the strip: portrait, name, and the role they played. */
@Composable
private fun CastCard(member: TMDBCastMember) {
    Column(
        modifier = Modifier.width(84.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AsyncImage(
            model = TMDBClient.buildProfileUrl(member.profile_path),
            contentDescription = member.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        )
        Spacer(Modifier.height(M3ESpacing.tightGap))
        Text(
            text = member.name,
            style = M3EType.rowSupporting(),
            fontWeight = FontWeight.SemiBold,
            color = M3EColor.text(),
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        val character = member.character
        if (!character.isNullOrBlank()) {
            Text(
                text = character,
                style = M3EType.rowSupporting(),
                color = M3EColor.textMuted(),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** A label/value row inside a card, spaced by the rhythm rather than by padding literals. */
@Composable
private fun DetailRow(label: String, value: String, divider: Boolean = true, lines: Int = 1) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = M3ESpacing.cardGap, vertical = M3ESpacing.rowGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = M3EType.rowSupporting(),
                color = M3EColor.textMuted(),
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = value,
                style = M3EType.rowTitle(),
                fontWeight = FontWeight.SemiBold,
                color = M3EColor.text(),
                maxLines = lines,
                textAlign = TextAlign.End,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(2f)
            )
        }
        if (divider) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = M3ESpacing.cardGap),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}
