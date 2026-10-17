package com.vara.mediatracker.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import com.vara.mediatracker.data.model.Taste
import com.vara.mediatracker.ui.common.OverlayAwareBackHandler
import com.vara.mediatracker.ui.common.rememberHaptics
import com.vara.mediatracker.ui.common.SnapRail
import com.vara.mediatracker.ui.common.AppToast
import com.vara.mediatracker.ui.common.FlatCard
import com.vara.mediatracker.ui.common.MonoLabel
import com.vara.mediatracker.ui.common.ProgressLine
import com.vara.mediatracker.ui.common.SectionViewAllLink
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.vara.mediatracker.data.TrackingLogic
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.vara.mediatracker.data.badge.BadgeEngine
import com.vara.mediatracker.data.badge.BadgeResult
import com.vara.mediatracker.data.badge.SmartBadge
import com.vara.mediatracker.data.db.AppDatabase
import com.vara.mediatracker.di.AppContainer
import com.vara.mediatracker.data.model.EpisodeEntity
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.data.model.MediaIds
import com.vara.mediatracker.ui.common.parseAirInstant
import com.vara.mediatracker.ui.common.parseTargetAirDateTime
import com.vara.mediatracker.ui.common.rememberCountdownText
import com.vara.mediatracker.ui.common.LocalNowMillis
import com.vara.mediatracker.ui.common.EmptyState
import com.vara.mediatracker.ui.common.ExpressiveCard
import com.vara.mediatracker.ui.common.LibraryViewModel
import com.vara.mediatracker.ui.common.rememberTodayDateString
import com.vara.mediatracker.ui.common.springClickable
import com.vara.mediatracker.ui.theme.AppSpacing
import com.vara.mediatracker.ui.theme.AppTextStyles
import com.vara.mediatracker.ui.theme.ExpressiveShapes
import com.vara.mediatracker.ui.theme.LocalSemanticColors
import java.text.SimpleDateFormat
import java.util.*

