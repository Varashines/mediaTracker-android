package com.vara.mediatracker.ui.library

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.vara.mediatracker.ui.common.EmptyState
import com.vara.mediatracker.ui.common.ExpressiveCard
import com.vara.mediatracker.ui.common.ExpressiveSegmentedControl
import com.vara.mediatracker.ui.common.LibraryViewModel
import com.vara.mediatracker.data.api.TMDBClient
import com.vara.mediatracker.data.api.TMDBSearchItem
import com.vara.mediatracker.data.db.AppDatabase
import com.vara.mediatracker.di.AppContainer
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.data.prefs.PreferencesManager
import com.vara.mediatracker.data.sync.CloudSyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    items: List<MediaItemEntity>,
    container: AppContainer,
    onSelectMedia: (String) -> Unit,
    onOpenSearch: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var searchOnlineTMDB by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf("All") }
    var selectedType by remember { mutableStateOf("All") }
    var isSearchingOnline by remember { mutableStateOf(false) }
    var onlineResults by remember { mutableStateOf<List<TMDBSearchItem>>(emptyList()) }

    // Dynamic watch streak & activity calculation. The full ~9k-row episode table used to
    // be collected and scanned here on the main thread; it now comes from the shared,
    // off-main ViewModel join (see LibraryViewModel) and the scan runs on a background
    // dispatcher.
    val libraryVm: LibraryViewModel = viewModel(factory = LibraryViewModel.factory(container))
    val core by libraryVm.core.collectAsState()
    val allEpisodes = core.allEpisodes
    val todayCal = remember { Calendar.getInstance() }
    val todayDateStr = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(todayCal.time) }
    val currentMonthPrefix = remember { SimpleDateFormat("yyyy-MM", Locale.US).format(todayCal.time) }

    val streakActivity by produceState(
        initialValue = 0 to 0,
        allEpisodes, items, todayDateStr, currentMonthPrefix
    ) {
        value = withContext(Dispatchers.Default) {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

            // 1. Gather all unique dates of watched activity
            val watchedDates = mutableSetOf<String>()
            var thisMonthCount = 0

            for (ep in allEpisodes) {
                if (ep.isWatched) {
                    val datePart = ep.watchedDate?.take(10) ?: ep.airDate?.take(10)
                    if (!datePart.isNullOrBlank()) {
                        watchedDates.add(datePart)
                        if (datePart.startsWith(currentMonthPrefix)) {
                            thisMonthCount++
                        }
                    }
                }
            }

            for (item in items) {
                if (item.state.equals("Completed", ignoreCase = true)) {
                    val datePart = item.lastStateChangeDate?.take(10) ?: item.lastInteractionDate?.take(10)
                    if (!datePart.isNullOrBlank()) {
                        watchedDates.add(datePart)
                        if (datePart.startsWith(currentMonthPrefix)) {
                            thisMonthCount++
                        }
                    }
                }
            }

            // 2. Count consecutive days backwards starting from today or yesterday
            var streak = 0
            val checkCal = Calendar.getInstance()
            var checkStr = dateFormat.format(checkCal.time)

            if (!watchedDates.contains(checkStr)) {
                // Check yesterday
                checkCal.add(Calendar.DAY_OF_YEAR, -1)
                checkStr = dateFormat.format(checkCal.time)
            }

            while (watchedDates.contains(checkStr)) {
                streak++
                checkCal.add(Calendar.DAY_OF_YEAR, -1)
                checkStr = dateFormat.format(checkCal.time)
            }

            streak to thisMonthCount
        }
    }
    val streakDays = streakActivity.first
    val watchedThisMonth = streakActivity.second

    val syncManager = remember { container.syncManager() }

    // Trigger TMDB search when enabled
    LaunchedEffect(searchQuery, searchOnlineTMDB) {
        if (searchOnlineTMDB && searchQuery.trim().length >= 2 && container.preferences.tmdbApiKey.isNotBlank()) {
            isSearchingOnline = true
            withContext(Dispatchers.IO) {
                try {
                    val client = container.tmdbClient()
                    val res = client.searchMulti(searchQuery.trim())
                    onlineResults = res.getOrNull()?.results?.filter { 
                        it.media_type == "movie" || it.media_type == "tv" 
                    } ?: emptyList()
                } catch (e: Exception) {
                    onlineResults = emptyList()
                } finally {
                    isSearchingOnline = false
                }
            }
        } else if (!searchOnlineTMDB) {
            onlineResults = emptyList()
        }
    }

    val filteredItems = remember(items, searchQuery, selectedFilter, selectedType) {
        items.filter { item ->
            val matchesQuery = searchQuery.isBlank() || item.title.contains(searchQuery, ignoreCase = true)
            val matchesFilter = when (selectedFilter) {
                "All" -> true
                "Watching" -> item.state.equals("Watching", ignoreCase = true) || item.state.equals("Active", ignoreCase = true)
                "Wishlist" -> item.state.equals("Wishlist", ignoreCase = true)
                "Completed" -> item.state.equals("Completed", ignoreCase = true)
                "Loved" -> item.taste.equals("Loved", ignoreCase = true) || item.taste.equals("Love", ignoreCase = true)
                else -> true
            }
            val matchesType = when (selectedType) {
                "All" -> true
                "Movies" -> item.type.contains("Movie", ignoreCase = true)
                "TV Shows" -> item.type.contains("TV", ignoreCase = true)
                else -> true
            }
            matchesQuery && matchesFilter && matchesType
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, top = 6.dp, end = 14.dp, bottom = 108.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Top Header: "COLLECTION" / "Media Library" + count pill
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "COLLECTION",
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
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Media Library",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Interactive count pill
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                ) {
                    Text(
                        text = "${filteredItems.size} TITLES",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // 2. Search Bar + Online TMDB mode pill
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        androidx.compose.foundation.text.BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            modifier = Modifier.weight(1f),
                            decorationBox = { innerTextField ->
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        text = if (searchOnlineTMDB) "Search TMDB online..." else "Filter library titles...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                                    )
                                }
                                innerTextField()
                            }
                        )
                        if (searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = { searchQuery = "" },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                // TMDB Global Toggle Button
                Surface(
                    onClick = { searchOnlineTMDB = !searchOnlineTMDB },
                    shape = RoundedCornerShape(16.dp),
                    color = if (searchOnlineTMDB) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = Icons.Default.Public,
                            contentDescription = "Search Online TMDB",
                            tint = if (searchOnlineTMDB) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        if (searchOnlineTMDB) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "TMDB DISCOVERY RESULTS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.5.sp
                    )
                    if (isSearchingOnline) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            if (onlineResults.isEmpty() && !isSearchingOnline) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (searchQuery.length < 2) "Type at least 2 characters to discover on TMDB" else "No TMDB results found",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(onlineResults, key = { "${it.media_type}_${it.id}" }) { res ->
                    val isMovie = res.media_type == "movie"
                    val mediaId = (if (isMovie) "movie_" else "tv_") + res.id
                    val title = res.title ?: res.name ?: "Untitled"
                    val inLibrary = items.any { it.id == mediaId }

                    ExpressiveCard(
                        onClick = {
                            if (inLibrary) {
                                onSelectMedia(mediaId)
                            } else {
                                scope.launch {
                                    val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())
                                    val newItem = MediaItemEntity(
                                        id = mediaId,
                                        title = title,
                                        overview = res.overview ?: "",
                                        posterUrl = TMDBClient.buildPosterUrl(res.poster_path),
                                        backdropUrl = TMDBClient.buildPosterUrl(res.backdrop_path),
                                        releaseDate = res.release_date ?: res.first_air_date,
                                        type = if (isMovie) "Movie" else "TV Show",
                                        state = "Wishlist",
                                        lastInteractionDate = now,
                                        lastStateChangeDate = now,
                                        updatedAt = now
                                    )
                                    container.mediaDao.upsertMedia(newItem)
                                    syncManager.pushMediaUpdate(mediaId, newItem.type, "Wishlist", "None")
                                    Toast.makeText(context, "Added '$title' to Wishlist", Toast.LENGTH_SHORT).show()
                                    onSelectMedia(mediaId)
                                }
                            }
                        },
                        shape = RoundedCornerShape(16.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
                        tonalElevation = 2.dp,
                        pressedScale = 0.96f
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(2f / 3f)
                        ) {
                            if (res.poster_path != null) {
                                AsyncImage(
                                    model = TMDBClient.buildPosterUrl(res.poster_path),
                                    contentDescription = title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            // Type Tag
                            Surface(
                                color = Color.Black.copy(alpha = 0.70f),
                                shape = RoundedCornerShape(bottomEnd = 10.dp),
                                modifier = Modifier.align(Alignment.TopStart)
                            ) {
                                Box(modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                                    Icon(
                                        imageVector = if (isMovie) Icons.Default.Movie else Icons.Default.Tv,
                                        contentDescription = if (isMovie) "Movie" else "TV Show",
                                        tint = Color.White,
                                        modifier = Modifier.size(11.dp)
                                    )
                                }
                            }

                            if (inLibrary) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = RoundedCornerShape(bottomStart = 10.dp),
                                    modifier = Modifier.align(Alignment.TopEnd)
                                ) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier
                                            .padding(4.dp)
                                            .size(12.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // 3. M3E Expressive Streak Banner
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
                Surface(
                    shape = com.vara.mediatracker.ui.theme.ExpressiveShapes.Card,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (streakDays > 0) "🔥" else "✨",
                                    fontSize = 13.sp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (streakDays > 0) "$streakDays DAY WATCH STREAK" else "START A WATCH STREAK",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Black,
                                    color = MaterialTheme.colorScheme.primary,
                                    letterSpacing = 0.8.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = when {
                                    streakDays >= 7 -> "Unstoppable momentum!"
                                    streakDays >= 3 -> "You're in the zone!"
                                    streakDays >= 1 -> "Great start! Keep going!"
                                    else -> "Pick something to watch!"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (watchedThisMonth > 0) {
                                    "$watchedThisMonth watched this month • ${items.count { it.state.equals("Watching", true) || it.state.equals("Active", true) }} active shows"
                                } else {
                                    "${items.count { it.state.equals("Watching", true) || it.state.equals("Active", true) }} active shows • ${items.count { it.state.equals("Completed", true) }} completed"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Text(
                                    text = "🍿",
                                    fontSize = 20.sp
                                )
                            }
                        }
                    }
                }
            }

            // 4. M3E Segmented Controls: Type Switcher (All / Movies / TV Shows)
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
                ExpressiveSegmentedControl(
                    items = listOf("All", "Movies", "TV Shows"),
                    selectedItem = selectedType,
                    onItemSelected = { selectedType = it },
                    labelProvider = { it },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 5. M3E Horizontal Scrolling Status Filter Pills with item counts
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
                val filterCounts = remember(items, selectedType) {
                    val scoped = items.filter { item ->
                        when (selectedType) {
                            "Movies" -> item.type.contains("Movie", ignoreCase = true)
                            "TV Shows" -> item.type.contains("TV", ignoreCase = true)
                            else -> true
                        }
                    }
                    mapOf(
                        "All" to scoped.size,
                        "Watching" to scoped.count { it.state.equals("Watching", true) || it.state.equals("Active", true) },
                        "Wishlist" to scoped.count { it.state.equals("Wishlist", true) },
                        "Completed" to scoped.count { it.state.equals("Completed", true) },
                        "Loved" to scoped.count { it.taste.equals("Loved", true) || it.taste.equals("Love", true) }
                    )
                }

                androidx.compose.foundation.lazy.LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val filters = listOf("All", "Watching", "Wishlist", "Completed", "Loved")
                    items(filters.size) { idx ->
                        val filter = filters[idx]
                        val isSelected = selectedFilter == filter
                        val count = filterCounts[filter] ?: 0
                        Surface(
                            onClick = { selectedFilter = filter },
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = filter,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Black else FontWeight.SemiBold,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "($count)",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }
            }

            // 6. Posters Grid
            if (filteredItems.isEmpty()) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
                    EmptyState(
                        icon = if (searchQuery.isNotBlank()) Icons.Default.SearchOff else Icons.Default.VideoLibrary,
                        title = if (searchQuery.isNotBlank()) "No matches found" else "Empty Collection",
                        body = if (searchQuery.isNotBlank()) "No titles match \"$searchQuery\" in this filter."
                               else "Add titles by searching online TMDB or sync from Mac."
                    )
                }
            } else {
                items(filteredItems, key = { it.id }) { item ->
                    LibraryPosterCard(item = item, onClick = { onSelectMedia(item.id) })
                }
            }
        }
    }
}
}

