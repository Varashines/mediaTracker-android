package com.vara.mediatracker.ui.m3e

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.vara.mediatracker.data.TrackingLogic
import com.vara.mediatracker.data.WatchHistoryRecorder
import com.vara.mediatracker.data.api.TMDBCastMember
import com.vara.mediatracker.data.api.TMDBClient
import com.vara.mediatracker.data.api.TMDBSearchItem
import com.vara.mediatracker.data.model.EpisodeEntity
import com.vara.mediatracker.data.model.MediaIds
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.di.AppContainer
import com.vara.mediatracker.ui.common.rememberHaptics
import com.vara.mediatracker.ui.theme.AppTextStyles
import com.vara.mediatracker.ui.theme.LocalSemanticColors
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * The detail screen, in the [M3E] vocabulary: a header, one primary action, and tabs.
 *
 * The screen used to be one column — status, taste, every season, every episode, overview,
 * details — which meant a long scroll to reach anything and no way to say what you came for.
 * Status and taste now live in a popup (they are both "how I feel about this title", and they are
 * set together), and the body is split into sections you switch between: a show has Seasons, Info
 * and Related, a film has Overview and Related.
 *
 * Every control writes through the same path the shipping screen uses: episodes and films through
 * [TrackingLogic], which owns the watched ledger and the state progression that follows from it;
 * status and taste as a DAO update plus the matching cloud push, with status moving the ledger too
 * because it bypasses the toggle.
 */
