package com.vara.mediatracker.ui.smarthub

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.ui.common.EmptyState
import com.vara.mediatracker.ui.common.LocalOverlayOpen
import com.vara.mediatracker.ui.common.rememberCountdownText
import com.vara.mediatracker.ui.library.LibraryPosterCard
import com.vara.mediatracker.util.AirTimeResolver
import java.util.Date
import java.util.Locale

/**
 * 3-column poster grid for a Smart Hub collection — the same poster tiles as the Library.
 *
 * When [groupByWeek] is set (Released This Week) titles are bucketed by weekday, matching
 * macOS. [sortAscending] lists soonest-next-airing first (Premiere Radar); otherwise
 * newest-release first. [showCountdown] overlays a countdown chip on each poster.
 */
@Composable
fun SmartCollectionListScreen(
    title: String,
    items: List<MediaItemEntity>,
    onSelectMedia: (String) -> Unit,
    onBack: () -> Unit,
    groupByWeek: Boolean = false,
    sortAscending: Boolean = false,
    showCountdown: Boolean = false,
    nextAirInstants: Map<String, Long> = emptyMap()
) {
    // Yield the back gesture to the detail/search overlay when one is open.
    BackHandler(enabled = !LocalOverlayOpen.current, onBack = onBack)

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
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${items.size} ${if (items.size == 1) "title" else "titles"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (items.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Movie,
                title = "Nothing here yet",
                body = "No titles match this collection right now."
            )
        } else {
            val groups = remember(items, groupByWeek) {
                if (groupByWeek) groupOnThisWeek(items) else emptyList()
            }
            val flat = remember(items, groupByWeek, sortAscending) {
                when {
                    groupByWeek -> emptyList()
                    // Soonest resolved airing/release first (Premiere Radar).
                    sortAscending -> items.sortedBy { resolvedInstant(it, nextAirInstants) ?: Long.MAX_VALUE }
                    else -> items.sortedWith(compareByDescending { it.releaseDate ?: "" })
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, top = 6.dp, end = 12.dp, bottom = 140.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (groupByWeek) {
                    groups.forEach { group ->
                        item(span = { GridItemSpan(maxLineSpan) }, key = "day_${group.label}") {
                            Text(
                                text = group.label.uppercase(Locale.getDefault()),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.primary,
                                letterSpacing = 0.8.sp,
                                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                            )
                        }
                        items(group.items, key = { it.id }) { item ->
                            LibraryPosterCard(
                                item = item,
                                statusLabel = null,
                                progressLabel = "",
                                onClick = { onSelectMedia(item.id) }
                            )
                        }
                    }
                } else {
                    items(flat, key = { it.id }) { item ->
                        val countdown = if (showCountdown) {
                            val instant = remember(item.id, item.nextAiringInstant, item.releaseDate, nextAirInstants[item.id]) {
                                resolvedInstant(item, nextAirInstants)?.let { Date(it) }
                            }
                            rememberCountdownText(instant)
                        } else null
                        LibraryPosterCard(
                            item = item,
                            statusLabel = countdown,
                            progressLabel = "",
                            onClick = { onSelectMedia(item.id) }
                        )
                    }
                }
            }
        }
    }
}

/** The item's next-episode instant (from the Hub) if known, else its own airing/release. */
private fun resolvedInstant(item: MediaItemEntity, nextAirInstants: Map<String, Long>): Long? =
    nextAirInstants[item.id]?.takeIf { it > 0 } ?: itemAirInstant(item)

/** Resolved next-airing (TVMaze) or release instant for an item, in epoch millis. */
private fun itemAirInstant(item: MediaItemEntity): Long? =
    item.nextAiringInstant?.takeIf { it.isNotBlank() }?.let { iso ->
        AirTimeResolver.resolveAirInstant(
            dateString = iso.take(10),
            airstamp = iso,
            timezone = item.timezone,
            serviceName = AirTimeResolver.serviceHint(item.network, item.studios, item.genres)
        )
    }?.time
        ?: item.releaseDate?.let { release ->
            AirTimeResolver.resolveAirInstant(release, serviceName = AirTimeResolver.serviceHint(item.network, item.studios, item.genres))
        }?.time
