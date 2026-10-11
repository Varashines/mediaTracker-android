package com.vara.mediatracker.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.vara.mediatracker.ui.common.rememberTodayDateString
import com.vara.mediatracker.ui.theme.AppColors
import java.text.SimpleDateFormat
import java.util.*

import com.vara.mediatracker.data.api.TMDBClient
import com.vara.mediatracker.data.prefs.PreferencesManager
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
    onSelectMedia: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val allEpisodes by container.mediaDao.getAllEpisodesFlow()
        .collectAsState(initial = emptyList())

    var activeFocusSection by remember { mutableStateOf<HomeFocusSection?>(null) }

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

    val sections = remember(continueWatching, allInProgress, smartBadgesMap) {
        HomeSectionsBuilder.build(
            readyNow = continueWatching,
            inProgressAll = allInProgress,
            badges = smartBadgesMap
        )
    }

    // Pre-fetch logos for visible continue watching items
    LaunchedEffect(continueWatching, container.preferences.tmdbApiKey) {
        val apiKey = container.preferences.tmdbApiKey
        if (!apiKey.isNullOrBlank()) {
            val tmdbClient = TMDBClient(apiKey)
            for (item in continueWatching) {
                if (logoMap.containsKey(item.id)) continue
                val tmdbId = MediaIds.tmdbIdOf(item.id)
                if (tmdbId <= 0) continue
                val isMovie = item.type.contains("Movie", ignoreCase = true)
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

    if (items.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.CloudSync,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = AppColors.Accent.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text("Your Library is Empty", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Sync with Supabase in Settings to import your titles",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
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

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        // 1. Top Continue Watching: First 5 cards scrollable at the top (or spotlight if CW empty)
        if (heroSource.isNotEmpty()) {
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(heroSource, key = { "top_${it.id}" }) { item ->
                        val progress = showProgressMap[item.id]
                        ContinueWatchingBackdropCard(
                            item = item,
                            progress = progress,
                            smartBadge = smartBadgesMap[item.id],
                            cardWidth = 270.dp,
                            cardHeight = 152.dp,
                            logoUrl = logoMap[item.id],
                            onClick = { onSelectMedia(item.id) }
                        )
                    }
                }
            }
        } else if (spotlightItem != null) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .height(230.dp)
                        .clickable { onSelectMedia(spotlightItem.id) },
                    shape = RoundedCornerShape(20.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AsyncImage(
                            model = spotlightItem.backdropUrl ?: spotlightItem.posterUrl,
                            contentDescription = spotlightItem.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            Color.Black.copy(alpha = 0.4f),
                                            Color.Black.copy(alpha = 0.95f)
                                        )
                                    )
                                )
                        )
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(18.dp)
                        ) {
                            val heroBadge = smartBadgesMap[spotlightItem.id]
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (heroBadge != null) {
                                    SmartBadgePill(badge = heroBadge.badge, isSparkle = heroBadge.isSparkle)
                                    Spacer(modifier = Modifier.width(6.dp))
                                }
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (heroBadge != null) Color.Black.copy(alpha = 0.6f) else AppColors.Accent
                                ) {
                                    Text(
                                        text = if (spotlightItem.state.equals("Watching", ignoreCase = true) || spotlightItem.state.equals("Active", ignoreCase = true)) "NOW WATCHING" else "FEATURED",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = spotlightItem.title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (spotlightItem.overview.isNotBlank()) {
                                Text(
                                    text = spotlightItem.overview,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.85f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. macOS Top Focus Section Bar (For You [Watchlist Only] / Recently Watched / Pick of the Day)
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HomeFocusSection.values().forEach { section ->
                        val isSelected = activeFocusSection == section
                        Surface(
                            onClick = {
                                activeFocusSection = if (isSelected) null else section
                            },
                            shape = RoundedCornerShape(20.dp),
                            color = if (isSelected) AppColors.Accent else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 9.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = when (section) {
                                        HomeFocusSection.FOR_YOU -> Icons.Default.AutoAwesome
                                        HomeFocusSection.RECENTLY_WATCHED -> Icons.Default.History
                                        HomeFocusSection.PICK_OF_THE_DAY -> Icons.Default.Star
                                    },
                                    contentDescription = null,
                                    tint = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = section.title,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                // Expanded Focus Tray
                AnimatedVisibility(
                    visible = activeFocusSection != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    if (activeFocusSection != null) {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = AppColors.Accent.copy(alpha = 0.08f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.Accent.copy(alpha = 0.25f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp)
                        ) {
                            Column(modifier = Modifier.padding(vertical = 12.dp)) {
                                val focusList = when (activeFocusSection) {
                                    HomeFocusSection.FOR_YOU -> forYouItems
                                    HomeFocusSection.RECENTLY_WATCHED -> recentlyWatchedItems
                                    HomeFocusSection.PICK_OF_THE_DAY -> pickOfTheDay
                                    null -> emptyList()
                                }
                                if (activeFocusSection == HomeFocusSection.FOR_YOU) {
                                    Text(
                                        text = "Top picks from your Watchlist queue",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                }
                                if (focusList.isEmpty()) {
                                    Text(
                                        text = if (activeFocusSection == HomeFocusSection.FOR_YOU) "No titles in your Watchlist yet." else "No items found.",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                    )
                                } else {
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = 12.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        items(focusList, key = { it.id }) { item ->
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
        }

        // 3. ROW 1: CONTINUE WATCHING (Remaining items below top 5)
        if (remainingCW.isNotEmpty()) {
            item {
                Column {
                    SectionHeader(
                        title = "Continue Watching",
                        count = remainingCW.size,
                        accent = AppColors.Gold
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(remainingCW, key = { "rest_${it.id}" }) { item ->
                            val progress = showProgressMap[item.id]
                            ContinueWatchingBackdropCard(
                                item = item,
                                progress = progress,
                                smartBadge = smartBadgesMap[item.id],
                                logoUrl = logoMap[item.id],
                                onClick = { onSelectMedia(item.id) }
                            )
                        }
                    }
                }
            }
        }

        // 4. ROW 2: COMING SOON (Upcoming Releases + Airing Soon Next Episodes, max 15, no count beside title)
        if (comingSoonItems.isNotEmpty()) {
            item {
                Column {
                    SectionHeader(
                        title = "Coming Soon",
                        count = comingSoonItems.size,
                        accent = AppColors.AccentLuminous
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(comingSoonItems, key = { it.id }) { item ->
                            val progress = showProgressMap[item.id]
                            HomePosterCard(
                                item = item,
                                nextAirDate = progress?.nextAiringDate ?: (if (item.releaseDate != null && item.releaseDate > todayDateStr) item.releaseDate else null),
                                smartBadge = smartBadgesMap[item.id],
                                onClick = { onSelectMedia(item.id) }
                            )
                        }
                    }
                }
            }
        }

        // New: premieres and brand new releases, lifted out of Continue Watching so a
        // time-sensitive title always has somewhere prominent to live.
        if (sections.newReleases.isNotEmpty()) {
            item {
                Column {
                    SectionHeader(
                        title = "New",
                        count = sections.newReleases.size,
                        accent = AppColors.Liked
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    PosterRail(
                        items = sections.newReleases,
                        badges = smartBadgesMap,
                        progress = showProgressMap,
                        onSelectMedia = onSelectMedia
                    )
                }
            }
        }

        // In Progress: everything unfinished that is neither ready now nor new. This
        // previously had no home at all.
        if (sections.inProgress.isNotEmpty()) {
            item {
                Column {
                    SectionHeader(
                        title = "In Progress",
                        count = sections.inProgress.size,
                        accent = AppColors.Accent
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    PosterRail(
                        items = sections.inProgress,
                        badges = smartBadgesMap,
                        progress = showProgressMap,
                        onSelectMedia = onSelectMedia
                    )
                }
            }
        }

        // 5. ROW 3: RECENTLY ADDED (max 15, no count beside title)
        if (recentlyAddedItems.isNotEmpty()) {
            item {
                Column {
                    SectionHeader(
                        title = "Recently Added",
                        count = recentlyAddedItems.size,
                        accent = AppColors.TextSecondary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
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

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier
            .width(cardWidth)
            .height(cardHeight)
            .clickable(onClick = onClick)
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
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = if (item.type.contains("TV", ignoreCase = true)) "TV" else "FILM",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
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
    count: Int? = null,
    accent: Color? = null,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(horizontal = 16.dp)
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
        if (count != null && count > 0) {
            Spacer(modifier = Modifier.width(7.dp))
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
            ) {
                Text(
                    text = if (count > 99) "99+" else count.toString(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                )
            }
        }
    }
}

/** Poster rail used by the New and In Progress sections. */
@Composable
private fun PosterRail(
    items: List<MediaItemEntity>,
    badges: Map<String, BadgeResult?>,
    progress: Map<String, TVShowProgress>,
    onSelectMedia: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return

    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(items, key = { it.id }) { item ->
            val info = progress[item.id]
            HomePosterCard(
                item = item,
                nextAirDate = info?.nextAiringDate,
                smartBadge = badges[item.id],
                onClick = { onSelectMedia(item.id) }
            )
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
    onClick: () -> Unit
) {
    // Target date resolution using network / streaming service rules
    val targetAirDate = remember(nextAirDate, item.releaseDate, item.title, item.overview) {
        val rawDate = nextAirDate ?: item.releaseDate
        parseTargetAirDateTime(rawDate, "${item.title} ${item.overview}")
    }

    val countdownLabel = rememberCountdownText(targetAirDate)

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        modifier = Modifier
            .width(136.dp)
            .height(204.dp)
            .clickable(onClick = onClick)
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
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                ) {
                    Text(
                        text = if (item.type.contains("TV", ignoreCase = true)) "TV" else "FILM",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
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
                    color = Color.Black.copy(alpha = 0.78f),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, Color.White.copy(alpha = 0.2f)),
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
