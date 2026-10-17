package com.vara.mediatracker.ui.m3e

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.vara.mediatracker.data.TrackingLogic
import com.vara.mediatracker.data.WatchHistoryRecorder
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
 * The detail screen, in the [M3E] vocabulary — wired to the real library.
 *
 * Every control writes through the same path the shipping screen uses: episodes and movies go
 * through [TrackingLogic], which owns the watched/unwatched ledger and the state progression that
 * follows from it; status and taste are a DAO update plus the matching cloud push. Status carries
 * the watch-history bookkeeping too, because a status change bypasses the toggle and would
 * otherwise leave the ledger describing a different library from the state column.
 *
 * The screen holds its own optimistic copy so a tap reads back immediately, but it never invents
 * an outcome: the copy mirrors exactly what was written, and the episode list is a Room flow, so
 * anything the tracker changes lands here regardless.
 */
@Composable
fun M3ERoughDetail(item: MediaItemEntity, container: AppContainer, onBack: () -> Unit) {
    val db = container.database
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()

    val isTv = remember(item.id) { item.type.contains("TV", ignoreCase = true) }
    val showKey = remember(item.id) { MediaIds.tvShowKey(item.id) }

    // Optimistic copy: state and taste only, kept in step with what was just persisted.
    var live by remember(item.id) { mutableStateOf(item) }
    var season by remember(item.id) { mutableStateOf<Int?>(null) }

    // A flow, not a one-shot read: the tracker rewrites the show's state and its episodes, and
    // those changes have to appear without this screen knowing they happened.
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

            // The ledger is not the state column, so a status change has to move it as well.
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
                seasonNumber = currentSeason,
                seasonEpisodes = seasonEpisodes,
                watched = watched
            ) { updated -> live = updated }
        }
    }

    fun toggleMovie() {
        haptics.toggle()
        scope.launch {
            TrackingLogic.toggleMovieWatched(container, live) { updated -> live = updated }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(M3EColor.page())
            .statusBarsPadding()
            .padding(horizontal = M3ESpacing.page),
        verticalArrangement = Arrangement.spacedBy(M3ESpacing.sectionGap)
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

        item { Title(item) }

        item {
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
        }

        item {
            Column {
                M3ESectionHeader("Status")
                Spacer(Modifier.height(M3ESpacing.tightGap))
                ChoiceRow(
                    options = listOf("Wishlist", "Active", "On Hold", "Dropped", "Completed"),
                    selected = live.state,
                    tintFor = { statusTint(it) },
                    onSelect = { if (!it.equals(live.state, true)) setStatus(it) }
                )
                Spacer(Modifier.height(M3ESpacing.cardGap))
                M3ESectionHeader("Taste")
                Spacer(Modifier.height(M3ESpacing.tightGap))
                ChoiceRow(
                    options = listOf("None", "Dislike", "Like", "Love"),
                    selected = live.taste,
                    tintFor = { tasteTint(it) },
                    onSelect = { if (!it.equals(live.taste, true)) setTaste(it) }
                )
            }
        }

        if (seasons.isNotEmpty()) {
            item {
                Column {
                    M3ESectionHeader(
                        title = "Seasons",
                        trailing = {
                            val allWatched = seasonEpisodes.isNotEmpty() && seasonEpisodes.all { it.isWatched }
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
            items(seasonEpisodes, key = { it.uniqueId }) { episode ->
                EpisodeRow(
                    episode = episode,
                    watched = episode.isWatched,
                    onToggle = { setEpisodeWatched(episode, it) }
                )
            }
        }

        if (item.overview.isNotBlank()) {
            item {
                Column {
                    M3ESectionHeader("Overview")
                    Spacer(Modifier.height(M3ESpacing.tightGap))
                    Text(
                        text = item.overview,
                        style = M3EType.rowSupporting(),
                        color = M3EColor.textMuted()
                    )
                }
            }
        }

        item {
            Column {
                M3ESectionHeader("Details")
                Spacer(Modifier.height(M3ESpacing.tightGap))
                M3ETonalCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(vertical = M3ESpacing.tightGap)) {
                        if (ordered.isNotEmpty()) {
                            DetailRow(
                                "Progress",
                                "${ordered.count { it.isWatched }} / ${ordered.size} episodes"
                            )
                        }
                        // Read once: `live` is a delegated property, so its fields cannot be
                        // smart-cast after a null check.
                        val genres = live.genres
                        val network = live.network
                        val released = live.releaseDate
                        if (!genres.isNullOrBlank()) DetailRow("Genres", genres, lines = 2)
                        if (!network.isNullOrBlank()) DetailRow("Network", network)
                        if (!released.isNullOrBlank()) DetailRow("Released", released.take(10))
                        DetailRow("Added", live.dateAdded?.take(10) ?: "—", divider = false)
                    }
                }
            }
        }

        item { Spacer(Modifier.height(48.dp)) }
    }
}

/** Poster, eyebrow and the editorial title — the display heading this app uses everywhere. */
@Composable
private fun Title(item: MediaItemEntity) {
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
 * Each option carries its own tint, so a selected chip says what it means — a finished title
 * reads green, a dropped one red, a loved one the taste colour — rather than every selection
 * looking alike. That is Material's colour tactic: colour as information, not decoration.
 */
@Composable
private fun ChoiceRow(
    options: List<String>,
    selected: String,
    tintFor: @Composable (String) -> Color,
    onSelect: (String) -> Unit
) {
    androidx.compose.foundation.layout.FlowRow(
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

/** One episode: code and name, with a Material checkbox trailing. */
@Composable
private fun EpisodeRow(episode: EpisodeEntity, watched: Boolean, onToggle: (Boolean) -> Unit) {
    M3ETonalCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(M3ESpacing.inlineGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "E${episode.episodeNumber} · ${episode.name.ifBlank { "Episode ${episode.episodeNumber}" }}",
                    style = M3EType.rowTitle(),
                    fontWeight = FontWeight.SemiBold,
                    color = M3EColor.text(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (watched) "Watched" else episode.airDate?.take(10) ?: "Not watched",
                    style = M3EType.rowSupporting(),
                    color = M3EColor.textMuted(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Checkbox(checked = watched, onCheckedChange = onToggle)
        }
    }
}

/**
 * A label/value row inside a card, spaced by the rhythm rather than by padding literals.
 *
 * Both sides carry a weight. With only the label weighted, a long value — a genre list is the
 * obvious one — took whatever width it wanted and squeezed the label down to a single character
 * per line. Sharing the width proportionally means the label holds its own and the value wraps
 * within the remainder, and [lines] lets a genuinely long value use more than one.
 */
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