@Composable
fun M3ERoughDetail(
    item: MediaItemEntity,
    container: AppContainer,
    onBack: () -> Unit,
    onOpenMedia: (MediaItemEntity) -> Unit = {}
) {
    val db = container.database
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()

    val isTv = remember(item.id) { item.type.contains("TV", ignoreCase = true) }
    val showKey = remember(item.id) { MediaIds.tvShowKey(item.id) }

    var live by remember(item.id) { mutableStateOf(item) }
    var season by remember(item.id) { mutableStateOf<Int?>(null) }
    var statusPickerOpen by rememberSaveable(item.id) { mutableStateOf(false) }
    var tastePickerOpen by rememberSaveable(item.id) { mutableStateOf(false) }
    var related by remember(item.id) { mutableStateOf(emptyList<TMDBSearchItem>()) }
    var cast by remember(item.id) { mutableStateOf(emptyList<TMDBCastMember>()) }
    var directors by remember(item.id) { mutableStateOf(emptyList<String>()) }
    var creators by remember(item.id) { mutableStateOf(emptyList<String>()) }
    var section by rememberSaveable(item.id) {
        mutableStateOf(if (isTv) DetailSection.Seasons else DetailSection.Overview)
    }

    // Re-read the library's copy, then fill in what TMDB knows. A title added from search is a
    // stub, and one that arrived through sync can be missing its metadata entirely — which is how a
    // title ends up on screen under its own id. Only fields TMDB actually returns are written, so a
    // good local value is never replaced by an empty one.
    LaunchedEffect(item.id) {
        val stored = db.mediaDao().getMediaById(item.id) ?: item
        live = stored

        val tmdbId = MediaIds.tmdbIdOf(item.id)
        if (tmdbId <= 0) return@LaunchedEffect

        val client = container.tmdbClient()
        val details = runCatching {
            if (isTv) client.getTVDetails(tmdbId) else client.getMovieDetails(tmdbId)
        }.getOrNull()?.getOrNull()

        if (details != null) {
            val enriched = stored.copy(
                title = details.title ?: details.name ?: stored.title,
                overview = details.overview?.takeIf { it.isNotBlank() } ?: stored.overview,
                posterUrl = TMDBClient.buildPosterUrl(details.poster_path) ?: stored.posterUrl,
                backdropUrl = TMDBClient.buildBackdropUrl(details.backdrop_path) ?: stored.backdropUrl,
                releaseDate = details.release_date ?: details.first_air_date ?: stored.releaseDate
            )
            if (enriched != stored) {
                db.mediaDao().upsertMedia(enriched)
                live = enriched
            }
        }

        // A show is credited to its creators, a film to its director; TMDB keeps them in
        // different places, which is why the byline under the title reads from both.
        creators = details?.created_by?.mapNotNull { it.name }?.take(2).orEmpty()

        val credits = runCatching {
            if (isTv) client.getTVCredits(tmdbId) else client.getMovieCredits(tmdbId)
        }.getOrNull()?.getOrNull()
        if (credits != null) {
            cast = credits.cast.sortedBy { it.order }.take(15)
            directors = credits.crew
                .filter { it.job == "Director" }
                .mapNotNull { it.name }
                .distinct()
                .take(2)
        }

        related = runCatching {
            if (isTv) client.getTVRecommendations(tmdbId) else client.getMovieRecommendations(tmdbId)
        }.getOrNull()?.getOrNull()?.results
            .orEmpty()
            .filter { it.media_type == "movie" || it.media_type == "tv" }
            .take(12)
    }

    val episodeFlow = remember(item.id) {
        if (isTv) db.mediaDao().getEpisodesForShow(showKey) else flowOf(emptyList())
    }
    val episodes by episodeFlow.collectAsState(initial = emptyList())
    val ordered = remember(episodes) {
        episodes.sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))
    }
    val seasons = remember(ordered) { ordered.map { it.seasonNumber }.distinct().sorted() }
    val currentSeason = season ?: seasons.firstOrNull() ?: 1
    val seasonEpisodes = remember(ordered, currentSeason) {
        ordered.filter { it.seasonNumber == currentSeason }
    }
    val nextEpisode = remember(ordered) { ordered.firstOrNull { !it.isWatched && it.seasonNumber > 0 } }

    fun setStatus(next: String) {
        val now = TrackingLogic.isoNow()
        val wasCompleted = live.state.equals("Completed", ignoreCase = true)
        val isCompleted = next.equals("Completed", ignoreCase = true)
        haptics.toggle()
        live = live.copy(state = next, lastStateChangeDate = now, lastInteractionDate = now)
        scope.launch {
            db.mediaDao().updateState(item.id, next, now)
            container.syncManager().pushMediaUpdate(
                mediaId = item.id,
                type = item.type,
                state = next,
                taste = live.taste,
                isStateChange = true,
                dateAdded = item.dateAdded
            )

            // The ledger is not the state column, so a status change has to move it too.
            val historyDao = db.watchHistoryDao()
            if (isCompleted && !wasCompleted) {
                if (!isTv) {
                    WatchHistoryRecorder.recordWatch(
                        dao = historyDao,
                        mediaId = item.id,
                        kind = WatchHistoryRecorder.KIND_MOVIE,
                        episodeId = null,
                        watchedAt = now,
                        runtimeMinutes = null
                    )
                }
                WatchHistoryRecorder.completeCycle(historyDao, item.id, now)
            } else if (!isCompleted && wasCompleted) {
                if (!isTv) WatchHistoryRecorder.voidWatch(historyDao, item.id, null, now)
                WatchHistoryRecorder.reopenCycle(historyDao, item.id, now)
            }
        }
    }

    fun setTaste(next: String) {
        val now = TrackingLogic.isoNow()
        haptics.toggle()
        live = live.copy(taste = next, lastInteractionDate = now)
        scope.launch {
            db.mediaDao().updateTaste(item.id, next, now)
            container.syncManager().pushMediaUpdate(
                mediaId = item.id,
                type = item.type,
                state = live.state,
                taste = next,
                // A taste change is not a state change; claiming otherwise would advance
                // last_state_change_date in the cloud.
                isStateChange = false,
                dateAdded = item.dateAdded
            )
        }
    }

    fun setEpisodeWatched(episode: EpisodeEntity, watched: Boolean) {
        haptics.tap()
        scope.launch {
            TrackingLogic.markEpisodeWatched(container, episode, watched) { updated -> live = updated }
        }
    }

    fun setSeasonWatched(watched: Boolean) {
        if (seasonEpisodes.isEmpty()) return
        haptics.toggle()
        scope.launch {
            TrackingLogic.markSeasonWatched(
                container = container,
                showId = showKey,
                currentSeason,
                seasonEpisodes,
                watched
            ) { updated -> live = updated }
        }
    }

    fun toggleMovie() {
        haptics.toggle()
        scope.launch {
            TrackingLogic.toggleMovieWatched(container, live) { updated -> live = updated }
        }
    }

    /** A related title opens if it is already in the library, and is added first if it is not. */
    fun openRelated(result: TMDBSearchItem) {
        val isMovie = result.media_type == "movie"
        val mediaId = (if (isMovie) "movie_" else "tv_") + result.id
        scope.launch {
            val existing = db.mediaDao().getMediaById(mediaId)
            if (existing != null) {
                onOpenMedia(existing)
                return@launch
            }
            val title = result.title ?: result.name ?: return@launch
            val added = MediaItemEntity(
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
            db.mediaDao().upsertMedia(added)
            container.syncManager().pushMediaUpdate(
                mediaId = added.id,
                type = added.type,
                state = added.state,
                taste = added.taste,
                isStateChange = false,
                dateAdded = added.dateAdded
            )
            onOpenMedia(added)
        }
    }

    val sections = remember(isTv) {
        if (isTv) {
            listOf(DetailSection.Seasons, DetailSection.Info, DetailSection.Related)
        } else {
            listOf(DetailSection.Overview, DetailSection.Related)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(M3EColor.page())
            .statusBarsPadding()
            .padding(horizontal = M3ESpacing.page),
        verticalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)
    ) {
        item {
            Spacer(Modifier.height(M3ESpacing.tightGap))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = M3EColor.text()
                    )
                }
                Text(
                    text = item.type.uppercase(),
                    style = AppTextStyles.MonoLabel,
                    color = M3EColor.accent()
                )
            }
        }

        item {
            val byline = when {
                isTv && creators.isNotEmpty() -> "Created by ${creators.joinToString(", ")}"
                !isTv && directors.isNotEmpty() -> "Directed by ${directors.joinToString(", ")}"
                else -> null
            }
            Title(live, byline)
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(M3ESpacing.tightGap)) {
                if (isTv) {
                    PrimaryAction(
                        label = nextEpisode
                            ?.let { "Mark S${it.seasonNumber} · E${it.episodeNumber} watched" }
                            ?: "Everything watched",
                        icon = Icons.Default.Check,
                        enabled = nextEpisode != null,
                        onClick = { nextEpisode?.let { setEpisodeWatched(it, true) } }
                    )
                } else {
                    val watched = live.state.equals("Completed", ignoreCase = true)
                    PrimaryAction(
                        label = if (watched) "Watched" else "Mark watched",
                        icon = if (watched) Icons.Default.Check else Icons.Default.Bookmark,
                        enabled = true,
                        onClick = { toggleMovie() }
                    )
                }
                // Two pills, each its own popup: they are two decisions, not one, and a pill
                // that opens exactly what it names is quicker than a shared sheet you then have
                // to read.
                Row(horizontalArrangement = Arrangement.spacedBy(M3ESpacing.tightGap)) {
                    PickerPill(
                        label = "Status",
                        value = live.state,
                        tint = statusTint(live.state),
                        modifier = Modifier.weight(1f),
                        onClick = { statusPickerOpen = true }
                    )
                    PickerPill(
                        label = "Taste",
                        value = live.taste,
                        tint = tasteTint(live.taste),
                        modifier = Modifier.weight(1f),
                        onClick = { tastePickerOpen = true }
                    )
                }
            }
        }

        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(M3ESpacing.tightGap),
                verticalArrangement = Arrangement.spacedBy(M3ESpacing.tightGap)
            ) {
                sections.forEach { candidate ->
                    FilterChip(
                        selected = section == candidate,
                        onClick = { section = candidate },
                        label = { Text(candidate.label, style = M3EType.action()) },
                        shape = M3EShape.chip()
                    )
                }
            }
        }

        when (section) {
            DetailSection.Seasons -> {
                item {
                    Column {
                        val allWatched =
                            seasonEpisodes.isNotEmpty() && seasonEpisodes.all { it.isWatched }
                        M3ESectionHeader(
                            title = "Season $currentSeason",
                            trailing = {
                                TextButton(onClick = { setSeasonWatched(!allWatched) }) {
                                    Text(
                                        text = if (allWatched) "Clear season" else "Watch season",
                                        style = M3EType.action(),
                                        color = M3EColor.accent()
                                    )
                                }
                            }
                        )
                        Spacer(Modifier.height(M3ESpacing.tightGap))
                        ChoiceRow(
                            options = seasons.map { "Season $it" },
                            selected = "Season $currentSeason",
                            tintFor = { M3EColor.accent() },
                            onSelect = { label ->
                                label.substringAfter("Season ").toIntOrNull()?.let { season = it }
                            }
                        )
                    }
                }
                if (seasonEpisodes.isEmpty()) {
                    item {
                        M3ETonalCard(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "No episodes here yet.",
                                style = M3EType.rowSupporting(),
                                color = M3EColor.textMuted(),
                                modifier = Modifier.padding(M3ESpacing.cardGap)
                            )
                        }
                    }
                } else {
                    items(seasonEpisodes, key = { it.uniqueId }) { episode ->
                        EpisodeRow(
                            episode = episode,
                            watched = episode.isWatched,
                            onToggle = { setEpisodeWatched(episode, it) }
                        )
                    }
                }
            }

            DetailSection.Info, DetailSection.Overview -> {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)) {
                        if (live.overview.isNotBlank()) {
                            M3ESectionHeader("Overview")
                            Text(
                                text = live.overview,
                                style = M3EType.rowSupporting(),
                                color = M3EColor.textMuted()
                            )
                        }
                        M3ESectionHeader("Details")
                        M3ETonalCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(vertical = M3ESpacing.tightGap)) {
                                // Read once: `live` is a delegated property, so its fields cannot
                                // be smart-cast after a null check.
                                val orderedCount = ordered.size
                                val watchedCount = ordered.count { it.isWatched }
                                val genres = live.genres
                                val network = live.network
                                val released = live.releaseDate
                                if (orderedCount > 0) {
                                    DetailRow("Progress", "$watchedCount / $orderedCount episodes")
                                }
                                if (!genres.isNullOrBlank()) {
                                    DetailRow("Genres", genres, lines = 2)
                                }
                                if (!network.isNullOrBlank()) DetailRow("Network", network)
                                if (!released.isNullOrBlank()) {
                                    DetailRow("Released", released.take(10))
                                }
                                DetailRow("Added", live.dateAdded?.take(10) ?: "—", divider = false)
                            }
                        }

                        if (cast.isNotEmpty()) {
                            M3ESectionHeader("Cast")
                            M3ETonalCard(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(vertical = M3ESpacing.tightGap)) {
                                    val listed = cast.take(10)
                                    listed.forEachIndexed { index, member ->
                                        CastRow(member, divider = index < listed.lastIndex)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            DetailSection.Related -> {
                item {
                    M3ESectionHeader("More like this")
                }
                if (related.isEmpty()) {
                    item {
                        M3ETonalCard(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Nothing to suggest yet.",
                                style = M3EType.rowSupporting(),
                                color = M3EColor.textMuted(),
                                modifier = Modifier.padding(M3ESpacing.cardGap)
                            )
                        }
                    }
                } else {
                    items(related, key = { "${it.media_type}_${it.id}" }) { result ->
                        RelatedRow(result) { openRelated(result) }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(48.dp)) }
    }

    if (statusPickerOpen) {
        ChoicePicker(
            title = "Status",
            options = TrackingStatuses,
            selected = live.state,
            tintFor = { statusTint(it) },
            onSelect = { if (!it.equals(live.state, true)) setStatus(it) },
            onDismiss = { statusPickerOpen = false }
        )
    }

    if (tastePickerOpen) {
        ChoicePicker(
            title = "Taste",
            options = listOf("None", "Dislike", "Like", "Love"),
            selected = live.taste,
            tintFor = { tasteTint(it) },
            onSelect = { if (!it.equals(live.taste, true)) setTaste(it) },
            onDismiss = { tastePickerOpen = false }
        )
    }
}

private enum class DetailSection(val label: String) {
    Seasons("Seasons"),
    Info("Info"),
    Overview("Overview"),
    Related("Related")
}

/**
 * A labelled pill showing the current value, in the colour that value means.
 *
 * The tint does the work: a Completed title's pill is green, a Dropped one is red, a loved one
 * carries the taste colour — so the state is legible before the word is read.
 */
@Composable
private fun PickerPill(
    label: String,
    value: String,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = M3EShape.chip(),
        color = tint.copy(alpha = 0.18f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.5f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = M3ESpacing.inlineGap, vertical = M3ESpacing.tightGap)
        ) {
            Text(
                text = label,
                style = M3EType.rowSupporting(),
                color = M3EColor.textMuted(),
                maxLines = 1
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = value,
                    style = M3EType.rowTitle(),
                    fontWeight = FontWeight.SemiBold,
                    color = tint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = tint
                )
            }
        }
    }
}

