package com.vara.mediatracker.ui.search

import android.widget.Toast
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
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vara.mediatracker.data.api.TMDBClient
import com.vara.mediatracker.data.api.TMDBSearchItem
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.di.AppContainer
import com.vara.mediatracker.ui.common.EmptyState
import com.vara.mediatracker.ui.common.ExpressiveCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Search and add.
 *
 * Promoted to a primary navigation destination. For a tracker, finding and adding
 * content is the main thing a user does — the old Library screen buried it behind a
 * globe toggle, and Calendar held a nav slot despite being a view of data rather than
 * a place you live.
 *
 * Local matches are shown immediately while the TMDB request runs, so typing never
 * feels blocked on the network.
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

    var query by remember { mutableStateOf("") }
    var remoteResults by remember { mutableStateOf<List<TMDBSearchItem>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchedTerm by remember { mutableStateOf("") }

    val inLibraryIds = remember(items) { items.mapTo(HashSet()) { it.id } }

    val localMatches = remember(items, query) {
        if (query.isBlank()) emptyList()
        else items.filter { it.title.contains(query.trim(), ignoreCase = true) }
    }

    // Debounced remote search. The job is cancelled on every keystroke, so a fast typist
    // issues one request rather than one per character.
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
                ?.take(30)
                .orEmpty()
        }
        searchedTerm = term
        searching = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                androidx.compose.material3.IconButton(
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
            )
        }

        when {
            query.isBlank() -> EmptyState(
                icon = Icons.Default.Search,
                title = "Find something new",
                body = "Search TMDB to add films and shows to your library."
            )

            localMatches.isEmpty() && remoteResults.isEmpty() && !searching -> EmptyState(
                icon = Icons.Default.Search,
                title = "No matches",
                body = "Nothing found for \"${query.trim()}\"."
            )

            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 140.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (localMatches.isNotEmpty()) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
                        Text(
                            text = "In your library",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    items(localMatches, key = { "local_${it.id}" }) { item ->
                        SearchResultCard(
                            posterUrl = item.posterUrl,
                            title = item.title,
                            typeLabel = if (item.type.contains("TV", true)) "TV" else "FILM",
                            inLibrary = true,
                            onClick = { onSelectMedia(item.id) }
                        )
                    }
                }

                if (remoteResults.isNotEmpty()) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
                        Text(
                            text = "On TMDB",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    items(remoteResults, key = { "remote_${it.media_type}_${it.id}" }) { res ->
                        val isMovie = res.media_type == "movie"
                        val mediaId = (if (isMovie) "movie_" else "tv_") + res.id
                        val title = res.title ?: res.name ?: "Untitled"

                        SearchResultCard(
                            posterUrl = TMDBClient.buildPosterUrl(res.poster_path),
                            title = title,
                            typeLabel = if (isMovie) "FILM" else "TV",
                            inLibrary = mediaId in inLibraryIds,
                            onClick = {
                                if (mediaId in inLibraryIds) {
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
                                        container.syncManager().pushMediaUpdate(
                                            mediaId, newItem.type, "Wishlist", "None"
                                        )
                                        Toast.makeText(
                                            context, "Added \"$title\"", Toast.LENGTH_SHORT
                                        ).show()
                                        onSelectMedia(mediaId)
                                    }
                                }
                            }
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
    inLibrary: Boolean,
    onClick: () -> Unit
) {
    ExpressiveCard(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
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
                Surface(
                    color = Color.Black.copy(alpha = 0.72f),
                    shape = RoundedCornerShape(bottomEnd = 8.dp),
                    modifier = Modifier.align(Alignment.TopStart)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            imageVector = if (typeLabel == "TV") Icons.Default.Tv else Icons.Default.Movie,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(10.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = typeLabel,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                    }
                }
                if (inLibrary) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(bottomStart = 8.dp),
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "In library",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(3.dp).size(12.dp)
                        )
                    }
                }
            }
            Text(
                text = title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
            )
        }
    }
}