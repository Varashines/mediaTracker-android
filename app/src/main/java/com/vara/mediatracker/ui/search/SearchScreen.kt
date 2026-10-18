package com.vara.mediatracker.ui.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.vara.mediatracker.data.api.TMDBClient
import com.vara.mediatracker.data.api.TMDBSearchItem
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.di.AppContainer
import com.vara.mediatracker.ui.common.AppToast
import com.vara.mediatracker.ui.common.EmptyState
import com.vara.mediatracker.ui.common.ExpressiveCard
import com.vara.mediatracker.ui.theme.AppTextStyles
import com.vara.mediatracker.ui.theme.LocalSemanticColors
import com.vara.mediatracker.util.WireTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Locale

/**
 * Search and add media destination.
 *
 * Provides instant local library matches, debounced TMDB multi-search,
 * category filters (All, Movies, TV Shows, In Library), and rich card metadata
 * with ratings and release year.
 */
@Composable
fun SearchScreen(
    items: List<MediaItemEntity>,
    container: AppContainer,
    onSelectMedia: (String) -> Unit,
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sem = LocalSemanticColors.current

    var query by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("All") } // "All", "Movies", "TV Shows", "In Library"
    var remoteResults by remember { mutableStateOf<List<TMDBSearchItem>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchedTerm by remember { mutableStateOf("") }
    var trending by remember { mutableStateOf<List<TMDBSearchItem>>(emptyList()) }

    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    // Trending (TMDB, this week) shown before the user types.
    LaunchedEffect(container.preferences.tmdbApiKey) {
        if (container.preferences.tmdbApiKey.isNotBlank()) {
            trending = withContext(Dispatchers.IO) {
                container.tmdbClient().getTrending().getOrNull()?.results
                    ?.filter { it.media_type == "movie" || it.media_type == "tv" }
                    ?.take(24)
                    .orEmpty()
            }
        }
    }

    val inLibraryMap = remember(items) { items.associateBy { it.id } }

    val openOrAdd: (TMDBSearchItem) -> Unit = { res ->
        val isMovie = res.media_type == "movie"
        val mediaId = (if (isMovie) "movie_" else "tv_") + res.id
        val title = res.title ?: res.name ?: "Untitled"
        if (mediaId in inLibraryMap) {
            onSelectMedia(mediaId)
        } else {
            scope.launch {
                val now = WireTime.now()
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
                    dateAdded = now,
                    updatedAt = now
                )
                container.mediaDao.upsertMedia(newItem)
                container.syncManager().pushMediaUpdate(mediaId, newItem.type, "Wishlist", "None", dateAdded = now)
                AppToast.show("Added \"$title\" to Watchlist")
                onSelectMedia(mediaId)
            }
        }
    }

    val localMatches = remember(items, query, selectedFilter) {
        if (query.isBlank()) emptyList()
        else items.filter { item ->
            val matchesQuery = item.title.contains(query.trim(), ignoreCase = true)
            val matchesFilter = when (selectedFilter) {
                "Movies" -> item.type.contains("Movie", ignoreCase = true)
                "TV Shows" -> item.type.contains("TV", ignoreCase = true)
                else -> true
            }
            matchesQuery && matchesFilter
        }
    }

    // Debounced remote search
    LaunchedEffect(query) {
        val term = query.trim()
        if (term.length < 2 || container.preferences.tmdbApiKey.isBlank()) {
            remoteResults = emptyList()
            searching = false
            return@LaunchedEffect
        }
        delay(350)
        searching = true
        remoteResults = withContext(Dispatchers.IO) {
            container.tmdbClient().searchMulti(term).getOrNull()?.results
                ?.filter { it.media_type == "movie" || it.media_type == "tv" }
                ?.take(36)
                .orEmpty()
        }
        searchedTerm = term
        searching = false
    }

    // Filter remote results. The "ON TMDB" section only lists titles you don't already own —
    // owned ones already appear in "IN YOUR LIBRARY", so showing them in both is duplication.
    // The "In Library" chip is the explicit inverse (it exists to show the TMDB rows you own).
    val filteredRemoteResults = remember(remoteResults, selectedFilter, inLibraryMap) {
        remoteResults.filter { res ->
            val isMovie = res.media_type == "movie"
            val mediaId = (if (isMovie) "movie_" else "tv_") + res.id
            val inLibrary = mediaId in inLibraryMap
            when (selectedFilter) {
                "Movies" -> isMovie && !inLibrary
                "TV Shows" -> !isMovie && !inLibrary
                "In Library" -> inLibrary
                else -> !inLibrary
            }
        }
    }

    // Filter trending results
    val filteredTrending = remember(trending, selectedFilter, inLibraryMap) {
        trending.filter { res ->
            val isMovie = res.media_type == "movie"
            val mediaId = (if (isMovie) "movie_" else "tv_") + res.id
            when (selectedFilter) {
                "Movies" -> isMovie
                "TV Shows" -> !isMovie
                "In Library" -> mediaId in inLibraryMap
                else -> true
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        // Search Input Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = {
                    Text(
                        "Search movies, TV shows, cast...",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(
                            onClick = { query = "" },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Clear",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                ),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .focusRequester(focusRequester)
            )
        }

        // Category Filter Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("All", "Movies", "TV Shows", "In Library").forEach { filter ->
                val isSelected = selectedFilter == filter
                Surface(
                    onClick = { selectedFilter = filter },
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                    modifier = Modifier.padding(vertical = 2.dp)
                ) {
                    Text(
                        text = filter,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        when {
            query.isBlank() && filteredTrending.isNotEmpty() -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 140.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "TRENDING THIS WEEK",
                            style = AppTextStyles.MonoLabel,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "${filteredTrending.size} TITLES",
                            style = AppTextStyles.MonoLabel,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(filteredTrending, key = { "trend_${it.media_type}_${it.id}" }) { res ->
                    val isMovie = res.media_type == "movie"
                    val mediaId = (if (isMovie) "movie_" else "tv_") + res.id
                    val libItem = inLibraryMap[mediaId]
                    SearchResultCard(
                        posterUrl = TMDBClient.buildPosterUrl(res.poster_path),
                        title = res.title ?: res.name ?: "Untitled",
                        typeLabel = if (isMovie) "FILM" else "TV",
                        year = (res.release_date ?: res.first_air_date)?.take(4),
                        rating = res.vote_average,
                        libraryStatus = libItem?.state,
                        inLibrary = libItem != null,
                        onClick = { openOrAdd(res) }
                    )
                }
            }

            query.isBlank() -> EmptyState(
                icon = Icons.Default.Search,
                title = "Find something new",
                body = "Search TMDB to discover and track movies and TV shows."
            )

            localMatches.isEmpty() && filteredRemoteResults.isEmpty() && !searching -> EmptyState(
                icon = Icons.Default.Search,
                title = "No matches",
                body = "Nothing found for \"${query.trim()}\"."
            )

            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 140.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (localMatches.isNotEmpty()) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
                        Text(
                            text = "IN YOUR LIBRARY (${localMatches.size})",
                            style = AppTextStyles.MonoLabel,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    items(localMatches, key = { "local_${it.id}" }) { item ->
                        SearchResultCard(
                            posterUrl = item.posterUrl,
                            title = item.title,
                            typeLabel = if (item.type.contains("TV", true)) "TV" else "FILM",
                            year = item.releaseDate?.take(4),
                            rating = container.preferences.getCachedVoteAverage(item.id)?.toDouble(),
                            libraryStatus = item.state,
                            inLibrary = true,
                            onClick = { onSelectMedia(item.id) }
                        )
                    }
                }

                if (filteredRemoteResults.isNotEmpty()) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
                        Text(
                            text = "ON TMDB (${filteredRemoteResults.size})",
                            style = AppTextStyles.MonoLabel,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )
                    }
                    items(filteredRemoteResults, key = { "remote_${it.media_type}_${it.id}" }) { res ->
                        val isMovie = res.media_type == "movie"
                        val mediaId = (if (isMovie) "movie_" else "tv_") + res.id
                        val libItem = inLibraryMap[mediaId]

                        SearchResultCard(
                            posterUrl = TMDBClient.buildPosterUrl(res.poster_path),
                            title = res.title ?: res.name ?: "Untitled",
                            typeLabel = if (isMovie) "FILM" else "TV",
                            year = (res.release_date ?: res.first_air_date)?.take(4),
                            rating = res.vote_average,
                            libraryStatus = libItem?.state,
                            inLibrary = libItem != null,
                            onClick = { openOrAdd(res) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultCard(
    posterUrl: String?,
    title: String,
    typeLabel: String,
    year: String?,
    rating: Double?,
    libraryStatus: String?,
    inLibrary: Boolean,
    onClick: () -> Unit
) {
    val sem = LocalSemanticColors.current

    ExpressiveCard(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        tonalElevation = 2.dp,
        pressedScale = 0.95f
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
            ) {
                if (posterUrl != null) {
                    AsyncImage(
                        model = posterUrl,
                        contentDescription = title,
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
                            imageVector = if (typeLabel == "TV") Icons.Default.Tv else Icons.Default.Movie,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                // Type & Rating Pill at Top-Start
                Surface(
                    color = Color.Black.copy(alpha = 0.72f),
                    shape = RoundedCornerShape(bottomEnd = 10.dp),
                    modifier = Modifier.align(Alignment.TopStart)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = typeLabel,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                        if (rating != null && rating > 0) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                Icons.Default.Star,
                                contentDescription = null,
                                tint = sem.gold,
                                modifier = Modifier.size(9.dp)
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = String.format(Locale.US, "%.1f", rating),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                color = Color.White
                            )
                        }
                    }
                }

                // Library state indicator at Top-End
                if (inLibrary) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(bottomStart = 10.dp),
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp)
                        ) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "In library",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(10.dp)
                            )
                            if (libraryStatus != null) {
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text = libraryStatus.take(4).uppercase(Locale.US),
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }
                    }
                }
            }

            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp)) {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (!year.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = year,
                        style = AppTextStyles.MonoLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}