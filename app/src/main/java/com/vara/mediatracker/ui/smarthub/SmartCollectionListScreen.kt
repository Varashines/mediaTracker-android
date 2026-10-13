package com.vara.mediatracker.ui.smarthub

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.ui.common.EmptyState
import com.vara.mediatracker.ui.common.ExpressiveCard
import com.vara.mediatracker.ui.theme.ExpressiveShapes
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * List for a Smart Hub collection.
 *
 * Deliberately minimal: just the title and its release date. The generic section list
 * showed next-episode progress, which is meaningless for these curated views. When
 * [groupByWeek] is set (Released This Week) the titles are bucketed by weekday, matching
 * macOS; otherwise they are listed newest-release first.
 */
@Composable
fun SmartCollectionListScreen(
    title: String,
    items: List<MediaItemEntity>,
    onSelectMedia: (String) -> Unit,
    onBack: () -> Unit,
    groupByWeek: Boolean = false
) {
    BackHandler(onBack = onBack)

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
            val flat = remember(items, groupByWeek) {
                if (groupByWeek) emptyList() else items.sortedWith(compareByDescending { it.releaseDate ?: "" })
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 108.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (groupByWeek) {
                    groups.forEach { group ->
                        item(key = "day_${group.label}") {
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
                            SmartReleaseRow(item = item, onClick = { onSelectMedia(item.id) })
                        }
                    }
                } else {
                    items(flat, key = { it.id }) { item ->
                        SmartReleaseRow(item = item, onClick = { onSelectMedia(item.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SmartReleaseRow(item: MediaItemEntity, onClick: () -> Unit) {
    val isTv = item.type.contains("TV", ignoreCase = true)
    ExpressiveCard(
        onClick = onClick,
        shape = ExpressiveShapes.Card,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)),
        pressedScale = 0.97f,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(46.dp)
                    .height(64.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            ) {
                val cover = item.posterUrl ?: item.backdropUrl
                if (cover != null) {
                    AsyncImage(
                        model = cover,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = if (isTv) Icons.Default.Tv else Icons.Default.Movie,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = formatReleaseDate(item.releaseDate),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                imageVector = if (isTv) Icons.Default.Tv else Icons.Default.Movie,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

private fun formatReleaseDate(releaseDate: String?): String {
    if (releaseDate.isNullOrBlank() || releaseDate.length < 10) return "Release date unknown"
    val parsed = runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(releaseDate.take(10))
    }.getOrNull() ?: return releaseDate
    return SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(parsed)
}
