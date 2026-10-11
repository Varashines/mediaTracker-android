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
import com.vara.mediatracker.data.model.EpisodeEntity
import com.vara.mediatracker.data.model.MediaItemEntity
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
    db: AppDatabase? = null,
    prefs: PreferencesManager? = null,
    onSelectMedia: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val allEpisodes by db?.mediaDao()?.getAllEpisodesFlow()?.collectAsState(initial = emptyList())
        ?: remember { mutableStateOf(emptyList()) }

    var activeFocusSection by remember { mutableStateOf<HomeFocusSection?>(null) }

    // Map showId -> List<EpisodeEntity>
    val episodesByShow = remember(allEpisodes) {
        allEpisodes.groupBy { it.showId }
    }

    val todayDateStr = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }

    // Precalculate progress and next episode per show
    val showProgressMap = remember(items, episodesByShow, todayDateStr) {
        items.filter { it.type.contains("TV", ignoreCase = true) }.associate { item ->
            val showKey = if (item.id.startsWith("tv_")) item.id else "tv_" + item.id.substringAfterLast("_")
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

    // Precalculate Smart Badges across all items using BadgeEngine
    val smartBadgesMap = remember(items, episodesByShow) {
        val now = Date()
        items.associate { item ->
            val showKey = if (item.id.startsWith("tv_")) item.id else "tv_" + item.id.substringAfterLast("_")
            val showEps = episodesByShow[showKey] ?: episodesByShow[item.id] ?: emptyList()
            item.id to BadgeEngine.calculateBadge(item, showEps, now)
        }
    }

    // Continue Watching Candidate Pool matching macOS HomeCategoryProcessor.isHomeEligible:
    // 1. Active / In Progress items (Watching, Active, In Progress)
    // 2. Titles with fresh PREMIERE / NEW / BINGE_DROP radar badges released in the past 3 days (even if in Wishlist)
    val continueWatchingPool = remember(items, smartBadgesMap, todayDateStr) {
        items.filter { item ->
            if (item.isSoftDeleted) return@filter false
            if (item.state.equals("Completed", ignoreCase = true) || item.state.equals("Dropped", ignoreCase = true)) return@filter false

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
        continueWatchingPool.filter { item ->
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
        }.sortedWith(
            compareByDescending<MediaItemEntity> { item ->
                when (smartBadgesMap[item.id]?.badge) {
                    SmartBadge.PREMIERE -> 110
                    SmartBadge.NEW -> 100
                    SmartBadge.FINALE -> 95
                    SmartBadge.BINGE_DROP -> 90
                    SmartBadge.HOOKED -> 85
                    SmartBadge.BEHIND -> 80
                    SmartBadge.BINGE -> 75
                    SmartBadge.SOON -> 70
                    null -> 60
                }
            }.thenByDescending { it.lastInteractionDate ?: it.updatedAt ?: it.id }
        ).take(15)
    }

    val continueWatchingIds = remember(continueWatching) {
        continueWatching.map { it.id }.toSet()
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

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        // 1. Cinematic Hero Spotlight
        if (spotlightItem != null) {
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

        // 3. ROW 1: CONTINUE WATCHING (Ready Now Only — Landscape Backdrop Cards with Smart Badges, max 15, no count beside title)
        if (continueWatching.isNotEmpty()) {
            item {
                Column {
                    SectionHeader(title = "Continue Watching")
                    Spacer(modifier = Modifier.height(10.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(continueWatching, key = { it.id }) { item ->
                            val progress = showProgressMap[item.id]
                            ContinueWatchingBackdropCard(
                                item = item,
                                progress = progress,
                                smartBadge = smartBadgesMap[item.id],
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
                    SectionHeader(title = "Coming Soon")
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

        // 5. ROW 3: RECENTLY ADDED (max 15, no count beside title)
        if (recentlyAddedItems.isNotEmpty()) {
            item {
                Column {
                    SectionHeader(title = "Recently Added")
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
 * 16:9 ratio (236dp x 140dp), ambient overlay, next episode indicator, and progress bar.
 */
@Composable
fun ContinueWatchingBackdropCard(
    item: MediaItemEntity,
    progress: TVShowProgress?,
    smartBadge: BadgeResult? = null,
    onClick: () -> Unit
) {
    // Art priority: backdrop -> next episode still path -> center-cropped poster
    val coverUrl = remember(item.backdropUrl, item.posterUrl, progress?.nextEpisode?.stillPath) {
        item.backdropUrl
            ?: progress?.nextEpisode?.stillPath?.let { if (it.startsWith("http")) it else "https://image.tmdb.org/t/p/w780$it" }
            ?: item.posterUrl
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier
            .width(236.dp)
            .height(140.dp)
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
                Text(
                    text = item.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

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

@Composable
fun SectionHeader(title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 16.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Streaming service broadcast/release timing rule matching macOS DateUtils.
 */
data class StreamingServiceRule(
    val patterns: List<String>,
    val releaseTime: String, // "HH:mm"
    val timeZoneId: String,
    val dayOffset: Int
)

val defaultStreamingRules = listOf(
    // Apple TV Originals drop at 9 PM ET on the eve of release date
    StreamingServiceRule(listOf("apple"), "21:00", "America/New_York", -1),
    // Disney+ Marvel / Star Wars drops at 9 PM ET
    StreamingServiceRule(listOf("star wars", "marvel"), "21:00", "America/New_York", 0),
    // Disney+ standard Midnight PT
    StreamingServiceRule(listOf("disney"), "00:00", "America/Los_Angeles", 0),
    // Netflix: Midnight PT
    StreamingServiceRule(listOf("netflix"), "00:00", "America/Los_Angeles", 0),
    // Prime / Amazon / MGM+: Midnight PT
    StreamingServiceRule(listOf("amazon", "prime", "mgm"), "00:00", "America/Los_Angeles", 0),
    // Hulu / Peacock / Paramount+: Midnight ET
    StreamingServiceRule(listOf("hulu", "peacock", "paramount"), "00:00", "America/New_York", 0),
    // FX / FXX: Broadcasts evening / drops next morning on Hulu
    StreamingServiceRule(listOf("fx", "fxx"), "00:00", "America/New_York", 1),
    // HBO / Max: 9 PM ET
    StreamingServiceRule(listOf("max", "hbo"), "21:00", "America/New_York", 0)
)

/**
 * Resolves exact Date for an airDate or releaseDate string using streaming/network rules or 8 PM ET default.
 */
fun parseTargetAirDateTime(dateStr: String?, networkOrTitle: String?): Date? {
    if (dateStr.isNullOrBlank()) return null
    val cleanDate = dateStr.trim().take(10) // "yyyy-MM-dd"
    if (cleanDate.length < 10) return null

    val query = (networkOrTitle ?: "").lowercase(Locale.US)
    val rule = defaultStreamingRules.firstOrNull { r ->
        r.patterns.any { pattern -> query.contains(pattern) }
    }

    val (timePart, tzId, dayOffset) = if (rule != null) {
        Triple(rule.releaseTime, rule.timeZoneId, rule.dayOffset)
    } else {
        Triple("20:00", "America/New_York", 0)
    }

    return try {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
            timeZone = TimeZone.getTimeZone(tzId)
        }
        val baseDate = sdf.parse("$cleanDate $timePart") ?: return null
        if (dayOffset != 0) {
            val cal = Calendar.getInstance(TimeZone.getTimeZone(tzId)).apply {
                time = baseDate
                add(Calendar.DAY_OF_YEAR, dayOffset)
            }
            cal.time
        } else {
            baseDate
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * Generates countdown label matching macOS MediaThumbnailView.upcomingCountdownLabel:
 * "in X min", "in X hours", "in X days", or calendar date.
 */
fun formatCountdownTimer(targetDate: Date, now: Date = Date()): String {
    val diffMillis = targetDate.time - now.time
    if (diffMillis <= 0) return "Today"
    val diffMinutes = (diffMillis / 60000L).toInt()
    if (diffMinutes < 60) {
        return "in ${maxOf(diffMinutes, 1)}m"
    }
    val diffHours = diffMinutes / 60
    if (diffHours < 24) {
        return if (diffHours == 1) "in 1h" else "in ${diffHours}h"
    }
    val diffDays = diffHours / 24
    if (diffDays <= 60) {
        return if (diffDays == 1) "in 1d" else "in ${diffDays}d"
    }
    return SimpleDateFormat("MMM d", Locale.getDefault()).format(targetDate)
}

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

    val countdownLabel = remember(targetAirDate) {
        targetAirDate?.let { formatCountdownTimer(it) }
    }

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
