package com.vara.mediatracker.ui.library

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
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

    Column(modifier = Modifier.fillMaxSize()) {
        // Search Bar + Online Mode Toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { 
                    Text(if (searchOnlineTMDB) "Search TMDB online..." else "Search ${items.size} library titles...") 
                },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(8.dp))

            // TMDB Global Toggle Button
            FilledIconToggleButton(
                checked = searchOnlineTMDB,
                onCheckedChange = { searchOnlineTMDB = it },
                shape = RoundedCornerShape(12.dp),
                colors = IconButtonDefaults.filledIconToggleButtonColors(
                    checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    checkedContentColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Public,
                    contentDescription = "Search Online TMDB"
                )
            }
        }

        if (searchOnlineTMDB) {
            // Online Search View Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "TMDB Online Results",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                if (isSearchingOnline) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }

            if (onlineResults.isEmpty() && !isSearchingOnline) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (searchQuery.length < 2) "Type at least 2 characters to search TMDB" else "No TMDB results found",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(onlineResults, key = { "${it.media_type}_${it.id}" }) { res ->
                        val isMovie = res.media_type == "movie"
                        val mediaId = (if (isMovie) "movie_" else "tv_") + res.id
                        val title = res.title ?: res.name ?: "Untitled"
                        val inLibrary = items.any { it.id == mediaId }

                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                            modifier = Modifier.clickable {
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
                            }
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
                                        color = Color.Black.copy(alpha = 0.7f),
                                        shape = RoundedCornerShape(bottomEnd = 8.dp),
                                        modifier = Modifier.align(Alignment.TopStart)
                                    ) {
                                        Text(
                                            text = if (isMovie) "MOVIE" else "TV",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }

                                    // In Library indicator
                                    if (inLibrary) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.primary,
                                            shape = RoundedCornerShape(bottomStart = 8.dp),
                                            modifier = Modifier.align(Alignment.TopEnd)
                                        ) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = null,
                                                tint = Color.White,
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
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // Local Library Filter Pills (All / Movies / TV Shows)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("All", "Movies", "TV Shows").forEach { type ->
                    FilterChip(
                        selected = selectedType == type,
                        onClick = { selectedType = type },
                        label = { Text(type, fontSize = 12.sp) }
                    )
                }
            }

            // Status Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("All", "Watching", "Wishlist", "Completed", "Loved").forEach { filter ->
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { selectedFilter = filter },
                        label = { Text(filter, fontSize = 11.sp) }
                    )
                }
            }

            // Grid of Library Titles
            if (filteredItems.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (searchQuery.isNotBlank()) "No titles match '$searchQuery'" else "No titles in this section",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 88.dp),
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
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.clickable(onClick = onClick)
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
                }

                if (item.taste == "Loved" || item.taste == "Love") {
                    Surface(
                        color = Color(0xFFE91E63).copy(alpha = 0.9f),
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

            Text(
                text = item.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
    }
}
