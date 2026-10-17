package com.vara.mediatracker.ui.m3e

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.vara.mediatracker.data.TrackingLogic
import com.vara.mediatracker.data.api.TMDBClient
import com.vara.mediatracker.data.api.TMDBSearchItem
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.di.AppContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Search, in the [M3E] vocabulary, over the library and TMDB.
 *
 * Local matching stays in memory over the library the shell already holds — the real screen does
 * the same, and there is no SQL search to call. TMDB is searched on a short debounce so a typed
 * query costs one request rather than one per keystroke, and a result already in the library opens
 * straight away while one that is not is added the way the real screen adds it: a wishlist row,
 * then the push that tells the other devices about it.
 *
 * The tile says which of the two it is. A result you own is a title; one you do not is an offer.
 */
@Composable
fun M3ERoughSearch(
    library: List<MediaItemEntity>,
    container: AppContainer,
    onOpen: (MediaItemEntity) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var remote by remember { mutableStateOf(emptyList<TMDBSearchItem>()) }
    var searching by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf<String?>(null) }

    val owned = remember(library) { library.associateBy { it.id } }
    val local = remember(library, query) {
        if (query.isBlank()) {
            emptyList()
        } else {
            library.filter { it.title.contains(query, ignoreCase = true) }.take(24)
        }
    }

    // Debounced: a search request per keystroke is a request per keystroke, and TMDB is rate
    // limited. Two characters is the floor — one letter matches almost everything.
    LaunchedEffect(query) {
        val term = query.trim()
        if (term.length < 2) {
            remote = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(350)
        remote = runCatching {
            container.tmdbClient().searchMulti(term).getOrNull()?.results
        }.getOrNull()
            .orEmpty()
            .filter { it.media_type == "movie" || it.media_type == "tv" }
            .take(24)
        searching = false
    }

    fun openOrAdd(result: TMDBSearchItem) {
        val isMovie = result.media_type == "movie"
        val mediaId = (if (isMovie) "movie_" else "tv_") + result.id
        owned[mediaId]?.let { existing ->
            onOpen(existing)
            return
        }

        val title = result.title ?: result.name ?: return
        val item = MediaItemEntity(
            id = mediaId,
            title = title,
            overview = result.overview.orEmpty(),
            posterUrl = TMDBClient.buildPosterUrl(result.poster_path),
            backdropUrl = TMDBClient.buildBackdropUrl(result.backdrop_path),
            releaseDate = result.release_date ?: result.first_air_date,
            type = if (isMovie) "Movie" else "TV",
            state = "Wishlist",
            dateAdded = TrackingLogic.isoNow()
        )

        adding = mediaId
        scope.launch {
            container.mediaDao.upsertMedia(item)
            container.syncManager().pushMediaUpdate(
                mediaId = item.id,
                type = item.type,
                state = item.state,
                taste = item.taste,
                isStateChange = false,
                dateAdded = item.dateAdded
            )
            adding = null
            onOpen(item)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(M3EColor.page())
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = M3ESpacing.tightGap, vertical = M3ESpacing.tightGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = M3EColor.text()
                )
            }
            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Search titles", style = M3EType.rowSupporting()) },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, tint = M3EColor.textMuted())
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Clear",
                                tint = M3EColor.textMuted()
                            )
                        }
                    }
                },
                singleLine = true,
                shape = M3EShape.chip(),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = M3EColor.card(),
                    unfocusedContainerColor = M3EColor.card()
                )
            )
        }
        Spacer(Modifier.height(M3ESpacing.tightGap))

        when {
            query.isBlank() -> M3ETonalCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = M3ESpacing.page)
            ) {
                Column(modifier = Modifier.padding(M3ESpacing.cardGap)) {
                    Text("Search your library", style = M3EType.rowTitle(), color = M3EColor.text())
                    Text(
                        text = "Titles you own, and anything on TMDB you do not.",
                        style = M3EType.rowSupporting(),
                        color = M3EColor.textMuted()
                    )
                }
            }

            local.isEmpty() && remote.isEmpty() -> M3ETonalCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = M3ESpacing.page)
            ) {
                Column(modifier = Modifier.padding(M3ESpacing.cardGap)) {
                    Text(
                        text = if (searching) "Searching…" else "Nothing found",
                        style = M3EType.rowTitle(),
                        color = M3EColor.text()
                    )
                    Text(
                        text = if (searching) {
                            "Looking for “$query”."
                        } else {
                            "No title matches “$query”."
                        },
                        style = M3EType.rowSupporting(),
                        color = M3EColor.textMuted()
                    )
                }
            }

            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = M3ESpacing.page),
                horizontalArrangement = Arrangement.spacedBy(M3ESpacing.inlineGap),
                verticalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)
            ) {
                if (local.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        M3ESectionHeader("In your library")
                    }
                    items(local, key = { it.id }) { item ->
                        SearchTile(
                            title = item.title,
                            posterUrl = item.posterUrl,
                            owned = true,
                            onClick = { onOpen(item) }
                        )
                    }
                }
                if (remote.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        M3ESectionHeader("On TMDB")
                    }
                    items(remote, key = { "${it.media_type}_${it.id}" }) { result ->
                        val mediaId = (if (result.media_type == "movie") "movie_" else "tv_") + result.id
                        SearchTile(
                            title = result.title ?: result.name ?: "Untitled",
                            posterUrl = TMDBClient.buildPosterUrl(result.poster_path),
                            owned = false,
                            busy = adding == mediaId,
                            onClick = { openOrAdd(result) }
                        )
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Spacer(Modifier.height(M3ESpacing.sectionGap))
                }
            }
        }
    }
}

/** One result. The corner tag is what distinguishes something owned from something on offer. */
@Composable
private fun SearchTile(
    title: String,
    posterUrl: String?,
    owned: Boolean,
    busy: Boolean = false,
    onClick: () -> Unit
) {
    Column(modifier = Modifier.clickable(enabled = !busy, onClick = onClick)) {
        Box {
            AsyncImage(
                model = posterUrl,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(M3EShape.chip())
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            )
            Surface(
                shape = M3EShape.chip(),
                color = if (owned) M3EColor.accent() else M3EColor.card(),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(M3ESpacing.tightGap)
            ) {
                Text(
                    text = if (owned) "In library" else if (busy) "Adding…" else "Add",
                    style = M3EType.action(),
                    color = if (owned) MaterialTheme.colorScheme.onPrimary else M3EColor.text(),
                    modifier = Modifier.padding(horizontal = M3ESpacing.tightGap, vertical = 2.dp)
                )
            }
        }
        Spacer(Modifier.height(M3ESpacing.tightGap))
        Text(
            text = title,
            style = M3EType.rowSupporting(),
            color = M3EColor.text(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