/** One popup for one decision: a row of chips, applied as it is chosen. */
@Composable
private fun ChoicePicker(
    title: String,
    options: List<String>,
    selected: String,
    tintFor: @Composable (String) -> Color,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            ChoiceRow(
                options = options,
                selected = selected,
                tintFor = tintFor,
                onSelect = onSelect
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

/** The app's six tracking states, raw values — what sync and the Mac both read. */
private val TrackingStatuses = listOf(
    "Wishlist", "Active", "Completed", "On Hold", "Dropped", "Re-watching"
)

/** Poster, eyebrow and the editorial title — the display heading this app uses everywhere. */
@Composable
private fun Title(item: MediaItemEntity, byline: String? = null) {
    Row(verticalAlignment = Alignment.Bottom) {
        AsyncImage(
            model = item.posterUrl,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(132.dp)
                .aspectRatio(2f / 3f)
                .clip(M3EShape.card())
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        )
        Spacer(Modifier.width(M3ESpacing.cardGap))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = M3EType.screenTitle(),
                color = M3EColor.text(),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            if (byline != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = byline,
                    style = M3EType.rowSupporting(),
                    color = M3EColor.accent(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(M3ESpacing.tightGap))
            Text(
                text = buildString {
                    append(item.type)
                    item.releaseDate?.take(4)?.let { if (it.isNotBlank()) append(" · ").append(it) }
                },
                style = M3EType.rowSupporting(),
                color = M3EColor.textMuted()
            )
        }
    }
}

/** One emphasised action, deliberately the largest element on the screen. */
@Composable
private fun PrimaryAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(M3ESpacing.rowMinHeight)
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(M3ESpacing.tightGap))
        Text(text = label, style = M3EType.action())
    }
}

