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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.vara.mediatracker.data.model.EpisodeEntity
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.di.AppContainer
import com.vara.mediatracker.ui.theme.LocalSemanticColors
import com.vara.mediatracker.ui.theme.AppTextStyles

/**
 * The detail screen, in the [M3E] vocabulary — now interactive.
 *
 * The controls are real Material components and they respond, but every change lives in this
 * screen's own state: nothing is written to the library or synced. That keeps the sketch truthful
 * — it shows the interaction, not a half-wired migration. When this replaces the real screen, the
 * same controls call the tracking logic that already exists.
 */
@Composable
fun M3ERoughDetail(item: MediaItemEntity, container: AppContainer, onBack: () -> Unit) {
    val episodes by produceState(initialValue = emptyList<EpisodeEntity>(), item.id) {
        // Episodes key their id as `tv_1399_s1_e1`, so the show's own id is the prefix.
        value = runCatching {
            container.database.mediaDao().getAllEpisodesList().filter { it.uniqueId.startsWith(item.id) }
        }.getOrDefault(emptyList())
    }

    // UI-local interaction state.
    var status by remember(item.id) { mutableStateOf(item.state) }
    var taste by remember(item.id) { mutableStateOf(item.taste) }
    val watched = remember(item.id) { mutableStateMapOf<String, Boolean>() }
    val seasons = remember(episodes) { episodes.map { it.seasonNumber }.distinct().sorted() }
    var season by remember(seasons) { mutableStateOf(seasons.firstOrNull() ?: 1) }

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
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text(
                    text = item.type.uppercase(),
                    style = AppTextStyles.MonoLabel,
                    color = M3EColor.accent()
                )
            }
        }

        item { Title(item) }

        item { PrimaryAction(finished = status.equals("Completed", true)) { status = it } }

        item {
            Column {
                M3ESectionHeader("Status")
                Spacer(Modifier.height(M3ESpacing.tightGap))
                ChoiceRow(
                    options = listOf("Wishlist", "Active", "On Hold", "Dropped", "Completed"),
                    selected = status,
                    tintFor = { statusTint(it) },
                    onSelect = { status = it }
                )
                Spacer(Modifier.height(M3ESpacing.cardGap))
                M3ESectionHeader("Taste")
                Spacer(Modifier.height(M3ESpacing.tightGap))
                ChoiceRow(
                    options = listOf("None", "Dislike", "Like", "Love"),
                    selected = taste,
                    tintFor = { tasteTint(it) },
                    onSelect = { taste = it }
                )
            }
        }

        if (episodes.isNotEmpty()) {
            item {
                Column {
                    M3ESectionHeader("Seasons")
                    Spacer(Modifier.height(M3ESpacing.tightGap))
                    ChoiceRow(
                        options = seasons.map { "Season $it" },
                        selected = "Season $season",
                        tintFor = { M3EColor.accent() },
                        onSelect = { label -> season = label.substringAfter("Season ").toIntOrNull() ?: season }
                    )
                }
            }
            items(
                episodes.filter { it.seasonNumber == season }
                    .sortedBy { it.episodeNumber },
                key = { it.uniqueId }
            ) { episode ->
                EpisodeRow(
                    episode = episode,
                    watched = watched[episode.uniqueId] ?: episode.isWatched,
                    onToggle = { watched[episode.uniqueId] = it }
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
                        DetailRow("Progress", progressLabel(episodes, watched))
                        if (!item.genres.isNullOrBlank()) DetailRow("Genres", item.genres)
                        if (!item.network.isNullOrBlank()) DetailRow("Network", item.network)
                        if (!item.releaseDate.isNullOrBlank()) DetailRow("Released", item.releaseDate.take(10))
                        DetailRow("Added", item.dateAdded?.take(10) ?: "—", divider = false)
                    }
                }
            }
        }

        item { Spacer(Modifier.height(48.dp)) }
    }
}

/** How many of the known episodes count as watched, counting the local toggles. */
private fun progressLabel(
    episodes: List<EpisodeEntity>,
    watched: Map<String, Boolean>
): String {
    if (episodes.isEmpty()) return "—"
    val seen = episodes.count { watched[it.uniqueId] ?: it.isWatched }
    return "$seen / ${episodes.size} episodes"
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
private fun PrimaryAction(finished: Boolean, onToggle: (String) -> Unit) {
    Button(
        onClick = { onToggle(if (finished) "Active" else "Completed") },
        modifier = Modifier
            .fillMaxWidth()
            .height(M3ESpacing.rowMinHeight)
    ) {
        Icon(
            imageVector = if (finished) Icons.Default.Check else Icons.Default.Bookmark,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(M3ESpacing.tightGap))
        Text(
            text = if (finished) "Watched" else "Mark watched",
            style = M3EType.action()
        )
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
    tintFor: (String) -> Color,
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

/** A label/value row inside a card, spaced by the rhythm rather than by padding literals. */
@Composable
private fun DetailRow(label: String, value: String, divider: Boolean = true) {
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
                modifier = Modifier.weight(1f)
            )
            Text(
                text = value,
                style = M3EType.rowTitle(),
                fontWeight = FontWeight.SemiBold,
                color = M3EColor.text(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
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
