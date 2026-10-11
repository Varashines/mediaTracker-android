package com.vara.mediatracker.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vara.mediatracker.data.badge.BadgeResult
import com.vara.mediatracker.data.badge.SmartBadge
import com.vara.mediatracker.ui.common.EmptyState
import com.vara.mediatracker.ui.common.ExpressiveCard
import com.vara.mediatracker.ui.common.formatReleaseDay
import com.vara.mediatracker.ui.theme.AppColors

/**
 * The Home timeline: a vertical, day-grouped feed of releases.
 *
 * Replaces five horizontal poster rails. Vertical is the right axis here because the
 * question is chronological — "what is on today, what is on tomorrow" — and a vertical
 * feed needs no horizontal panning to read. It also scales: the shelf layout could only
 * ever show the first 15 of each rail, whereas the feed can be as long as the month
 * actually requires.
 */
@Composable
fun HomeTimeline(
    days: List<FeedDay>,
    badges: Map<String, BadgeResult?>,
    onSelectMedia: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (days.isEmpty()) {
        EmptyState(
            icon = Icons.Default.Tv,
            title = "Nothing scheduled",
            body = "No episodes or releases in the next month for anything you're tracking."
        )
        return
    }

    Column(modifier = modifier.fillMaxWidth()) {
        days.forEach { day ->
            DaySectionHeader(dateString = day.dateString, count = day.entries.size)
            day.entries.forEach { entry ->
                FeedRow(
                    entry = entry,
                    badge = badges[entry.media.id],
                    onClick = { onSelectMedia(entry.media.id) }
                )
            }
        }
    }
}

@Composable
private fun DaySectionHeader(dateString: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = formatReleaseDay(dateString),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = count.toString(),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * A single timeline entry.
 *
 * Compact row rather than a card: a month of releases can run to dozens of entries, and
 * cards would push the actual content below the fold. The thumbnail carries recognition;
 * the text carries the facts.
 */
@Composable
private fun FeedRow(
    entry: FeedEntry,
    badge: BadgeResult?,
    onClick: () -> Unit
) {
    ExpressiveCard(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f),
        tonalElevation = 0.dp,
        pressedScale = 0.985f,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(44.dp).height(62.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            ) {
                if (entry.media.posterUrl != null) {
                    AsyncImage(
                        model = entry.media.posterUrl,
                        contentDescription = entry.media.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = if (entry.isEpisode) Icons.Default.Tv else Icons.Default.Movie,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center).size(20.dp)
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.media.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (badge != null) {
                        Spacer(Modifier.width(6.dp))
                        BadgeDot(badge.badge)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = entry.kindLabel,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Countdown, because "is it out yet" is the reason this row exists.
            entry.nextAiringInstant?.let { instant ->
                Spacer(Modifier.width(8.dp))
                CountdownChip(instant)
            }
        }
    }
}

/** Compact badge marker — the full pill is too heavy at row scale. */
@Composable
private fun BadgeDot(badge: SmartBadge) {
    Box(
        modifier = Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(badge.backgroundColor)
    )
}

@Composable
private fun CountdownChip(instantIso: String) {
    val label = androidx.compose.runtime.remember(instantIso) { relativeLabel(instantIso) }
    if (label.isNullOrBlank()) return
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.75f))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = AppColors.Gold
        )
    }
}

private fun relativeLabel(instantIso: String): String? = try {
    val target = java.time.Instant.parse(instantIso).toEpochMilli()
    val diff = target - System.currentTimeMillis()
    when {
        diff <= 0 -> "Today"
        diff < 3_600_000 -> "in ${(diff / 60_000).coerceAtLeast(1)}m"
        diff < 86_400_000 -> "in ${diff / 3_600_000}h"
        else -> "in ${diff / 86_400_000}d"
    }
} catch (_: Exception) {
    null
}
