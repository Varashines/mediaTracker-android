package com.vara.mediatracker.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import com.vara.mediatracker.ui.common.SnapRail
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.vara.mediatracker.ui.common.EmptyState
import com.vara.mediatracker.ui.common.ExpressiveCard
import com.vara.mediatracker.ui.common.rememberTodayDateString
import com.vara.mediatracker.ui.common.springClickable
import com.vara.mediatracker.ui.theme.ExpressiveShapes
import com.vara.mediatracker.ui.theme.LocalSemanticColors
import java.text.SimpleDateFormat
import java.util.*

import com.vara.mediatracker.data.api.TMDBClient
import com.vara.mediatracker.data.prefs.PreferencesManager
import com.vara.mediatracker.util.AirTimeResolver
import com.vara.mediatracker.util.NetworkResolver
import kotlinx.coroutines.launch

data class TVShowProgress(
    val totalEpisodes: Int,
    val watchedEpisodes: Int,
    val nextEpisode: EpisodeEntity?,
    val nextAiringDate: String?
)

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
    val sem = LocalSemanticColors.current
    val allEpisodes by container.mediaDao.getAllEpisodesFlow()
        .collectAsState(initial = emptyList())

    var activeSectionListTitle by remember { mutableStateOf<String?>(null) }
    var activeSectionListItems by remember { mutableStateOf<List<MediaItemEntity>>(emptyList()) }

    // Map showId -> List<EpisodeEntity>
    val episodesByShow = remember(allEpisodes) {
        allEpisodes.groupBy { it.showId }
    }

    val todayDateStr = rememberTodayDateString()

    // Precalculate progress and next episode per show
    val showProgressMap = remember(items, episodesByShow, todayDateStr) {
        items.filter { it.type.contains("TV", ignoreCase = true) }.associate { item ->
            val showKey = MediaIds.tvShowKey(item.id)
            val showEps = (episodesByShow[showKey] ?: episodesByShow[item.id] ?: emptyList())
                .sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))
            val total = showEps.size
            val watched = showEps.count { it.isWatched }
            val nextEp = showEps.firstOrNull { !it.isWatched }
            // nextAir is the FIRST unwatched episode that airs in the STRICT FUTURE (> today)
            val nextAir = showEps.firstOrNull { ep ->
                !ep.isWatched && !ep.airDate.isNullOrBlank() && ep.airDate > todayDateStr
            }?.airDate
            item.id to TVShowProgress(
                totalEpisodes = total,
                watchedEpisodes = watched,
                nextEpisode = nextEp,
                nextAiringDate = nextAir
            )
        }
    }

    // Badges are read from Room rather than recomputed here. A background worker
    // keeps this table current, so the UI never runs BadgeEngine across the whole
    // library on the main thread — and badges are already correct on first paint.
    val badgeRows by container.database.badgeDao().observeBadges()
        .collectAsState(initial = emptyList())
    val smartBadgesMap = remember(badgeRows) {
        badgeRows.associate { row ->
            row.mediaId to BadgeResult(
                SmartBadge.fromLabel(row.badgeLabel) ?: SmartBadge.NEW,
                isSparkle = row.isSparkle
            )
        }
    }

    // Up Next / Continue Watching: eligibility rules ported from the macOS app.
    // This is the single source for the hero spotlight, the "Up Next" list, and its
    // "View All" screen, so a title can never appear in two differently-ranked rails.
    val resumeItems = remember(items, episodesByShow, smartBadgesMap, todayDateStr) {
        HomeEligibility.resumeCandidates(
            items = items,
            episodesByShow = episodesByShow,
            badges = smartBadgesMap,
            now = Date()
        )
    }

    // Network name cache: mediaId -> realNetworkName (initialized from persistent prefs cache)
    val networkMap = remember {
        mutableStateMapOf<String, String>().apply {
            items.forEach { item ->
                container.preferences.getCachedNetwork(item.id)?.let { put(item.id, it) }
            }
        }
    }

    // Force network refresh trigger state
    var forceNetworkRefreshCount by remember { mutableIntStateOf(0) }

    // COMING SOON: anything with an unaired episode or an unreleased film, soonest
    // first. Uses the ported macOS eligibility rules, and excludes anything already in
    // Up Next so a title never appears in two rails.
    val comingSoonItems = remember(items, episodesByShow, resumeItems, todayDateStr) {
        val resumeIds = resumeItems.map { it.id }.toSet()
        items.filter { item ->
            if (item.isSoftDeleted || resumeIds.contains(item.id)) return@filter false
            val key = if (item.type.contains("TV", ignoreCase = true)) MediaIds.tvShowKey(item.id) else null
            val air = if (key != null) {
                HomeEligibility.nextAiringDate(episodesByShow[key].orEmpty(), todayDateStr)
            } else {
                item.releaseDate?.take(10)
            }
            HomeEligibility.isComingSoon(item, air, todayDateStr)
        }
            .sortedBy { item ->
                val key = if (item.type.contains("TV", ignoreCase = true)) MediaIds.tvShowKey(item.id) else null
                if (key != null) {
                    HomeEligibility.nextAiringDate(episodesByShow[key].orEmpty(), todayDateStr)
                } else {
                    item.releaseDate?.take(10)
                } ?: "9999-99-99"
            }
            .take(15)
    }

    // RECENTLY ADDED: Sorted by last added / interacted, max 15
    val recentlyAddedItems = remember(items) {
        items.filter { !it.isSoftDeleted }
            .sortedByDescending { it.lastInteractionDate ?: it.updatedAt ?: it.id }
            .take(15)
    }

    val lovedItems = remember(items) {
        items.filter { !it.isSoftDeleted && (it.taste.equals("Loved", ignoreCase = true) || it.taste.equals("Love", ignoreCase = true)) }
    }

    // Hero Spotlight: highest priority title with backdrop or poster
    val spotlightItem = remember(resumeItems, comingSoonItems, lovedItems, items) {
        resumeItems.firstOrNull { it.backdropUrl != null || it.posterUrl != null }
            ?: comingSoonItems.firstOrNull { it.backdropUrl != null || it.posterUrl != null }
            ?: lovedItems.firstOrNull { it.backdropUrl != null || it.posterUrl != null }
            ?: items.firstOrNull { it.backdropUrl != null || it.posterUrl != null }
    }

    // Pre-fetch real TMDB networks for the items Home actually renders.
    val itemsToEnrich = remember(spotlightItem, resumeItems, comingSoonItems, recentlyAddedItems, items) {
        (listOfNotNull(spotlightItem) + resumeItems + comingSoonItems + recentlyAddedItems + items.take(15))
            .distinctBy { it.id }
    }

    LaunchedEffect(itemsToEnrich, container.preferences.tmdbApiKey, forceNetworkRefreshCount) {
        val apiKey = container.preferences.tmdbApiKey
        if (!apiKey.isNullOrBlank()) {
            val tmdbClient = TMDBClient(apiKey)
            for (item in itemsToEnrich) {
                val tmdbId = MediaIds.tmdbIdOf(item.id)
                if (tmdbId <= 0) continue
                val isMovie = item.type.contains("Movie", ignoreCase = true)

                // Fetch details for network if not already cached or on force refresh
                if (!networkMap.containsKey(item.id) || forceNetworkRefreshCount > 0) {
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

    if (activeSectionListTitle != null) {
        androidx.activity.compose.BackHandler {
            activeSectionListTitle = null
            activeSectionListItems = emptyList()
        }
        com.vara.mediatracker.ui.common.SectionListScreen(
            title = activeSectionListTitle!!,
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
                .background(MaterialTheme.colorScheme.background)
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
                    style = MaterialTheme.typography.headlineLarge,
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
            contentPadding = PaddingValues(top = 8.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            // Asymmetrical Hero Card matching HTML prototype
            if (spotlightItem != null) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        ExpressiveCard(
                            onClick = { onSelectMedia(spotlightItem.id) },
                            shape = ExpressiveShapes.AsymHero,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            tonalElevation = 4.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(270.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                val heroCover = spotlightItem.backdropUrl ?: spotlightItem.posterUrl
                                if (heroCover != null) {
                                    AsyncImage(
                                        model = heroCover,
                                        contentDescription = spotlightItem.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                // Dark cinematic gradient scrim
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(
                                            Brush.verticalGradient(
                                                colors = listOf(
                                                    Color.Black.copy(alpha = 0.20f),
                                                    Color.Black.copy(alpha = 0.60f),
                                                    Color.Black.copy(alpha = 0.95f)
                                                )
                                            )
                                        )
                                )

                                // Floating Dynamic Spotlight Pill Badge
                                val heroBadge = smartBadgesMap[spotlightItem.id]?.badge?.label ?: spotlightItem.state.uppercase()
                                Row(
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = RoundedCornerShape(20.dp),
                                        shadowElevation = 4.dp
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(7.dp)
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.onPrimary)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = heroBadge,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                letterSpacing = 0.5.sp
                                            )
                                        }
                                    }
                                }

                                // Media Info Box + Action Split
                                Column(
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(16.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        val realHeroNet = networkMap[spotlightItem.id]
                                        val heroNetwork = if (!realHeroNet.isNullOrBlank()) {
                                            NetworkResolver.resolveByName(realHeroNet, isTV = spotlightItem.type.contains("TV", ignoreCase = true))
                                        } else {
                                            NetworkResolver.resolve(spotlightItem)
                                        }
                                        val heroProgress = showProgressMap[spotlightItem.id]
                                        val epTag = if (spotlightItem.type.contains("TV", ignoreCase = true)) {
                                            heroProgress?.nextEpisode?.let { " • S${it.seasonNumber} E${it.episodeNumber}" } ?: " • S1 E1"
                                        } else ""

                                        Surface(
                                            color = Color.White.copy(alpha = 0.15f),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                text = if (spotlightItem.type.contains("TV", ignoreCase = true)) "${heroNetwork.shortName}$epTag" else "FEATURE FILM",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                        if (!spotlightItem.taste.isNullOrBlank() && spotlightItem.taste != "None") {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Default.Star,
                                                    contentDescription = null,
                                                    tint = sem.gold,
                                                    modifier = Modifier.size(13.dp)
                                                )
                                                Spacer(modifier = Modifier.width(3.dp))
                                                Text(
                                                    text = spotlightItem.taste.uppercase(),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = sem.gold
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))

                                    Text(
                                        text = spotlightItem.title,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Black,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    if (!spotlightItem.overview.isNullOrBlank()) {
                                        Spacer(modifier = Modifier.height(3.dp))
                                        Text(
                                            text = spotlightItem.overview,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.White.copy(alpha = 0.8f),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))

                                    // Action buttons inside hero
                                    val heroIsTV = spotlightItem.type.contains("TV", ignoreCase = true)
                                    val heroWishlisted = spotlightItem.state.equals("Wishlist", ignoreCase = true)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Button(
                                            onClick = {
                                                scope.launch {
                                                    val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())
                                                    val dao = container.mediaDao
                                                    val sync = container.syncManager()
                                                    if (heroIsTV) {
                                                        val nextEp = showProgressMap[spotlightItem.id]?.nextEpisode
                                                        if (nextEp != null) {
                                                            dao.setEpisodeWatched(nextEp.uniqueId, true, now)
                                                            val showTmdbId = MediaIds.tmdbIdOf(nextEp.showId).toString()
                                                            sync.pushEpisodeWatch(showTmdbId, nextEp.seasonNumber, nextEp.episodeNumber, true)
                                                        }
                                                    } else {
                                                        dao.updateState(spotlightItem.id, "Completed", now)
                                                        sync.pushMediaUpdate(spotlightItem.id, spotlightItem.type, "Completed", spotlightItem.taste)
                                                    }
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.primary,
                                                contentColor = MaterialTheme.colorScheme.onPrimary
                                            ),
                                            shape = RoundedCornerShape(14.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.PlayArrow,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = if (heroIsTV) "Watch Next" else "Mark Watched",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }

                                        IconButton(
                                            onClick = {
                                                scope.launch {
                                                    val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())
                                                    val next = if (heroWishlisted) "Active" else "Wishlist"
                                                    container.mediaDao.updateState(spotlightItem.id, next, now)
                                                    container.syncManager().pushMediaUpdate(spotlightItem.id, spotlightItem.type, next, spotlightItem.taste)
                                                }
                                            },
                                            modifier = Modifier
                                                .size(42.dp)
                                                .clip(RoundedCornerShape(14.dp))
                                                .background(Color.White.copy(alpha = 0.20f))
                                        ) {
                                            Icon(
                                                imageVector = if (heroWishlisted) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                                contentDescription = if (heroWishlisted) "Remove from Watchlist" else "Add to Watchlist",
                                                tint = Color.White,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Up Next For You Section (Vertical list matching HTML prototype)
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .width(6.dp)
                                    .height(18.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Up Next For You",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        SectionViewAllLink(
                            text = "View All (${resumeItems.size})",
                            onClick = {
                                activeSectionListTitle = "Continue Watching"
                                activeSectionListItems = resumeItems
                            }
                        )
                    }

                    // Render vertical up next items
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        resumeItems.take(3).forEach { item ->
                            val progress = showProgressMap[item.id]
                            ExpressiveCard(
                                onClick = { onSelectMedia(item.id) },
                                shape = ExpressiveShapes.Card,
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Thumbnail with 4K badge
                                    Box(
                                        modifier = Modifier
                                            .width(58.dp)
                                            .height(76.dp)
                                            .clip(RoundedCornerShape(16.dp))
                                    ) {
                                        val poster = item.posterUrl ?: item.backdropUrl
                                        if (poster != null) {
                                            AsyncImage(
                                                model = poster,
                                                contentDescription = item.title,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                            )
                                        }
                                        val isTvItem = item.type.contains("TV", ignoreCase = true)
                                        Surface(
                                            color = Color.Black.copy(alpha = 0.75f),
                                            shape = RoundedCornerShape(4.dp),
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .padding(4.dp)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                            ) {
                                                Icon(
                                                    imageVector = if (isTvItem) Icons.Default.Tv else Icons.Default.Movie,
                                                    contentDescription = null,
                                                    tint = Color.White,
                                                    modifier = Modifier.size(9.dp)
                                                )
                                                Spacer(modifier = Modifier.width(2.dp))
                                                Text(
                                                    text = if (isTvItem) "TV" else "FILM",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = Color.White
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(14.dp))

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
                                            Text(
                                                text = netInfo.name.uppercase(),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = MaterialTheme.colorScheme.primary,
                                                letterSpacing = 0.5.sp
                                            )
                                            Text(
                                                text = "READY",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = sem.success
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

                                        val subDesc = progress?.nextEpisode?.let {
                                            "S${it.seasonNumber} E${it.episodeNumber} • \"${it.name.ifBlank { "Episode " + it.episodeNumber }}\""
                                        } ?: "Ready to watch"

                                        Text(
                                            text = subDesc,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )

                                        Spacer(modifier = Modifier.height(6.dp))

                                        // Progress bar for TV shows with episodes
                                        val total = progress?.totalEpisodes ?: 0
                                        val watched = progress?.watchedEpisodes ?: 0
                                        if (total > 0) {
                                            val ratio = (watched.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                LinearProgressIndicator(
                                                    progress = { ratio },
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .height(5.dp)
                                                        .clip(RoundedCornerShape(3.dp)),
                                                    color = MaterialTheme.colorScheme.primary,
                                                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                                                )
                                                Text(
                                                    text = "$watched/$total",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(10.dp))

                                    // Quick check episode button
                                    IconButton(
                                        onClick = { onSelectMedia(item.id) },
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Done",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            }

            // Coming Soon is rendered once, below, as a snapping poster rail. An
            // earlier "Anticipated Releases" carousel duplicated this exact list.

        // Coming Soon — anything with an unaired episode or an unreleased film,
        // soonest first, matching macOS.
        if (comingSoonItems.isNotEmpty()) {
            item {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionHeader(
                                title = "Coming Soon",
                                accent = sem.gold,
                                horizontalPadding = 0.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Next 30 days",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        SectionViewAllLink(
                            text = "View All (${comingSoonItems.size})",
                            onClick = {
                                activeSectionListTitle = "Coming Soon"
                                activeSectionListItems = comingSoonItems
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    SnapRail {
                        items(comingSoonItems, key = { it.id }) { item ->
                            val key = if (item.type.contains("TV", ignoreCase = true)) MediaIds.tvShowKey(item.id) else null
                            val nextEp = key?.let { HomeEligibility.nextAiringEpisode(episodesByShow[it].orEmpty(), todayDateStr) }
                            HomePosterCard(
                                item = item,
                                nextAirDate = if (key != null) {
                                    HomeEligibility.nextAiringDate(episodesByShow[key].orEmpty(), todayDateStr)
                                } else {
                                    item.releaseDate?.take(10)
                                },
                                smartBadge = smartBadgesMap[item.id],
                                showCountdown = true,
                                airTime = nextEp?.airTime,
                                airstamp = nextEp?.airstamp,
                                timezone = item.timezone,
                                serviceName = AirTimeResolver.serviceHint(networkMap[item.id] ?: item.network, item.studios, item.genres),
                                onClick = { onSelectMedia(item.id) }
                            )
                        }
                    }
                }
            }
        }

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
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Spacer(modifier = Modifier.width(7.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
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

    ExpressiveCard(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
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
            if (item.taste == "Loved" || item.taste == "Love") {
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
                Surface(
                    color = Color.Black.copy(alpha = 0.88f),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, Color.White.copy(alpha = 0.25f)),
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
                            tint = sem.gold,
                            modifier = Modifier.size(10.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = countdownLabel,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}
