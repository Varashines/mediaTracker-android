package com.vara.mediatracker.ui.m3e

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
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
/**
 * Why the results area is empty.
 *
 * The three ways a search can come back with nothing used to be one value — an empty list — so the
 * screen could only ever say "Nothing found". A missing key and a failed request both looked like
 * "your title does not exist", which is the worst possible answer: it is confidently wrong.
 */
private sealed interface SearchOutcome {
    data object Idle : SearchOutcome
    data object Searching : SearchOutcome
    data object Results : SearchOutcome
    data object NeedsKey : SearchOutcome
    data object Failed : SearchOutcome
    data class NoMatches(val query: String) : SearchOutcome
}

@Composable
internal fun M3ERoughSearch(
    library: List<MediaItemEntity>,
    container: AppContainer,
    onOpen: (MediaItemEntity) -> Unit,
    onBack: () -> Unit,
    /** Where "Search needs a key" sends you. */
    onOpenSettings: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var remote by remember { mutableStateOf(emptyList<TMDBSearchItem>()) }
    var searching by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<SearchOutcome>(SearchOutcome.Idle) }
    // Bumped by "Try again"; it is a key of the search effect, so the request re-runs.
    var retryToken by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf<String?>(null) }
    // Before a query there was nothing to look at but a prompt card, which is the dullest state a
    // search screen has. Trending gives it something worth tapping on the way to typing.
    var trending by remember { mutableStateOf(emptyList<TMDBSearchItem>()) }

    // Focus on open, so the keyboard is up and the first thing you do is type. A search screen that
    // makes you tap the field first has added a step to the one action it exists for.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    LaunchedEffect(Unit) {
        trending = runCatching { container.tmdbClient().getTrending().getOrNull()?.results }
            .getOrNull()
            .orEmpty()
            .filter { it.media_type == "movie" || it.media_type == "tv" }
            .take(18)
    }

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
    //
    // The three ways a search can come back empty are kept apart. They used to collapse into one
    // `.getOrNull().orEmpty()`, so a missing API key and a failed request both rendered as
    // "Nothing found" — the app told the user their title did not exist when in fact it had never
    // asked. `outcome` is what the empty branch reads.
    LaunchedEffect(query, retryToken) {
        val term = query.trim()
        if (term.length < 2) {
            remote = emptyList()
            searching = false
            outcome = SearchOutcome.Idle
            return@LaunchedEffect
        }
        if (container.preferences.tmdbApiKey.isBlank()) {
            remote = emptyList()
            searching = false
            outcome = SearchOutcome.NeedsKey
            return@LaunchedEffect
        }
        searching = true
        outcome = SearchOutcome.Searching
        delay(350)
        val result = container.tmdbClient().searchMulti(term)
        val items = result.getOrNull()?.results
            .orEmpty()
            .filter { it.media_type == "movie" || it.media_type == "tv" }
            .take(24)
        remote = items
        outcome = when {
            // A failure first: an empty list from a failed call is not an empty result.
            result.isFailure -> SearchOutcome.Failed
            items.isEmpty() -> SearchOutcome.NoMatches(term)
            else -> SearchOutcome.Results
        }
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
            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    // A hairline, so the bar reads as a field rather than as a gap in the page. The
                    // fill alone was invisible against a dark background.
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, M3EShape.chip()),
                placeholder = {
                    Text("Search titles, or add something new", style = M3EType.rowSupporting())
                },
                leadingIcon = {
                    // The accent, because the field is the one thing on this screen worth looking
                    // at and the icon is what says so.
                    Icon(Icons.Default.Search, contentDescription = null, tint = M3EColor.accent())
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
            query.isBlank() -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = M3ESpacing.page),
                horizontalArrangement = Arrangement.spacedBy(M3ESpacing.inlineGap),
                verticalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Text(
                            text = "Titles you own, and anything on TMDB you do not.",
                            style = M3EType.rowSupporting(),
                            color = M3EColor.textMuted()
                        )
                        if (trending.isNotEmpty()) {
                            Spacer(Modifier.height(M3ESpacing.cardGap))
                            M3ESectionHeader(
                                "Trending this week",
                                icon = Icons.Default.TrendingUp,
                                tint = M3EColor.warning()
                            )
                        }
                    }
                }
                items(trending, key = { "${it.media_type}_${it.id}" }) { result ->
                    val mediaId = (if (result.media_type == "movie") "movie_" else "tv_") + result.id
                    SearchTile(
                        title = result.title ?: result.name ?: "Untitled",
                        posterUrl = TMDBClient.buildPosterUrl(result.poster_path),
                        owned = false,
                        busy = adding == mediaId,
                        onClick = { openOrAdd(result) }
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Spacer(Modifier.height(LocalNavClearance.current))
                }
            }

            local.isEmpty() && remote.isEmpty() -> {
                // Four different reasons to be here, four different things to say. Reporting all of
                // them as "nothing found" told the user their title did not exist when the app may
                // never have asked — see SearchOutcome.
                when (val state = outcome) {
                    is SearchOutcome.NeedsKey -> EmptyState(
                        title = "Search needs a key.",
                        body = "Add your TMDB key in Settings and results will come through.",
                        action = {
                            Button(onClick = onOpenSettings) { Text("Open Settings") }
                        }
                    )

                    is SearchOutcome.Failed -> EmptyState(
                        title = "Couldn't reach TMDB.",
                        body = "Check your connection and try again.",
                        action = {
                            Button(onClick = { retryToken++ }) { Text("Try again") }
                        }
                    )

                    is SearchOutcome.NoMatches -> EmptyState(
                        title = "Nothing for “${state.query}”.",
                        body = "Try a different spelling — or the original title."
                    )

                    is SearchOutcome.Searching -> M3ETonalCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = M3ESpacing.page)
                    ) {
                        Column(modifier = Modifier.padding(M3ESpacing.cardGap)) {
                            // Something that moves while the request is out. The words alone look
                            // like a result that happens to say "Searching…".
                            LoadingIndicator(
                                modifier = Modifier.size(32.dp),
                                color = M3EColor.accent()
                            )
                            Spacer(Modifier.height(M3ESpacing.tightGap))
                            Text(
                                text = "Searching…",
                                style = M3EType.rowTitle(),
                                color = M3EColor.text()
                            )
                            Text(
                                text = "Looking for “$query”.",
                                style = M3EType.rowSupporting(),
                                color = M3EColor.textMuted()
                            )
                        }
                    }

                    // Idle, or results that the library/local filters already covered.
                    else -> EmptyState(
                        title = "Nothing for “$query”.",
                        body = "Try a different spelling — or the original title."
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
                        M3ESectionHeader("In your library", icon = Icons.Default.Bookmark, tint = M3EColor.accent())
                    }
                    // Namespaced: a lazy grid's key space is shared by every `items` block in it,
                    // and the TMDB block below keys on "<media_type>_<id>" — which for a TV result
                    // is exactly the library's own "tv_<id>". Searching for a title you already own
                    // put the same key in the grid twice and crashed the draw.
                    items(local, key = { "local_${it.id}" }) { item ->
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
                        M3ESectionHeader("On TMDB", icon = Icons.Default.Public, tint = M3EColor.positive())
                    }
                    items(remote, key = { "remote_${it.media_type}_${it.id}" }) { result ->
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
    M3EArtworkCard(title = title, onClick = if (busy) null else onClick) {
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
    }
}
