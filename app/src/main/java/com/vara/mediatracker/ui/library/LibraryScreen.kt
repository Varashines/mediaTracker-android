package com.vara.mediatracker.ui.library

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.vara.mediatracker.ui.common.AppToast
import com.vara.mediatracker.ui.common.EmptyState
import com.vara.mediatracker.ui.common.ExpressiveCard
import com.vara.mediatracker.ui.common.ExpressiveSegmentedControl
import com.vara.mediatracker.ui.common.LibraryViewModel
import com.vara.mediatracker.data.api.TMDBClient
import com.vara.mediatracker.data.api.TMDBSearchItem
import com.vara.mediatracker.data.db.AppDatabase
import com.vara.mediatracker.di.AppContainer
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.data.model.MediaIds
import com.vara.mediatracker.ui.common.ChipTone
import com.vara.mediatracker.ui.common.FlatCard
import com.vara.mediatracker.ui.common.StatusChip
import com.vara.mediatracker.ui.theme.AppSpacing
import com.vara.mediatracker.ui.theme.ExpressiveShapes
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
    var filters by remember { mutableStateOf(LibraryFilters()) }
    var showFilters by remember { mutableStateOf(false) }
    var showGroup by remember { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }
    var isSearchingOnline by remember { mutableStateOf(false) }
    var onlineResults by remember { mutableStateOf<List<TMDBSearchItem>>(emptyList()) }


    // The library join is produced once, off the UI thread, by the activity-scoped
    // ViewModel (see LibraryViewModel). The watch streak + stats moved to the Hub.
    val libraryVm: LibraryViewModel = viewModel(factory = LibraryViewModel.factory(container))
    val core by libraryVm.core.collectAsState()
    val badgeLabels = remember(core.badges) { core.badges.mapValues { it.value.badge.label } }
    val badgeCounts = remember(core.badgeRows) { core.badgeRows.groupingBy { it.badgeLabel }.eachCount() }

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

    // Discovery folded into the Library: every facet (network/studio/genre/language) is
    // a filter here, and the grid can be grouped into sections.
    val filteredItems = remember(items, searchQuery, filters) {
        LibraryQuery.apply(items, searchQuery, filters, badgeLabels)
    }
    val sections = remember(filteredItems, filters.groupBy) {
        LibraryQuery.group(filteredItems, filters.groupBy)
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
            contentPadding = PaddingValues(start = 12.dp, top = 6.dp, end = 12.dp, bottom = 140.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 1. Top Header: "COLLECTION" / "Media Library" + count pill
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
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
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = AppSpacing.sm),
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
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
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
                        LoadingIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            if (onlineResults.isEmpty() && !isSearchingOnline) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
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
                                        backdropUrl = TMDBClient.buildBackdropUrl(res.backdrop_path),
                                        releaseDate = res.release_date ?: res.first_air_date,
                                        type = if (isMovie) "Movie" else "TV Show",
                                        state = "Wishlist",
                                        lastInteractionDate = now,
                                        lastStateChangeDate = now,
                                        updatedAt = now
                                    )
                                    container.mediaDao.upsertMedia(newItem)
                                    syncManager.pushMediaUpdate(mediaId, newItem.type, "Wishlist", "None")
                                    AppToast.show("Added '$title' to Wishlist")
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
            // 3. M3E Segmented Controls: Type Switcher (All / Movies / TV Shows)
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                ExpressiveSegmentedControl(
                    items = listOf("All", "Movies", "TV Shows"),
                    selectedItem = filters.type,
                    onItemSelected = { filters = filters.copy(type = it) },
                    labelProvider = { it },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 5. Quick state pills + the facet filter / group / sort bar
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                val filterCounts = remember(items, filters.type) {
                    val scoped = items.filter { item ->
                        when (filters.type) {
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

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.foundation.lazy.LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val statusFilters = listOf("All", "Watching", "Wishlist", "Completed", "Loved")
                        items(statusFilters.size, key = { statusFilters[it] }) { idx ->
                            val status = statusFilters[idx]
                            val isSelected = filters.state == status
                            val count = filterCounts[status] ?: 0
                            Surface(
                                onClick = { filters = filters.copy(state = status) },
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
                                        text = status,
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

                    LibraryFilterBar(
                        filters = filters,
                        onFiltersClick = { showFilters = true },
                        onGroupClick = { showGroup = true },
                        onSortClick = { showSort = true }
                    )
                }
            }

            // 6. Posters Grid
            if (filteredItems.isEmpty()) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = if (searchQuery.isNotBlank()) Icons.Default.SearchOff else Icons.Default.VideoLibrary,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(44.dp)
                        )
                        Text(
                            text = if (searchQuery.isNotBlank()) "No matches in library" else "No titles in this view",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (searchQuery.isNotBlank()) "No library titles match \"$searchQuery\" with current filters."
                                   else "Try switching filters or search TMDB to add new titles.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        if (searchQuery.isNotBlank()) {
                            Button(
                                onClick = { searchOnlineTMDB = true },
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Search Online TMDB")
                            }
                        }
                    }
                }
            } else {
                sections.forEach { (groupTitle, groupItems) ->
                    if (groupTitle.isNotEmpty()) {
                        item(
                            span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) },
                            key = "group_$groupTitle"
                        ) {
                            LibraryGroupHeader(title = groupTitle, count = groupItems.size)
                        }
                    }
                    items(groupItems, key = { it.id }) { item ->
                        val isTV = item.type.contains("TV", ignoreCase = true)
                        val eps = if (isTV) core.episodesByShow[MediaIds.tvShowKey(item.id)] else null
                        val watched = eps?.count { it.isWatched } ?: 0
                        val total = eps?.size ?: 0
                        val progressLabel = if (isTV && total > 0) "$watched of $total watched" else item.state
                        LibraryPosterCard(
                            item = item,
                            statusLabel = core.badges[item.id]?.badge?.label,
                            progressLabel = progressLabel,
                            onClick = { onSelectMedia(item.id) }
                        )
                    }
                }
            }
        }
    }

    if (showFilters) {
        LibraryFilterSheet(
            items = items,
            badgeCounts = badgeCounts,
            filters = filters,
            onFiltersChange = { filters = it },
            onDismiss = { showFilters = false }
        )
    }

    if (showGroup) {
        LibraryOptionSheet(
            title = "Group by",
            options = LibraryFilters.GROUPS,
            selected = filters.groupBy,
            onSelect = { filters = filters.copy(groupBy = it) },
            onDismiss = { showGroup = false }
        )
    }

    if (showSort) {
        LibraryOptionSheet(
            title = "Sort by",
            options = LibraryFilters.SORTS,
            selected = filters.sort,
            onSelect = { filters = filters.copy(sort = it) },
            onDismiss = { showSort = false }
        )
    }
}
}