/**
 * A row of chips wrapping to as many lines as it needs.
 *
 * Each option carries its own tint, so a selected chip says what it means — a finished title reads
 * green, a dropped one red, a loved one the taste colour — rather than every selection looking
 * alike. That is Material's colour tactic: colour as information, not decoration.
 */
@Composable
private fun ChoiceRow(
    options: List<String>,
    selected: String,
    tintFor: @Composable (String) -> Color,
    onSelect: (String) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(M3ESpacing.tightGap),
        verticalArrangement = Arrangement.spacedBy(M3ESpacing.tightGap)
    ) {
        options.forEach { option ->
            val isSelected = option.equals(selected, ignoreCase = true)
            val tint = tintFor(option)
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(option) },
                label = {
                    Text(
                        text = option,
                        style = M3EType.action(),
                        color = if (isSelected) tint else M3EColor.textMuted()
                    )
                },
                shape = M3EShape.chip(),
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Color.Transparent,
                    labelColor = M3EColor.textMuted(),
                    selectedContainerColor = tint.copy(alpha = 0.18f),
                    selectedLabelColor = tint,
                    selectedLeadingIconColor = tint
                )
            )
        }
    }
}

/** Status colour by meaning: what the state *is*, not just that it is selected. */
@Composable
private fun statusTint(state: String): Color = when {
    state.equals("Completed", true) -> M3EColor.positive()
    state.equals("Active", true) -> M3EColor.accent()
    state.equals("Re-watching", true) -> M3EColor.accent()
    state.equals("On Hold", true) -> M3EColor.warning()
    state.equals("Dropped", true) -> MaterialTheme.colorScheme.error
    else -> M3EColor.textMuted()
}

