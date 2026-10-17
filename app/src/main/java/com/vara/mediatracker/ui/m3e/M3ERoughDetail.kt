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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.ui.theme.AppTextStyles

/**
 * The detail screen, in the [M3E] vocabulary.
 *
 * The shape Material's tactics point at for a detail view: one editorial title, one emphasised
 * action that is the largest thing on the screen (the size tactic), then calm sections — an
 * overview, and a tonal card of label/value rows. The poster is the exception that needs no
 * component: a rounded image on a tonal plate.
 *
 * Actions are inert on purpose. This is a sketch of the language; wire them through the existing
 * tracking logic when the screen replaces the real one.
 */
@Composable
fun M3ERoughDetail(item: MediaItemEntity, onBack: () -> Unit) {
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

        item { PrimaryAction(item) }

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
                        DetailRow("Status", item.state)
                        DetailRow("Taste", item.taste)
                        if (!item.genres.isNullOrBlank()) DetailRow("Genres", item.genres)
                        if (!item.network.isNullOrBlank()) DetailRow("Network", item.network)
                        if (!item.releaseDate.isNullOrBlank()) DetailRow("Released", item.releaseDate.take(10))
                        if (!item.dateAdded.isNullOrBlank()) DetailRow("Added", item.dateAdded.take(10), divider = false)
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
private fun PrimaryAction(item: MediaItemEntity) {
    val finished = item.state.equals("Completed", true)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(M3ESpacing.inlineGap)
    ) {
        Button(
            onClick = {},
            modifier = Modifier.weight(1f).height(M3ESpacing.rowMinHeight)
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
        FilledTonalButton(
            onClick = {},
            modifier = Modifier.height(M3ESpacing.rowMinHeight)
        ) {
            Text("Save", style = M3EType.action())
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