@Composable
fun LibraryPosterCard(
    item: MediaItemEntity,
    statusLabel: String?,
    progressLabel: String,
    onClick: () -> Unit
) {
    // Poster-only M3E tile: full-bleed art with small overlays, no captions.
    FlatCard(
        onClick = onClick,
        shape = ExpressiveShapes.AsymPoster,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
        ) {
            // Cover: poster, falling back to the backdrop when no poster exists.
            val cover = item.posterUrl ?: item.backdropUrl
            if (cover != null) {
                AsyncImage(
                    model = cover,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Movie,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            // Bottom scrim so the title pill stays legible over any artwork.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.62f))
                        )
                    )
            )

            // Title pill — only when there's no poster and we're showing the backdrop.
            if (item.posterUrl == null) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color.Black.copy(alpha = 0.55f),
                    contentColor = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(6.dp)
                ) {
                    Text(
                        text = item.title,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // State chip (top-start).
            if (statusLabel != null) {
                StatusChip(
                    label = statusLabel,
                    tone = ChipTone.Accent,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                )
            }

            if (item.taste.equals("Loved", true) || item.taste.equals("Love", true)) {
                Surface(
                    color = Color(0xFFFF2D55),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
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
    }
}

/** Section header shown when the grid is grouped. */
@Composable
private fun LibraryGroupHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "$count",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Filter by / Group by / Sort by entry points above the grid — one chip each. */
@Composable
private fun LibraryFilterBar(
    filters: LibraryFilters,
    onFiltersClick: () -> Unit,
    onGroupClick: () -> Unit,
    onSortClick: () -> Unit
) {
    androidx.compose.foundation.lazy.LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        item {
            FilterBarChip(
                label = if (filters.activeCount > 0) "Filter by · ${filters.activeCount}" else "Filter by",
                icon = Icons.Default.FilterList,
                highlighted = filters.activeCount > 0,
                onClick = onFiltersClick
            )
        }
        item {
            FilterBarChip(
                label = "Group: ${filters.groupBy}",
                icon = Icons.Default.ViewAgenda,
                highlighted = filters.groupBy != LibraryFilters.GROUP_NONE,
                onClick = onGroupClick
            )
        }
        item {
            FilterBarChip(
                label = "Sort: ${filters.sort}",
                icon = Icons.Default.SwapVert,
                highlighted = false,
                onClick = onSortClick
            )
        }
    }
}

@Composable
private fun FilterBarChip(
    label: String,
    icon: ImageVector,
    highlighted: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(
            1.dp,
            if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryFilterSheet(
    items: List<MediaItemEntity>,
    badgeCounts: Map<String, Int>,
    filters: LibraryFilters,
    onFiltersChange: (LibraryFilters) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Filter by",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.weight(1f)
                )
                if (filters.activeCount > 0) {
                    TextButton(onClick = {
                        onFiltersChange(filters.copy(badges = emptySet(), genres = emptySet(), languages = emptySet()))
                    }) { Text("Clear all") }
                }
            }

            if (badgeCounts.isNotEmpty()) {
                FilterGroup(title = "Badges") {
                    badgeCounts.entries
                        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                        .forEach { (label, count) ->
                            FacetChip(
                                label = "$label ($count)",
                                selected = label in filters.badges,
                                logo = null,
                                onClick = { onFiltersChange(filters.copy(badges = filters.badges.toggle(label))) }
                            )
                        }
                }
            }

            LibraryFacet.entries.forEach { facet ->
                val values = remember(items, facet) { LibraryQuery.facetValues(items, facet).take(24) }
                if (values.isNotEmpty()) {
                    FilterGroup(title = facet.title) {
                        values.forEach { value ->
                            FacetChip(
                                label = if (facet == LibraryFacet.LANGUAGE) LibraryQuery.languageLabel(value) else value,
                                selected = filters.isSelected(facet, value),
                                logo = null,
                                onClick = { onFiltersChange(filters.toggled(facet, value)) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Single-select option sheet for Group by / Sort by. */
@Composable
private fun LibraryOptionSheet(
    title: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            options.forEach { option ->
                val isSelected = option == selected
                Surface(
                    onClick = { onSelect(option) },
                    shape = RoundedCornerShape(16.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = option,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title.uppercase(Locale.US),
            fontSize = 11.sp,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 1.0.sp
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun FacetChip(label: String, selected: Boolean, logo: String?, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (logo != null) {
                AsyncImage(
                    model = TMDBClient.buildLogoUrl(logo),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .height(14.dp)
                        .widthIn(max = 42.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.Black else FontWeight.SemiBold,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

private fun LibraryFilters.isSelected(facet: LibraryFacet, value: String): Boolean = when (facet) {
    LibraryFacet.GENRE -> value in genres
    LibraryFacet.LANGUAGE -> value in languages
}

private fun LibraryFilters.toggled(facet: LibraryFacet, value: String): LibraryFilters = when (facet) {
    LibraryFacet.GENRE -> copy(genres = genres.toggle(value))
    LibraryFacet.LANGUAGE -> copy(languages = languages.toggle(value))
}

private fun Set<String>.toggle(value: String): Set<String> = if (value in this) this - value else this + value