/** Taste colour, matching the reaction tones the real detail uses. */
@Composable
private fun tasteTint(taste: String): Color {
    val semantic = LocalSemanticColors.current
    return when {
        taste.equals("Love", true) -> semantic.loved
        taste.equals("Like", true) -> semantic.liked
        taste.equals("Dislike", true) -> semantic.disliked
        else -> M3EColor.textMuted()
    }
}

/**
 * One episode: the number large, the name beside it, a Material checkbox trailing.
 *
 * The checkbox is the only thing saying "watched" — a tick already means that, and spelling it out
 * beside the tick said it twice. The episode number leads at display size because it is what an
 * episode is called when you are scanning a season, and the name takes two lines so a long one is
 * read rather than elided.
 */
@Composable
private fun EpisodeRow(episode: EpisodeEntity, watched: Boolean, onToggle: (Boolean) -> Unit) {
    M3ETonalCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(M3ESpacing.inlineGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "E${episode.episodeNumber}",
                style = M3EType.metric(),
                color = if (watched) M3EColor.textMuted() else M3EColor.accent(),
                maxLines = 1,
                modifier = Modifier.width(56.dp)
            )
            Spacer(Modifier.width(M3ESpacing.inlineGap))
            Text(
                text = episode.name.ifBlank { "Episode ${episode.episodeNumber}" },
                style = M3EType.rowTitle(),
                fontWeight = FontWeight.SemiBold,
                color = M3EColor.text(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Checkbox(checked = watched, onCheckedChange = onToggle)
        }
    }
}

