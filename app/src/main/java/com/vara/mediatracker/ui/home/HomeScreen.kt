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
import com.vara.mediatracker.data.db.AppDatabase
import com.vara.mediatracker.data.model.EpisodeEntity
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.ui.theme.AppColors
import java.text.SimpleDateFormat
import java.util.*

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
    onSelectMedia: (String) -> Unit
) {
    val allEpisodes by db?.mediaDao()?.getAllEpisodesFlow()?.collectAsState(initial = emptyList())
        ?: remember { mutableStateOf(emptyList()) }

    var selectedCategoryChip by remember { mutableStateOf("All") }
    var activeFocusSection by remember { mutableStateOf<HomeFocusSection?>(null) }

    // Map showId -> List<EpisodeEntity>
    val episodesByShow = remember(allEpisodes) {
        allEpisodes.groupBy { it.showId }
    }

    // Precalculate progress and next episode per show
    val todayDateStr = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }

    val showProgressMap = remember(items, episodesByShow, todayDateStr) {
        items.filter { it.type.contains("TV", ignoreCase = true) }.associate { item ->
            val showKey = if (item.id.startsWith("tv_")) item.id else "tv_" + item.id.substringAfterLast("_")
            val showEps = (episodesByShow[showKey] ?: episodesByShow[item.id] ?: emptyList())
                .sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))
            val total = showEps.size
            val watched = showEps.count { it.isWatched }
            val nextEp = showEps.firstOrNull { !it.isWatched }
            val nextAir = showEps.firstOrNull { ep ->
                !ep.isWatched && !ep.airDate.isNullOrBlank() && ep.airDate >= todayDateStr
            }?.airDate
            item.id to TVShowProgress(
                totalEpisodes = total,
                watchedEpisodes = watched,
                nextEpisode = nextEp,
                nextAiringDate = nextAir
            )
        }
    }

    // Base Active / In Progress items
    val activeItems = remember(items) {
        items.filter {
            !it.isSoftDeleted && (
                it.state.equals("Watching", ignoreCase = true) ||
                it.state.equals("Active", ignoreCase = true) ||
                it.state.equals("In Progress", ignoreCase = true)
            )
        }
    }

    // 1. CONTINUE WATCHING: Strictly READY NOW stuff only (no future airing episodes).
    // Movies in progress are ready now.
    // TV Shows are ready now if they have an unwatched episode that has already aired (airDate <= today or null).
    val continueWatching = remember(activeItems, showProgressMap, todayDateStr) {
        activeItems.filter { item ->
            if (!item.type.contains("TV", ignoreCase = true)) {
                true // Movie in progress
            } else {
                val progress = showProgressMap[item.id]
                val nextEp = progress?.nextEpisode
                if (nextEp == null) {
                    false // All episodes watched, nothing ready to continue
                } else {
                    nextEp.airDate == null || nextEp.airDate <= todayDateStr
                }
            }
        }
    }

    // 2. COMING SOON: Both upcoming releases AND shows with future airing episodes
    // - Movies / shows with releaseDate > today
    // - TV shows where the next unwatched episode airs in the future (airDate > today)
    val comingSoonItems = remember(items, showProgressMap, todayDateStr) {
        val directUpcoming = items.filter { !it.isSoftDeleted && it.releaseDate != null && it.releaseDate > todayDateStr }
        val airingSoonShows = items.filter { item ->
            !item.isSoftDeleted && item.type.contains("TV", ignoreCase = true) &&
                showProgressMap[item.id]?.nextAiringDate != null &&
                showProgressMap[item.id]!!.nextAiringDate!! > todayDateStr
        }
        (directUpcoming + airingSoonShows).distinctBy { it.id }.sortedBy { item ->
            showProgressMap[item.id]?.nextAiringDate ?: item.releaseDate ?: "9999"
        }
    }

    // 3. WATCHLIST
    val wishlistItems = remember(items) {
        items.filter { !it.isSoftDeleted && it.state.equals("Wishlist", ignoreCase = true) }
    }

    // 4. FOR YOU: Recommendations from WATCHLIST ONLY (personalized picks from what's queued to watch)
    val forYouItems = remember(wishlistItems) {
        if (wishlistItems.isNotEmpty()) {
            wishlistItems.shuffled(Random(Calendar.getInstance().get(Calendar.DAY_OF_YEAR).toLong())).take(15)
        } else {
            emptyList()
        }
    }

    val lovedItems = remember(items) {
        items.filter { !it.isSoftDeleted && (it.taste.equals("Loved", ignoreCase = true) || it.taste.equals("Love", ignoreCase = true)) }
    }
    val recentlyCompleted = remember(items) {
        items.filter { !it.isSoftDeleted && it.state.equals("Completed", ignoreCase = true) }
    }

    // Recently Added (By last interaction or added date)
    val recentlyAddedItems = remember(items) {
        items.filter { !it.isSoftDeleted }
            .sortedByDescending { it.lastInteractionDate ?: it.updatedAt ?: it.id }
    }

    // Recently Watched (Items with lastInteractionDate in past 30 days)
    val recentlyWatchedItems = remember(items) {
        items.filter { !it.isSoftDeleted && it.lastInteractionDate != null }
            .sortedByDescending { it.lastInteractionDate }
            .take(20)
    }

    // Pick of the Day (macOS deterministic pseudo-random formula based on day-of-year)
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

    // Hero Spotlight: top active or loved title with backdrop or poster
    val spotlightItem = remember(continueWatching, lovedItems, items) {
        continueWatching.firstOrNull { it.backdropUrl != null || it.posterUrl != null }
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
        contentPadding = PaddingValues(bottom = 36.dp),
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
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = AppColors.Accent
                            ) {
                                Text(
                                    text = if (spotlightItem.state.equals("Watching", ignoreCase = true) || spotlightItem.state.equals("Active", ignoreCase = true)) "NOW WATCHING" else "FEATURED SPOTLIGHT",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
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

        // 2. macOS-Style Top Focus Section Bar (For You [Watchlist Only] / Recently Watched / Pick of the Day)
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
                                            HomePosterCard(item = item, onClick = { onSelectMedia(item.id) })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Quick Category Switcher Chips (All, Watching, Coming Soon, Recently Added, etc.)
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val categories = listOf(
                    "All",
                    "Watching (${continueWatching.size})",
                    "Coming Soon (${comingSoonItems.size})",
                    "Recently Added (${recentlyAddedItems.size})",
                    "Favorites (${lovedItems.size})",
                    "Watchlist (${wishlistItems.size})",
                    "Completed (${recentlyCompleted.size})"
                )
                items(categories) { cat ->
                    val cleanName = cat.substringBefore(" ")
                    val isSelected = selectedCategoryChip == cleanName
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedCategoryChip = cleanName },
                        label = { Text(cat, fontSize = 12.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) }
                    )
                }
            }
        }

        // 4. CONTINUE WATCHING (Ready Now Only — No Extra Sub-filters, Landscape Cards with Next Ep & Progress)
        if ((selectedCategoryChip == "All" || selectedCategoryChip == "Watching") && continueWatching.isNotEmpty()) {
            item {
                Column {
                    SectionHeader(
                        title = "Continue Watching",
                        count = continueWatching.size,
                        badgeColor = AppColors.Liked
                    )
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
                                onClick = { onSelectMedia(item.id) }
                            )
                        }
                    }
                }
            }
        }

        // 5. COMING SOON (Upcoming Releases + Airing Soon Next Episodes)
        if ((selectedCategoryChip == "All" || selectedCategoryChip == "Coming") && comingSoonItems.isNotEmpty()) {
            item {
                Column {
                    SectionHeader(
                        title = "Coming Soon",
                        count = comingSoonItems.size,
                        badgeColor = AppColors.Gold
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(comingSoonItems.take(25), key = { it.id }) { item ->
                            val progress = showProgressMap[item.id]
                            HomePosterCard(
                                item = item,
                                nextAirDate = progress?.nextAiringDate ?: (if (item.releaseDate != null && item.releaseDate > todayDateStr) item.releaseDate else null),
                                onClick = { onSelectMedia(item.id) }
                            )
                        }
                    }
                }
            }
        }

        // 6. RECENTLY ADDED
        if ((selectedCategoryChip == "All" || selectedCategoryChip == "Recently") && recentlyAddedItems.isNotEmpty()) {
            item {
                Column {
                    SectionHeader(title = "Recently Added", count = recentlyAddedItems.size)
                    Spacer(modifier = Modifier.height(10.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(recentlyAddedItems.take(20), key = { it.id }) { item ->
                            HomePosterCard(item = item, onClick = { onSelectMedia(item.id) })
                        }
                    }
                }
            }
        }

        // 7. LOVED FAVORITES
        if ((selectedCategoryChip == "All" || selectedCategoryChip == "Favorites") && lovedItems.isNotEmpty()) {
            item {
                Column {
                    SectionHeader(title = "Loved Favorites (❤️)", count = lovedItems.size, badgeColor = AppColors.Loved)
                    Spacer(modifier = Modifier.height(10.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(lovedItems, key = { it.id }) { item ->
                            HomePosterCard(item = item, onClick = { onSelectMedia(item.id) })
                        }
                    }
                }
            }
        }

        // 8. WATCHLIST
        if ((selectedCategoryChip == "All" || selectedCategoryChip == "Watchlist") && wishlistItems.isNotEmpty()) {
            item {
                Column {
                    SectionHeader(title = "From Your Watchlist", count = wishlistItems.size)
                    Spacer(modifier = Modifier.height(10.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(wishlistItems.take(20), key = { it.id }) { item ->
                            HomePosterCard(item = item, onClick = { onSelectMedia(item.id) })
                        }
                    }
                }
            }
        }

        // 9. COMPLETED
        if ((selectedCategoryChip == "All" || selectedCategoryChip == "Completed") && recentlyCompleted.isNotEmpty()) {
            item {
                Column {
                    SectionHeader(title = "Completed", count = recentlyCompleted.size)
                    Spacer(modifier = Modifier.height(10.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(recentlyCompleted.take(20), key = { it.id }) { item ->
                            HomePosterCard(item = item, onClick = { onSelectMedia(item.id) })
                        }
                    }
                }
            }
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
    onClick: () -> Unit
) {
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
            // Backdrop Image (with Poster fallback)
            AsyncImage(
                model = item.backdropUrl ?: item.posterUrl,
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
                // TV / Film Chip
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
fun SectionHeader(
    title: String,
    count: Int,
    badgeColor: Color = MaterialTheme.colorScheme.primaryContainer
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 16.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.width(6.dp))
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (badgeColor == MaterialTheme.colorScheme.primaryContainer) badgeColor else badgeColor.copy(alpha = 0.2f)
        ) {
            Text(
                text = count.toString(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (badgeColor == MaterialTheme.colorScheme.primaryContainer) MaterialTheme.colorScheme.onPrimaryContainer else badgeColor,
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
fun HomePosterCard(
    item: MediaItemEntity,
    nextAirDate: String? = null,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        modifier = Modifier
            .width(136.dp)
            .clickable(onClick = onClick)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(195.dp)
            ) {
                if (item.posterUrl != null) {
                    AsyncImage(
                        model = item.posterUrl,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
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

                // Type Badge (top-left)
                Surface(
                    color = Color.Black.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(bottomEnd = 8.dp),
                    modifier = Modifier.align(Alignment.TopStart)
                ) {
                    Text(
                        text = if (item.type.contains("TV", ignoreCase = true)) "TV" else "FILM",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                // Air Date or Taste Badge (top-right)
                if (nextAirDate != null) {
                    Surface(
                        color = AppColors.Gold.copy(alpha = 0.9f),
                        shape = RoundedCornerShape(bottomStart = 8.dp),
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                Icons.Default.Schedule,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(9.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = nextAirDate.takeLast(5), // e.g. "10-15"
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                        }
                    }
                } else if (item.taste == "Loved" || item.taste == "Love") {
                    Surface(
                        color = AppColors.Loved.copy(alpha = 0.9f),
                        shape = RoundedCornerShape(bottomStart = 8.dp),
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier
                                .padding(4.dp)
                                .size(12.dp)
                        )
                    }
                }
            }

            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    text = item.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                val dateLabel = nextAirDate ?: item.releaseDate?.take(4)
                if (!dateLabel.isNullOrBlank()) {
                    Text(
                        text = if (nextAirDate != null) "Airs $nextAirDate" else dateLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (nextAirDate != null) AppColors.Gold else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