@Composable
fun LibraryPosterCard(item: MediaItemEntity, onClick: () -> Unit) {
    // Clean Poster-only card with M3E rounded squircle geometry
    val cardShape = RoundedCornerShape(16.dp)

    ExpressiveCard(
        onClick = onClick,
        shape = cardShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.20f)),
        tonalElevation = 2.dp,
        pressedScale = 0.95f
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
        ) {
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
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Movie,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            // Subtle Type Icon Badge (Top-left)
            val isTV = item.type.contains("TV", ignoreCase = true)
            Surface(
                color = Color.Black.copy(alpha = 0.65f),
                shape = RoundedCornerShape(bottomEnd = 10.dp),
                modifier = Modifier.align(Alignment.TopStart)
            ) {
                Box(modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                    Icon(
                        imageVector = if (isTV) Icons.Default.Tv else Icons.Default.Movie,
                        contentDescription = if (isTV) "TV Show" else "Movie",
                        tint = Color.White,
                        modifier = Modifier.size(11.dp)
                    )
                }
            }

            // Loved Heart indicator (Top-right)
            if (item.taste.equals("Loved", true) || item.taste.equals("Love", true)) {
                Surface(
                    color = Color(0xFFFF2D55),
                    shape = RoundedCornerShape(bottomStart = 10.dp),
                    modifier = Modifier.align(Alignment.TopEnd)
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
    }
}