/** One suggested title: poster, name, year, and whether it is already yours. */
@Composable
private fun RelatedRow(result: TMDBSearchItem, onClick: () -> Unit) {
    val title = result.title ?: result.name ?: "Untitled"
    val year = (result.release_date ?: result.first_air_date)?.take(4)
    M3ETonalCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(M3ESpacing.inlineGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = TMDBClient.buildPosterUrl(result.poster_path),
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(44.dp)
                    .height(66.dp)
                    .clip(M3EShape.chip())
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            )
            Spacer(Modifier.width(M3ESpacing.inlineGap))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = M3EType.rowTitle(),
                    fontWeight = FontWeight.SemiBold,
                    color = M3EColor.text(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = listOfNotNull(
                        if (result.media_type == "tv") "TV Show" else "Movie",
                        year
                    ).joinToString(" · "),
                    style = M3EType.rowSupporting(),
                    color = M3EColor.textMuted()
                )
            }
        }
    }
}

/** One cast member: portrait, name, and the role they played. */
@Composable
private fun CastRow(member: TMDBCastMember, divider: Boolean) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = M3ESpacing.cardGap, vertical = M3ESpacing.rowGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = TMDBClient.buildProfileUrl(member.profile_path),
                contentDescription = member.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            )
            Spacer(Modifier.width(M3ESpacing.inlineGap))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = member.name,
                    style = M3EType.rowTitle(),
                    fontWeight = FontWeight.SemiBold,
                    color = M3EColor.text(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val character = member.character
                if (!character.isNullOrBlank()) {
                    Text(
                        text = character,
                        style = M3EType.rowSupporting(),
                        color = M3EColor.textMuted(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        if (divider) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = M3ESpacing.cardGap),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}

/** A label/value row inside a card, spaced by the rhythm rather than by padding literals. */
@Composable
private fun DetailRow(label: String, value: String, divider: Boolean = true, lines: Int = 1) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = M3ESpacing.cardGap, vertical = M3ESpacing.rowGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = M3EType.rowSupporting(),
                color = M3EColor.textMuted(),
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = value,
                style = M3EType.rowTitle(),
                fontWeight = FontWeight.SemiBold,
                color = M3EColor.text(),
                maxLines = lines,
                textAlign = TextAlign.End,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(2f)
            )
        }
        if (divider) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = M3ESpacing.cardGap),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}
