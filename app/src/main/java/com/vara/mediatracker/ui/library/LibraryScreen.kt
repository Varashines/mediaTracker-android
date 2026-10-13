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
import coil.compose.AsyncImage
import com.vara.mediatracker.ui.common.EmptyState
import com.vara.mediatracker.ui.common.ExpressiveCard
import com.vara.mediatracker.ui.common.ExpressiveSegmentedControl
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
    onSelectMedia: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var searchOnlineTMDB by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf("All") }
    var selectedType by remember { mutableStateOf("All") }
    var isSearchingOnline by remember { mutableStateOf(false) }
    var onlineResults by remember { mutableStateOf<List<TMDBSearchItem>>(emptyList()) }

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
    ) {
        // 1. Sticky M3E Top Header matching HTML: "COLLECTION" / "Media Library" + count badge
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 6.dp),
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
                            .background(Color(0xFF6DDFB8))
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

        // 2. M3E Tactile Search Bar + Online TMDB mode pill
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
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

            // TMDB Global Toggle Button (M3E squircle)
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

        if (searchOnlineTMDB) {
            // Online Search View Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
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

            if (onlineResults.isEmpty() && !isSearchingOnline) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (searchQuery.length < 2) "Type at least 2 characters to discover on TMDB" else "No TMDB results found",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(start = 14.dp, top = 8.dp, end = 14.dp, bottom = 108.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
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
                            shape = RoundedCornerShape(18.dp),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
                            tonalElevation = 2.dp,
                            pressedScale = 0.96f
                        ) {
                            Column {
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
                                        color = Color.Black.copy(alpha = 0.75f),
                                        shape = RoundedCornerShape(bottomEnd = 10.dp),
                                        modifier = Modifier.align(Alignment.TopStart)
                                    ) {
                                        Text(
                                            text = if (isMovie) "FILM" else "TV",
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Black,
                                            color = Color.White,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }

                                    // In Library indicator
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

                                Text(
                                    text = title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // 3. M3E Segmented Controls: Type Switcher (All / Movies / TV Shows)
            ExpressiveSegmentedControl(
                items = listOf("All", "Movies", "TV Shows"),
                selectedItem = selectedType,
                onItemSelected = { selectedType = it },
                labelProvider = { it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )

            // 4. M3E Horizontal Scrolling Status Filter Pills
            androidx.compose.foundation.lazy.LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val filters = listOf("All", "Watching", "Wishlist", "Completed", "Loved")
                items(filters.size) { idx ->
                    val filter = filters[idx]
                    val isSelected = selectedFilter == filter
                    Surface(
                        onClick = { selectedFilter = filter },
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                        )
                    ) {
                        Text(
                            text = filter,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Black else FontWeight.SemiBold,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            // 5. Grid of Library Titles with M3E Asymmetric Squircle Cards
            if (filteredItems.isEmpty()) {
                EmptyState(
                    icon = if (searchQuery.isNotBlank()) Icons.Default.SearchOff else Icons.Default.VideoLibrary,
                    title = if (searchQuery.isNotBlank()) "No matches found" else "Empty Collection",
                    body = if (searchQuery.isNotBlank()) "No titles match \"$searchQuery\" in this filter."
                           else "Add titles by searching online TMDB or sync from Mac."
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(start = 14.dp, top = 8.dp, end = 14.dp, bottom = 108.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
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
    ExpressiveCard(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
        tonalElevation = 2.dp,
        pressedScale = 0.95f
    ) {
        Column {
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

                // Status or Type Badge
                Surface(
                    color = Color.Black.copy(alpha = 0.70f),
                    shape = RoundedCornerShape(bottomEnd = 10.dp),
                    modifier = Modifier.align(Alignment.TopStart)
                ) {
                    Text(
                        text = if (item.type.contains("TV", ignoreCase = true)) "TV" else "FILM",
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                if (item.taste == "Loved" || item.taste == "Love") {
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
                                .size(12.dp)
                        )
                    }
                }
            }

            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                Text(
                    text = item.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = item.state.uppercase(),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (item.state.equals("Watching", ignoreCase = true)) Color(0xFF6DDFB8) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    letterSpacing = 0.4.sp
                )
            }
        }
    }
}