import com.vara.mediatracker.data.api.TMDBClient
import com.vara.mediatracker.data.api.TmdbResponseCache
import com.vara.mediatracker.data.prefs.PreferencesManager
import com.vara.mediatracker.ui.common.TVShowProgress
import com.vara.mediatracker.ui.theme.SemanticColors
import com.vara.mediatracker.util.AirTimeResolver
import com.vara.mediatracker.util.NetworkResolver
import kotlinx.coroutines.launch


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    items: List<MediaItemEntity>,
    container: AppContainer,
    onSelectMedia: (String) -> Unit,
    onOpenCalendar: () -> Unit = {},
    onOpenSearch: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val sem = LocalSemanticColors.current
    // Everything below is derived once, off the UI thread, by the activity-scoped
    // ViewModel — grouping ~9k episodes, per-show sorts, eligibility scans and air-instant
    // resolution used to run here on the main thread and were redone on every tab switch.
    // The screen now only renders the result.
    val libraryVm: LibraryViewModel = viewModel(factory = LibraryViewModel.factory(container))
    val core by libraryVm.core.collectAsState()
    val home by libraryVm.home.collectAsState()

    val episodesByShow = core.episodesByShow
    val smartBadgesMap = core.badges
    val showProgressMap = home.showProgressMap
    val resumeItems = home.resumeItems
    val comingSoonItems = home.comingSoonItems
    val recentlyAddedItems = home.recentlyAddedItems
    val lovedItems = home.lovedItems
    val spotlightItem = home.spotlightItem
    val spotlightItems = home.spotlightItems

    var activeSectionListTitle by remember { mutableStateOf<String?>(null) }
    var activeSectionListItems by remember { mutableStateOf<List<MediaItemEntity>>(emptyList()) }

    val todayDateStr = rememberTodayDateString()

    // Network name cache: mediaId -> realNetworkName. Seeded from the persisted Room
    // `network` field first (enrichment writes it), then the prefs cache.
    val networkMap = remember {
        mutableStateMapOf<String, String>().apply {
            items.forEach { item ->
                (item.network ?: container.preferences.getCachedNetwork(item.id))?.let { put(item.id, it) }
            }
        }
    }

    // Title-treatment logo cache for the hero cards: mediaId -> logo URL.
    val logoMap = remember { mutableStateMapOf<String, String>() }

    // Force network refresh trigger state
    var forceNetworkRefreshCount by remember { mutableIntStateOf(0) }

    // Pre-fetch real TMDB networks for the items Home actually renders.
    val itemsToEnrich = remember(spotlightItem, resumeItems, comingSoonItems, recentlyAddedItems, items) {
        (listOfNotNull(spotlightItem) + resumeItems + comingSoonItems + recentlyAddedItems + items.take(15))
            .distinctBy { it.id }
    }
    // Key the fetch on the ids, not the list instances: `home` re-emits on every episode
    // change, and restarting the whole enrichment pass each time thrashed the network.
    val enrichIds = remember(itemsToEnrich) { itemsToEnrich.map { it.id } }

    LaunchedEffect(enrichIds, container.preferences.tmdbApiKey, forceNetworkRefreshCount) {
        val apiKey = container.preferences.tmdbApiKey
        if (!apiKey.isNullOrBlank()) {
            // Shared client: `TMDBClient(apiKey)` builds a fresh OkHttp pool per mount.
            val tmdbClient = container.tmdbClient()
            for (item in itemsToEnrich) {
                val tmdbId = MediaIds.tmdbIdOf(item.id)
                if (tmdbId <= 0) continue
                val isMovie = item.type.contains("Movie", ignoreCase = true)

                // Skip titles whose network is already known (Room `network` or the cache);
                // only a force refresh re-fetches.
                val knownNetwork = !item.network.isNullOrBlank() || networkMap.containsKey(item.id)
                if (!knownNetwork || forceNetworkRefreshCount > 0) {
                    val detailsRes = if (isMovie) tmdbClient.getMovieDetails(tmdbId) else tmdbClient.getTVDetails(tmdbId)
                    val details = detailsRes.getOrNull()
                    val net = if (isMovie) {
                        details?.production_companies?.firstOrNull()?.name
                    } else {
                        details?.networks?.firstOrNull()?.name
                    }
                    if (!net.isNullOrBlank()) {
                        networkMap[item.id] = net
                        container.preferences.setCachedNetwork(item.id, net)
                    }
                }
            }
        }
    }

    // Fetch the wordmark logo for the spotlight hero cards (title name is the fallback).
    val spotlightIds = remember(spotlightItems) { spotlightItems.map { it.id } }
    LaunchedEffect(spotlightIds, container.preferences.tmdbApiKey) {
        val apiKey = container.preferences.tmdbApiKey
        if (!apiKey.isNullOrBlank()) {
            val tmdbClient = container.tmdbClient()
            for (item in spotlightItems) {
                if (logoMap.containsKey(item.id)) continue
                val tmdbId = MediaIds.tmdbIdOf(item.id)
                if (tmdbId <= 0) continue
                val isMovie = item.type.contains("Movie", ignoreCase = true)
                val logoKey = "titlelogo:${item.id}"
                val path = TmdbResponseCache.get<String>(logoKey) ?: run {
                    val imagesRes = if (isMovie) tmdbClient.getMovieImages(tmdbId) else tmdbClient.getTVImages(tmdbId)
                    val logos = imagesRes.getOrNull()?.logos ?: emptyList()
                    val best = logos
                        .filter { it.iso_639_1 == null || it.iso_639_1 == "en" }
                        .maxByOrNull { it.vote_average ?: 0.0 }
                        ?: logos.maxByOrNull { it.vote_average ?: 0.0 }
                    best?.file_path?.also { TmdbResponseCache.put(logoKey, it) }
                }
                path?.let { p -> TMDBClient.buildLogoUrl(p)?.let { logoMap[item.id] = it } }
            }
        }
    }

    // Unconditional call, gated with `enabled`. Composing a BackHandler *inside* the `if`
    // below changes composition order and lets it outrank the detail overlay's handler, so
    // the system back would close the hidden list instead of the detail.
    val sectionListTitle = activeSectionListTitle
    OverlayAwareBackHandler(enabled = sectionListTitle != null) {
        activeSectionListTitle = null
        activeSectionListItems = emptyList()
    }

    if (sectionListTitle != null) {
        com.vara.mediatracker.ui.common.SectionListScreen(
            title = sectionListTitle,
            items = activeSectionListItems,
            showProgressMap = showProgressMap,
            smartBadgesMap = smartBadgesMap,
            networkMap = networkMap,
            onSelectMedia = onSelectMedia,
            onBack = {
                activeSectionListTitle = null
                activeSectionListItems = emptyList()
            }
        )
        return
    }

    if (items.isEmpty()) {
        EmptyState(
            icon = Icons.Default.CloudSync,
            title = "Your library is empty",
            body = "Pair your Supabase project in Settings to import your titles."
        )
        return
    }

    val listState = rememberLazyListState()

    Column(modifier = Modifier.fillMaxSize()) {
        // Sticky Top App Bar matching M3E HTML showcase: "EXPLORE" / "MediaTracker" + Actions
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.85f))
                .statusBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "EXPLORE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 1.2.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(sem.loved)
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "MediaTracker",
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            IconButton(
                onClick = onOpenSearch,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(19.dp)
                )
            }
        }


        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = AppSpacing.sm, bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap)
        ) {
            // Quick Library Summary Stats Row
            item {
                val activeCount = remember(items) { items.count { it.state.equals("Active", true) || it.state.equals("Watching", true) } }
                val completedCount = remember(items) { items.count { it.state.equals("Completed", true) } }
                val dropCount = comingSoonItems.size

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HomeStatCard(
                        title = "TOTAL",
                        value = "${items.size}",
                        icon = Icons.Default.VideoLibrary,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    HomeStatCard(
                        title = "ACTIVE",
                        value = "$activeCount",
                        icon = Icons.Default.PlayArrow,
                        tint = sem.liked,
                        modifier = Modifier.weight(1f)
                    )
                    HomeStatCard(
                        title = "DONE",
                        value = "$completedCount",
                        icon = Icons.Default.Check,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.weight(1f)
                    )
                    HomeStatCard(
                        title = "DROPS",
                        value = "$dropCount",
                        icon = Icons.Default.CalendarMonth,
                        tint = sem.loved,
                        modifier = Modifier.weight(1f),
                        onClick = onOpenCalendar
                    )
                }
            }

            // Spotlight carousel — swipeable hero with page dots.
            if (spotlightItems.isNotEmpty()) {
                item {
                    val pagerState = rememberPagerState(pageCount = { spotlightItems.size })
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        HorizontalPager(
                            state = pagerState,
                            pageSpacing = 12.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) { page ->
                            val sp = spotlightItems[page]
                            HomeSpotlightCard(
                                item = sp,
                                smartBadgesMap = smartBadgesMap,
                                showProgressMap = showProgressMap,
                                logoUrl = logoMap[sp.id],
                                onOpen = { onSelectMedia(sp.id) },
                                onMarkNext = {
                                    haptics.toggle()
                                    if (sp.type.contains("TV", ignoreCase = true)) {
                                        markEpisodeWatched(
                                            showProgressMap[sp.id]?.nextEpisode,
                                            sp,
                                            scope,
                                            container,
                                            onSelectMedia
                                        )
                                    } else {
                                        scope.launch {
                                            TrackingLogic.toggleMovieWatched(
                                                container = container,
                                                movie = sp
                                            )
                                        }
                                    }
                                }
                            )
                        }
                        if (spotlightItems.size > 1) {
                            Row(
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .padding(top = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                repeat(spotlightItems.size) { i ->
                                    val active = i == pagerState.currentPage
                                    Box(
                                        modifier = Modifier
                                            .size(if (active) 8.dp else 6.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (active) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                            )
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Continue Watching — same row layout as Up Next (same gutter, so same width).
            if (resumeItems.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SectionHeader(
                                title = "In Progress",
                                accent = MaterialTheme.colorScheme.primary,
                                horizontalPadding = 0.dp
                            )
                            SectionViewAllLink(
                                text = "View All (${resumeItems.size})",
                                onClick = {
                                    activeSectionListTitle = "In Progress"
                                    activeSectionListItems = resumeItems
                                }
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            resumeItems.take(3).forEach { item ->
                                HomeUpNextRow(
                                    item = item,
                                    progress = showProgressMap[item.id],
                                    networkMap = networkMap,
                                    sem = sem,
                                    onClick = { onSelectMedia(item.id) },
                                    onQuickCheck = { nextEp -> markEpisodeWatched(nextEp, item, scope, container, onSelectMedia) }
                                )
                            }
                        }
                    }
                }
            }

            // Up Next For You — the Coming Soon list, where a day countdown makes sense.
            if (comingSoonItems.isNotEmpty()) {
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SectionHeader(
                            title = "Up Next For You",
                            accent = MaterialTheme.colorScheme.primary,
                            horizontalPadding = 0.dp
                        )

                        SectionViewAllLink(
                            text = "View All (${comingSoonItems.size})",
                            onClick = {
                                activeSectionListTitle = "Up Next For You"
                                activeSectionListItems = comingSoonItems
                            }
                        )
                    }

                    // Render vertical up next items
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        comingSoonItems.take(3).forEach { item ->
                            HomeUpNextRow(
                                item = item,
                                progress = showProgressMap[item.id],
                                networkMap = networkMap,
                                sem = sem,
                                onClick = { onSelectMedia(item.id) },
                                onQuickCheck = { nextEp -> markEpisodeWatched(nextEp, item, scope, container, onSelectMedia) }
                            )
                        }
                    }
                }
            }
            }

            // (Coming Soon moved up into "Up Next For You".)

        if (recentlyAddedItems.isNotEmpty()) {
            item {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SectionHeader(
                            title = "Recently Added",
                            accent = MaterialTheme.colorScheme.onSurfaceVariant,
                            horizontalPadding = 0.dp
                        )

                        SectionViewAllLink(
                            text = "View All (${recentlyAddedItems.size})",
                            onClick = {
                                activeSectionListTitle = "Recently Added"
                                activeSectionListItems = recentlyAddedItems
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    SnapRail {
                        items(recentlyAddedItems, key = { it.id }) { item ->
                            HomePosterCard(
                                item = item,
                                smartBadge = smartBadgesMap[item.id],
                                onClick = { onSelectMedia(item.id) }
                            )
                        }
                    }
                }
            }
        }
    }
    }

}

