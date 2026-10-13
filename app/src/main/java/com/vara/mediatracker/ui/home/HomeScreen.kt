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
import com.vara.mediatracker.ui.common.CollapsingHeader
import com.vara.mediatracker.ui.common.SnapRail
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
import com.vara.mediatracker.ui.common.parseTargetAirDateTime
import com.vara.mediatracker.ui.common.rememberCountdownText
import com.vara.mediatracker.ui.common.EmptyState
import com.vara.mediatracker.ui.common.ExpressiveCard
import com.vara.mediatracker.ui.common.rememberTodayDateString
import com.vara.mediatracker.ui.common.springClickable
import com.vara.mediatracker.ui.theme.AppColors
import com.vara.mediatracker.ui.theme.ExpressiveShapes
import java.text.SimpleDateFormat
import java.util.*

import com.vara.mediatracker.data.api.TMDBClient
import com.vara.mediatracker.data.prefs.PreferencesManager
import com.vara.mediatracker.util.NetworkResolver
import kotlinx.coroutines.launch

enum class HomeFocusSection(val title: String) {
    FOR_YOU("For You"),
    RECENTLY_WATCHED("Recently Watched"),
    PICK_OF_THE_DAY("Pick of the Day")
}

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
    val allEpisodes by container.mediaDao.getAllEpisodesFlow()
        .collectAsState(initial = emptyList())

    var activeFocusSection by remember { mutableStateOf<HomeFocusSection?>(null) }
    var activeSectionListTitle by remember { mutableStateOf<String?>(null) }
    var activeSectionListItems by remember { mutableStateOf<List<MediaItemEntity>>(emptyList()) }

    // Map showId -> List<EpisodeEntity>
    val episodesByShow = remember(allEpisodes) {
        allEpisodes.groupBy { it.showId }
    }

    val todayDateStr = rememberTodayDateString()

    // Title logo URL cache: mediaId -> logoUrl
    val logoMap = remember { mutableStateMapOf<String, String?>() }

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

    // Continue Watching Candidate Pool matching macOS HomeCategoryProcessor.isHomeEligible:
    // 1. Active / In Progress items (Watching, Active, In Progress)
    // 2. Titles with fresh PREMIERE / NEW / BINGE_DROP radar badges released in the past 3 days (even if in Wishlist)
    val continueWatchingPool = remember(items, smartBadgesMap, todayDateStr) {
        items.filter { item ->
            if (item.isSoftDeleted) return@filter false
            if (item.state.equals("Completed", ignoreCase = true) ||
                item.state.equals("Dropped", ignoreCase = true) ||
                item.state.equals("On Hold", ignoreCase = true) ||
                item.state.equals("Paused", ignoreCase = true)
            ) return@filter false

            val isActive = item.state.equals("Watching", ignoreCase = true) ||
                item.state.equals("Active", ignoreCase = true) ||
                item.state.equals("In Progress", ignoreCase = true)

            val badge = smartBadgesMap[item.id]?.badge
            val isRadarBadge = badge != null && (
                badge == SmartBadge.PREMIERE ||
                badge == SmartBadge.NEW ||
                badge == SmartBadge.BINGE_DROP ||
                badge == SmartBadge.FINALE
            )

            if (isActive) {
                true
            } else if (isRadarBadge) {
                // Must be already released (releaseDate <= today) and within 3 days
                val relDate = item.releaseDate
                relDate != null && relDate <= todayDateStr
            } else {
                false
            }
        }
    }

    // 1. CONTINUE WATCHING: Strictly READY NOW items (next unwatched episode airDate <= today or null)
    val continueWatching = remember(continueWatchingPool, showProgressMap, smartBadgesMap, todayDateStr) {
        val readyNow = continueWatchingPool.filter { item ->
            if (!item.type.contains("TV", ignoreCase = true)) {
                // For movies: already released (releaseDate <= todayDateStr or null)
                item.releaseDate == null || item.releaseDate <= todayDateStr
            } else {
                val progress = showProgressMap[item.id]
                val nextEp = progress?.nextEpisode
                if (nextEp != null) {
                    // Ready now if next episode is already released (airDate <= today) or unannounced
                    nextEp.airDate == null || nextEp.airDate <= todayDateStr
                } else {
                    // If episodes are not synced locally yet, check show releaseDate <= today
                    item.releaseDate != null && item.releaseDate <= todayDateStr
                }
            }
        }
        HomeRanking.rank(readyNow, smartBadgesMap)
    }

    val continueWatchingIds = remember(continueWatching) {
        continueWatching.map { it.id }.toSet()
    }

    // Every actively-watching title, including ones whose next episode has not aired
    // yet. Anything not claimed by Continue Watching or New lands in In Progress.
    val allInProgress = remember(items) {
        items.filter { HomeRanking.isActivelyInProgress(it) && !it.isSoftDeleted }
    }

    // Everything the hero can page through: the freshest things worth resuming.
    val heroItems = remember(continueWatching) { continueWatching.take(5) }

    val sections = remember(continueWatching, allInProgress, smartBadgesMap) {
        HomeSectionsBuilder.build(
            readyNow = continueWatching,
            inProgressAll = allInProgress,
            badges = smartBadgesMap
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

    // Pre-fetch logos & real TMDB networks for all prominent items across Home
    val itemsToEnrich = remember(continueWatching, heroItems, items) {
        (continueWatching + heroItems + items.take(15)).distinctBy { it.id }
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

                // Fetch logo if not already cached
                if (!logoMap.containsKey(item.id)) {
                    val imagesRes = if (isMovie) tmdbClient.getMovieImages(tmdbId) else tmdbClient.getTVImages(tmdbId)
                    val bestLogo = imagesRes.getOrNull()?.logos?.let { logos ->
                        logos.sortedWith(
                            compareBy<com.vara.mediatracker.data.api.TMDBImageItem> { logo ->
                                when (logo.iso_639_1) {
                                    "en" -> 0
                                    null -> 1
                                    else -> 2
                                }
                            }.thenByDescending { it.width }
                        ).firstOrNull()?.file_path?.let { TMDBClient.buildLogoUrl(it) }
                    }
                    logoMap[item.id] = bestLogo
                }
            }
        }
    }

    // 2. COMING SOON: Strictly UPCOMING titles.
    // An item cannot be in both Continue Watching and Coming Soon:
    // - Movies / shows with releaseDate > today
    // - TV shows where the user is caught up with released episodes, and the NEXT unwatched episode airs in the future (> today)
    val comingSoonItems = remember(items, showProgressMap, smartBadgesMap, continueWatchingIds, todayDateStr) {
        val directUpcoming = items.filter { !it.isSoftDeleted && it.releaseDate != null && it.releaseDate > todayDateStr }
        val airingSoonShows = items.filter { item ->
            !item.isSoftDeleted && item.type.contains("TV", ignoreCase = true) &&
                !continueWatchingIds.contains(item.id) &&
                showProgressMap[item.id]?.nextEpisode != null &&
                showProgressMap[item.id]?.nextEpisode?.airDate != null &&
                showProgressMap[item.id]!!.nextEpisode!!.airDate!! > todayDateStr
        }
        (directUpcoming + airingSoonShows).distinctBy { it.id }.sortedWith(
            compareBy<MediaItemEntity> { item ->
                // Sort by earliest next airdate or release date
                showProgressMap[item.id]?.nextAiringDate ?: item.releaseDate ?: "9999"
            }.thenByDescending { item ->
                // Within date ties, prioritize PREMIERE, FINALE, SOON
                when (smartBadgesMap[item.id]?.badge) {
                    SmartBadge.PREMIERE -> 100
                    SmartBadge.FINALE -> 90
                    SmartBadge.SOON -> 80
                    SmartBadge.BINGE_DROP -> 70
                    else -> 0
                }
            }
        ).take(15)
    }

    // Time-sensitive strip. Sourced from Coming Soon rather than releaseDate: a show's
    // releaseDate is its premiere, often a year old, while what matters is when the
    // next episode actually lands.
    val thisWeekItems = remember(comingSoonItems) { comingSoonItems.take(15) }

    // 3. RECENTLY ADDED: Sorted by last added / interacted, max 15
    val recentlyAddedItems = remember(items) {
        items.filter { !it.isSoftDeleted }
            .sortedByDescending { it.lastInteractionDate ?: it.updatedAt ?: it.id }
            .take(15)
    }

    // Watchlist for For You focus section
    val wishlistItems = remember(items) {
        items.filter { !it.isSoftDeleted && it.state.equals("Wishlist", ignoreCase = true) }
    }

    val lovedItems = remember(items) {
        items.filter { !it.isSoftDeleted && (it.taste.equals("Loved", ignoreCase = true) || it.taste.equals("Love", ignoreCase = true)) }
    }

    // For You focus tray (Watchlist only)
    val forYouItems = remember(wishlistItems) {
        if (wishlistItems.isNotEmpty()) {
            wishlistItems.shuffled(Random(Calendar.getInstance().get(Calendar.DAY_OF_YEAR).toLong())).take(15)
        } else {
            emptyList()
        }
    }

    // Recently Watched focus tray
    val recentlyWatchedItems = remember(items) {
        items.filter { !it.isSoftDeleted && it.lastInteractionDate != null }
            .sortedByDescending { it.lastInteractionDate }
            .take(15)
    }

    // Pick of the Day focus tray
    val pickOfTheDay = remember(items, lovedItems) {
        val calendar = Calendar.getInstance()
        val dayOfYear = calendar.get(Calendar.DAY_OF_YEAR)
        val pool = if (lovedItems.isNotEmpty()) lovedItems else items.filter { !it.isSoftDeleted }
        if (pool.isEmpty()) emptyList()
        else {
            var seed = dayOfYear
            val picks = mutableListOf<MediaItemEntity>()
            val usedIndices = mutableSetOf<Int>()
            val count = minOf(4, pool.size)
            while (picks.size < count) {
                seed = (seed * 1103515245 + 12345) and 0x7FFFFFFF
                val idx = Math.floorMod(seed, pool.size)
                if (usedIndices.add(idx)) {
                    picks.add(pool[idx])
                }
            }
            picks
        }
    }

    // Hero Spotlight: highest priority title with backdrop or poster
    val spotlightItem = remember(continueWatching, comingSoonItems, lovedItems, items) {
        continueWatching.firstOrNull { it.backdropUrl != null || it.posterUrl != null }
            ?: comingSoonItems.firstOrNull { it.backdropUrl != null || it.posterUrl != null }
            ?: lovedItems.firstOrNull { it.backdropUrl != null || it.posterUrl != null }
            ?: items.firstOrNull { it.backdropUrl != null || it.posterUrl != null }
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

    // Hero row is the freshest things worth acting on: premieres and new releases,
    // then anything else ready to resume. Both derive from one partition, so a title
    // never shows up in two rails.
    val heroSource = remember(sections) {
        (sections.newReleases + sections.continueWatching).take(HomeRanking.TOP_ROW_SIZE)
    }
    val remainingCW = remember(sections, heroSource) {
        val placed = heroSource.mapTo(HashSet()) { it.id }
        (sections.newReleases + sections.continueWatching).filter { it.id !in placed }
    }

    // Fixed hint rather than reading the header's own animation state, which is
    // internal to CollapsingHeader.
    val barAlphaHint = 1f
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
                            .background(Color(0xFFFF2D55))
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

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

                IconButton(
                    onClick = {
                        forceNetworkRefreshCount++
                        scope.launch {
                            container.syncManager().pullFromCloud()
                        }
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Sync",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }


        // --- Section membership, all decided by HomeEligibility (ported macOS rules) ---
        val resumeItems = remember(items, episodesByShow, smartBadgesMap, todayDateStr) {
            HomeEligibility.resumeCandidates(
                items = items,
                episodesByShow = episodesByShow,
                badges = smartBadgesMap,
                today = todayDateStr
            )
        }
        val (newItems, inProgressItems) = remember(resumeItems, smartBadgesMap) {
            HomeEligibility.splitNewAndInProgress(resumeItems, smartBadgesMap)
        }

        val comingSoonItems = remember(items, episodesByShow, todayDateStr) {
            items.filter { item ->
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
                .take(40)
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
                                                fontSize = 9.sp,
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
                                                    tint = AppColors.Gold,
                                                    modifier = Modifier.size(13.dp)
                                                )
                                                Spacer(modifier = Modifier.width(3.dp))
                                                Text(
                                                    text = spotlightItem.taste.uppercase(),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = AppColors.Gold
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
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Button(
                                            onClick = { onSelectMedia(spotlightItem.id) },
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
                                                text = "Mark Watched",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }

                                        IconButton(
                                            onClick = { onSelectMedia(spotlightItem.id) },
                                            modifier = Modifier
                                                .size(42.dp)
                                                .clip(RoundedCornerShape(14.dp))
                                                .background(Color.White.copy(alpha = 0.20f))
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Bookmark,
                                                contentDescription = "Save",
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

                        Text(
                            text = "View All (${resumeItems.size})",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    activeSectionListTitle = "Continue Watching"
                                    activeSectionListItems = resumeItems
                                }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    // Render vertical up next items
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        resumeItems.take(3).forEach { item ->
                            val progress = showProgressMap[item.id]
                            ExpressiveCard(
                                onClick = { onSelectMedia(item.id) },
                                shape = RoundedCornerShape(20.dp),
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
                                                    fontSize = 8.sp,
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
                                                color = Color(0xFF6DDFB8)
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

            // Anticipated Releases Horizontal Carousel matching HTML prototype
            if (comingSoonItems.isNotEmpty()) {
                item {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .width(6.dp)
                                        .height(18.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(AppColors.Gold)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Anticipated Releases",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Black,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            val currentMonthYear = remember {
                                SimpleDateFormat("MMM yyyy", Locale.US).format(Date())
                            }
                            Text(
                                text = currentMonthYear,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(comingSoonItems.take(8), key = { it.id }) { item ->
                                ExpressiveCard(
                                    onClick = { onSelectMedia(item.id) },
                                    shape = ExpressiveShapes.AsymCard,
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
                                    modifier = Modifier.width(176.dp)
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(108.dp)
                                                .clip(RoundedCornerShape(14.dp))
                                        ) {
                                            val poster = item.backdropUrl ?: item.posterUrl
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
                                                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                                )
                                            }

                                            // Dynamic Countdown / Release Date Badge Pill
                                            val key = if (item.type.contains("TV", ignoreCase = true)) MediaIds.tvShowKey(item.id) else null
                                            val airDate = if (key != null) {
                                                HomeEligibility.nextAiringDate(episodesByShow[key].orEmpty(), todayDateStr)
                                            } else {
                                                item.releaseDate?.take(10)
                                            }
                                            val badgeText = remember(airDate, todayDateStr) {
                                                if (airDate != null) {
                                                    try {
                                                        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                                                        val target = sdf.parse(airDate)
                                                        val today = sdf.parse(todayDateStr)
                                                        if (target != null && today != null) {
                                                            val diffDays = ((target.time - today.time) / (1000 * 60 * 60 * 24)).toInt()
                                                            when {
                                                                diffDays == 0 -> "TODAY"
                                                                diffDays == 1 -> "TOMORROW"
                                                                diffDays in 2..30 -> "IN $diffDays DAYS"
                                                                else -> SimpleDateFormat("MMM d", Locale.US).format(target).uppercase()
                                                            }
                                                        } else {
                                                            airDate
                                                        }
                                                    } catch (_: Exception) {
                                                        airDate
                                                    }
                                                } else {
                                                    "COMING SOON"
                                                }
                                            }

                                            Surface(
                                                color = AppColors.Gold,
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier
                                                    .align(Alignment.TopStart)
                                                    .padding(6.dp)
                                            ) {
                                                Text(
                                                    text = badgeText,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Black,
                                                    color = Color.Black,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))

                                        Text(
                                            text = item.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val realAntNet = networkMap[item.id]
                                        val antNet = if (!realAntNet.isNullOrBlank()) {
                                            NetworkResolver.resolveByName(realAntNet, isTV = item.type.contains("TV", ignoreCase = true))
                                        } else {
                                            NetworkResolver.resolve(item)
                                        }
                                        Text(
                                            text = if (item.type.contains("TV", ignoreCase = true)) "${antNet.name} Original" else "${antNet.name} Theatrical",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

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
                                accent = AppColors.Gold,
                                horizontalPadding = 0.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Next 30 days",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Text(
                            text = "View All (${comingSoonItems.size})",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    activeSectionListTitle = "Coming Soon"
                                    activeSectionListItems = comingSoonItems
                                }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    SnapRail {
                        items(comingSoonItems, key = { it.id }) { item ->
                            val key = if (item.type.contains("TV", ignoreCase = true)) MediaIds.tvShowKey(item.id) else null
                            HomePosterCard(
                                item = item,
                                nextAirDate = if (key != null) {
                                    HomeEligibility.nextAiringDate(episodesByShow[key].orEmpty(), todayDateStr)
                                } else {
                                    item.releaseDate?.take(10)
                                },
                                smartBadge = smartBadgesMap[item.id],
                                showCountdown = true,
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
                            accent = AppColors.TextSecondary,
                            horizontalPadding = 0.dp
                        )

                        Text(
                            text = "View All (${recentlyAddedItems.size})",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    activeSectionListTitle = "Recently Added"
                                    activeSectionListItems = recentlyAddedItems
                                }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
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
                fontSize = 9.sp,
                fontWeight = FontWeight.ExtraBold,
                color = badge.textColor,
                letterSpacing = 0.4.sp
            )
        }
    }
}

/**
 * Landscape Backdrop Card for Continue Watching matching macOS Apple TV style:
 * 16:9 ratio, ambient overlay, next episode indicator, title logo (with text fallback), and progress bar.
 */
@Composable
fun ContinueWatchingBackdropCard(
    item: MediaItemEntity,
    progress: TVShowProgress?,
    smartBadge: BadgeResult? = null,
    cardWidth: androidx.compose.ui.unit.Dp = 236.dp,
    cardHeight: androidx.compose.ui.unit.Dp = 140.dp,
    logoUrl: String? = null,
    onClick: () -> Unit
) {
    // Art priority: backdrop -> next episode still path -> center-cropped poster
    val coverUrl = remember(item.backdropUrl, item.posterUrl, progress?.nextEpisode?.stillPath) {
        item.backdropUrl
            ?: progress?.nextEpisode?.stillPath?.let { if (it.startsWith("http")) it else "https://image.tmdb.org/t/p/w780$it" }
            ?: item.posterUrl
    }

    var isLogoFailed by remember(logoUrl) { mutableStateOf(false) }

    ExpressiveCard(
        onClick = onClick,
        shape = ExpressiveShapes.AsymCard,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f),
        tonalElevation = 3.dp,
        modifier = Modifier
            .width(cardWidth)
            .height(cardHeight)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = coverUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            // Scrim gradient for contrast
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.2f),
                                Color.Black.copy(alpha = 0.5f),
                                Color.Black.copy(alpha = 0.94f)
                            )
                        )
                    )
            )

            // Top Badges
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .align(Alignment.TopStart),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Priority: SmartBadge > TV/FILM Type
                if (smartBadge != null) {
                    SmartBadgePill(badge = smartBadge.badge, isSparkle = smartBadge.isSparkle)
                } else {
                    Surface(
                        color = Color.Black.copy(alpha = 0.72f),
                        shape = RoundedCornerShape(8.dp)
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

                // Taste Badge
                if (item.taste == "Loved" || item.taste == "Love") {
                    Surface(
                        color = AppColors.Loved.copy(alpha = 0.9f),
                        shape = CircleShape
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
            }

            // Bottom Content
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                // Title Logo with plain text as fallback
                if (!logoUrl.isNullOrBlank() && !isLogoFailed) {
                    AsyncImage(
                        model = logoUrl,
                        contentDescription = item.title,
                        contentScale = ContentScale.Fit,
                        onError = { isLogoFailed = true },
                        modifier = Modifier
                            .heightIn(max = 34.dp)
                            .widthIn(max = 180.dp)
                            .padding(bottom = 2.dp)
                    )
                } else {
                    Text(
                        text = item.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                // Subtitle: Next Episode label or Progress Count
                val subText = when {
                    item.type.contains("TV", ignoreCase = true) && progress?.nextEpisode != null -> {
                        "Next: S${progress.nextEpisode.seasonNumber} E${progress.nextEpisode.episodeNumber} • ${progress.watchedEpisodes}/${progress.totalEpisodes} EP"
                    }
                    item.type.contains("TV", ignoreCase = true) && progress != null && progress.totalEpisodes > 0 -> {
                        "${progress.watchedEpisodes}/${progress.totalEpisodes} EP Watched"
                    }
                    !item.releaseDate.isNullOrBlank() -> {
                        item.releaseDate.take(4)
                    }
                    else -> "In Progress"
                }

                Text(
                    text = subText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Progress Bar for TV shows
                if (progress != null && progress.totalEpisodes > 0) {
                    val ratio = (progress.watchedEpisodes.toFloat() / progress.totalEpisodes.toFloat()).coerceIn(0f, 1f)
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { ratio },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = AppColors.Accent,
                        trackColor = Color.White.copy(alpha = 0.25f)
                    )
                }
            }
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
    onClick: () -> Unit
) {
    // Target date resolution using network / streaming service rules (only computed when countdown is enabled)
    val targetAirDate = remember(nextAirDate, item.releaseDate, item.title, item.overview, showCountdown) {
        if (!showCountdown) null
        else {
            val rawDate = nextAirDate ?: item.releaseDate
            parseTargetAirDateTime(rawDate, "${item.title} ${item.overview}")
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
                    color = AppColors.Loved.copy(alpha = 0.9f),
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
                            tint = AppColors.Gold,
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