@Composable
fun SmartBadgePill(
    badge: SmartBadge,
    isSparkle: Boolean = false,
    modifier: Modifier = Modifier
) {
    Surface(
        color = badge.backgroundColor,
        shape = RoundedCornerShape(6.dp),
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            if (isSparkle) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = badge.textColor,
                    modifier = Modifier.size(9.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
            }
            Text(
                text = badge.label,
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                color = badge.textColor,
                letterSpacing = 0.4.sp
            )
        }
    }
}


/**
 * Rail heading with an optional count chip and accent dot.
 *
 * The count is deliberately hidden past a handful: a rail showing "24" reads as an
 * inventory problem rather than a curation, which is the opposite of the intent.
 */
@Composable
fun SectionHeader(
    title: String,
    accent: Color? = null,
    // Exposed because the Calendar already pads its own LazyColumn; hardcoding 16dp
    // here silently double-padded it.
    horizontalPadding: androidx.compose.ui.unit.Dp = 16.dp,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(horizontal = horizontalPadding)
    ) {
        if (accent != null) {
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height(18.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(accent)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = title.uppercase(Locale.US),
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.8.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** Spotlight hero card used inside the Home carousel. */
@Composable
fun HomeSpotlightCard(
    item: MediaItemEntity,
    smartBadgesMap: Map<String, BadgeResult>,
    showProgressMap: Map<String, TVShowProgress>,
    logoUrl: String?,
    onOpen: () -> Unit,
    onMarkNext: () -> Unit
) {
    val sem = LocalSemanticColors.current
    val isTV = item.type.contains("TV", ignoreCase = true)
    ExpressiveCard(
        onClick = onOpen,
        shape = ExpressiveShapes.AsymHero,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 4.dp,
        modifier = Modifier.fillMaxWidth().height(270.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            val heroCover = item.backdropUrl ?: item.posterUrl
            if (heroCover != null) {
                AsyncImage(
                    model = heroCover,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.45f to Color.Black.copy(alpha = 0.28f),
                            1f to Color.Black.copy(alpha = 0.88f)
                        )
                    )
            )

            val heroBadge = smartBadgesMap[item.id]?.badge?.label ?: item.state.uppercase()
            Row(
                modifier = Modifier.align(Alignment.TopStart).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(20.dp), shadowElevation = 4.dp) {
                    Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onPrimary))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(heroBadge, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onPrimary, letterSpacing = 0.5.sp)
                    }
                }
            }

            Column(modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                if (!item.taste.isNullOrBlank() && item.taste != "None") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Star, contentDescription = null, tint = sem.gold, modifier = Modifier.size(13.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(item.taste.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = sem.gold)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }

                if (logoUrl != null) {
                    AsyncImage(
                        model = logoUrl,
                        contentDescription = item.title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.height(44.dp).widthIn(max = 240.dp)
                    )
                } else {
                    Text(item.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                if (!item.overview.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(item.overview, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }

                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = onMarkNext,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isTV) "Watch Next" else "Mark Watched", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * Live countdown label. Recomposes on every tick of the app-wide clock published
 * through LocalNowMillis, so "in 12m" actually counts down.
 */
@Composable
fun HomePosterCard(
    item: MediaItemEntity,
    nextAirDate: String? = null,
    smartBadge: BadgeResult? = null,
    showCountdown: Boolean = false,
    airTime: String? = null,
    airstamp: String? = null,
    timezone: String? = null,
    serviceName: String? = null,
    onClick: () -> Unit
) {
    val sem = LocalSemanticColors.current
    // Target instant resolved through the shared AirTimeResolver. When episode data is
    // available (TVMaze airstamp/time + the show timezone) this is exact; otherwise it
    // falls back to the network's streaming drop time.
    val targetAirDate = remember(nextAirDate, item.releaseDate, item.title, item.overview, showCountdown, airTime, airstamp, timezone, serviceName) {
        if (!showCountdown) null
        else {
            val rawDate = nextAirDate ?: item.releaseDate
            if (airstamp != null || airTime != null || serviceName != null || timezone != null) {
                parseAirInstant(rawDate, airTime, airstamp, timezone, serviceName)
            } else {
                parseTargetAirDateTime(rawDate, "${item.title} ${item.overview}")
            }
        }
    }

    val countdownLabel = if (showCountdown) rememberCountdownText(targetAirDate) else null

    // Airings inside the next 24h are the most time-sensitive thing on the screen, so
    // they get an accent-glow chip (with a slow pulse) instead of the neutral black one.
    val nowMillis = LocalNowMillis.current
    val isImminent = targetAirDate != null &&
        (targetAirDate.time - nowMillis) in 0..(24L * 60 * 60 * 1000)

    ExpressiveCard(
        onClick = onClick,
        shape = ExpressiveShapes.AsymPoster,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f),
        tonalElevation = 2.dp,
        modifier = Modifier
            .width(118.dp)
            .height(172.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (item.posterUrl != null) {
                AsyncImage(
                    model = item.posterUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (item.type.contains("TV", ignoreCase = true)) Icons.Default.Tv else Icons.Default.Movie,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            // Top-Left: SmartBadge (if present) else Type Badge (TV / FILM)
            if (smartBadge != null) {
                SmartBadgePill(
                    badge = smartBadge.badge,
                    isSparkle = smartBadge.isSparkle,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                )
            } else {
                Surface(
                    color = Color.Black.copy(alpha = 0.72f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                ) {
                    Icon(
                        imageVector = if (item.type.contains("TV", ignoreCase = true)) Icons.Default.Tv else Icons.Default.Movie,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier
                            .padding(horizontal = 5.dp, vertical = 3.dp)
                            .size(13.dp)
                    )
                }
            }

            // Top-Right: Taste Badge (if loved)
            if (Taste.isLove(item.taste)) {
                Surface(
                    color = sem.loved.copy(alpha = 0.9f),
                    shape = CircleShape,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier
                            .padding(4.dp)
                            .size(11.dp)
                    )
                }
            }

            // Bottom Overlay: Countdown Timer Chip for Upcoming / CS items (matching macOS hourglass pill)
            if (!countdownLabel.isNullOrBlank()) {
                val glowAlpha = if (isImminent) {
                    val pulse = rememberInfiniteTransition(label = "countdownPulse")
                    pulse.animateFloat(
                        initialValue = 0.45f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 1100),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "countdownGlow"
                    ).value
                } else 0f
                Surface(
                    color = if (isImminent) MaterialTheme.colorScheme.primary.copy(alpha = 0.92f) else Color.Black.copy(alpha = 0.88f),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        0.5.dp,
                        if (isImminent) MaterialTheme.colorScheme.onPrimary.copy(alpha = glowAlpha)
                        else Color.White.copy(alpha = 0.25f)
                    ),
                    shadowElevation = if (isImminent) 8.dp else 0.dp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.HourglassEmpty,
                            contentDescription = null,
                            tint = if (isImminent) MaterialTheme.colorScheme.onPrimary else sem.gold,
                            modifier = Modifier.size(10.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = countdownLabel,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isImminent) MaterialTheme.colorScheme.onPrimary else Color.White
                        )
                    }
                }
            }
        }
    }
}

/**
 * Shared Continue-Watching / Up-Next row: poster thumb, mono network, a days-left /
 * READY label resolved from the service drop-time rules, the title, episode line, a
 * progress bar, and a quick-check button. A past/negative gap reads READY.
 */
@Composable
private fun HomeUpNextRow(
    item: MediaItemEntity,
    progress: TVShowProgress?,
    networkMap: Map<String, String>,
    sem: SemanticColors,
    onClick: () -> Unit,
    onQuickCheck: (EpisodeEntity?) -> Unit
) {
    val nextEp = progress?.nextEpisode
    FlatCard(
        onClick = onClick,
        shape = ExpressiveShapes.Card,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(56.dp)
                    .height(74.dp)
                    .clip(ExpressiveShapes.SquircleSm)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            ) {
                val poster = item.posterUrl ?: item.backdropUrl
                if (poster != null) {
                    AsyncImage(
                        model = poster,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Days-left from the resolved drop instant (rules); movies use their release
            // date, TV the next unwatched episode. Hoisted out of the Column so the quick
            // action below can tell "ready" from "not aired yet".
            val daysLeftLabel = HomeEligibility.daysLeftLabel(item, nextEp, networkMap) ?: "READY"

            Column(modifier = Modifier.weight(1f)) {
                val realNet = networkMap[item.id]
                val netInfo = if (!realNet.isNullOrBlank()) {
                    NetworkResolver.resolveByName(realNet, isTV = item.type.contains("TV", ignoreCase = true))
                } else {
                    NetworkResolver.resolve(item)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MonoLabel(text = netInfo.name, color = MaterialTheme.colorScheme.primary)
                    Text(
                        text = daysLeftLabel,
                        style = AppTextStyles.MonoLabel,
                        color = if (daysLeftLabel == "READY") sem.success else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                val subDesc = nextEp?.let {
                    "S${it.seasonNumber} E${it.episodeNumber} • \"${it.name.ifBlank { "Episode " + it.episodeNumber }}\""
                } ?: "Ready to watch"

                Text(
                    text = subDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                val total = progress?.totalEpisodes ?: 0
                val watched = progress?.watchedEpisodes ?: 0
                if (total > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    ProgressLine(
                        fraction = watched.toFloat() / total.toFloat(),
                        label = "$watched/$total"
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Only a *ready* (aired) next episode can be checked off. For something still in
            // the future the button becomes a clock that opens the detail instead of marking
            // an unaired episode watched.
            val isReady = daysLeftLabel == "READY"
            Surface(
                onClick = { onQuickCheck(if (isReady) nextEp else null) },
                shape = ExpressiveShapes.SquircleSm,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isReady) Icons.Default.Check else Icons.Default.Schedule,
                        contentDescription = if (isReady) "Mark next episode watched" else "Not aired yet — open for details",
                        tint = if (isReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/** Marks an episode watched + syncs it with intelligent state progression; opens detail if no next episode. */
private fun markEpisodeWatched(
    episode: EpisodeEntity?,
    item: MediaItemEntity,
    scope: kotlinx.coroutines.CoroutineScope,
    container: com.vara.mediatracker.di.AppContainer,
    onSelectMedia: (String) -> Unit
) {
    if (episode == null) {
        onSelectMedia(item.id)
        return
    }
    scope.launch {
        TrackingLogic.markEpisodeWatched(
            container = container,
            episode = episode,
            watched = true
        )
    }
}

@Composable
private fun HomeStatCard(
    title: String,
    value: String,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    Surface(
        onClick = onClick ?: {},
        enabled = onClick != null,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(12.dp))
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = title,
                    style = AppTextStyles.MonoLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
        }
    }
}
