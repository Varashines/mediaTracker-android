package com.vara.mediatracker.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
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
import androidx.compose.runtime.remember
import com.vara.mediatracker.ui.theme.AppTextStyles
import com.vara.mediatracker.util.AirTimeResolver
import com.vara.mediatracker.data.badge.BadgeResult
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.ui.home.SmartBadgePill
import com.vara.mediatracker.ui.common.TVShowProgress
import com.vara.mediatracker.ui.theme.ExpressiveShapes
import com.vara.mediatracker.util.NetworkResolver

/**
 * Reusable Full-Screen List View for Home & Discovery sections
 * (e.g. Up Next / Continue Watching, Coming Soon, Recently Added, Loved).
 */
@Composable
fun SectionListScreen(
    title: String,
    items: List<MediaItemEntity>,
    showProgressMap: Map<String, TVShowProgress> = emptyMap(),
    smartBadgesMap: Map<String, BadgeResult> = emptyMap(),
    networkMap: Map<String, String> = emptyMap(),
    onSelectMedia: (String) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        // Sticky Header with Back Button
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
                    text = "${items.size} titles",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (items.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No titles in this section",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 140.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(items, key = { it.id }) { item ->
                    val progress = showProgressMap[item.id]
                    val smartBadge = smartBadgesMap[item.id]
                    val realNet = networkMap[item.id]
                    val netInfo = if (!realNet.isNullOrBlank()) {
                        NetworkResolver.resolveByName(realNet, isTV = item.type.contains("TV", ignoreCase = true))
                    } else {
                        NetworkResolver.resolve(item)
                    }

                    ExpressiveCard(
                        onClick = { onSelectMedia(item.id) },
                        shape = ExpressiveShapes.Card,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Poster Thumbnail — matches the Home row size (56x74).
                            Box(
                                modifier = Modifier
                                    .width(56.dp)
                                    .height(74.dp)
                                    .clip(RoundedCornerShape(12.dp))
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
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                    )
                                }

                                // Type Icon Badge
                                Surface(
                                    color = Color.Black.copy(alpha = 0.72f),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(4.dp)
                                ) {
                                    Icon(
                                        imageVector = if (item.type.contains("TV", ignoreCase = true)) Icons.Default.Tv else Icons.Default.Movie,
                                        contentDescription = null,
                                        tint = Color.White.copy(alpha = 0.9f),
                                        modifier = Modifier
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                            .size(11.dp)
                                    )
                                }

                                if (item.taste.equals("Loved", ignoreCase = true) || item.taste.equals("Love", ignoreCase = true)) {
                                    Surface(
                                        color = Color(0xFFFF2D55).copy(alpha = 0.9f),
                                        shape = CircleShape,
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Favorite,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier
                                                .padding(3.dp)
                                                .size(9.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = netInfo.name.uppercase(),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.primary,
                                        letterSpacing = 0.5.sp
                                    )

                                    // Days-left from the resolved drop instant (rules),
                                    // matching the Home row. Falls back to the badge.
                                    val nextEp = progress?.nextEpisode
                                    val daysLeftLabel = remember(nextEp?.uniqueId, item.timezone, item.network) {
                                        if (nextEp == null) return@remember null
                                        val instant = AirTimeResolver.resolveAirInstant(
                                            dateString = nextEp.airDate?.take(10),
                                            time = nextEp.airTime,
                                            airstamp = nextEp.airstamp,
                                            timezone = item.timezone,
                                            serviceName = AirTimeResolver.serviceHint(
                                                networkMap[item.id] ?: item.network, item.studios, item.genres
                                            )
                                        ) ?: return@remember null
                                        val hours = (instant.time - System.currentTimeMillis()) / 3_600_000.0
                                        when {
                                            hours < 0 -> "READY"
                                            hours < 24 -> "AIRING TODAY"
                                            hours < 48 -> "1 day left"
                                            else -> "${(hours / 24).toInt()} days left"
                                        }
                                    }
                                    if (daysLeftLabel != null) {
                                        Text(
                                            text = daysLeftLabel,
                                            style = AppTextStyles.MonoLabel,
                                            color = if (daysLeftLabel == "READY") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    } else if (smartBadge != null) {
                                        SmartBadgePill(badge = smartBadge.badge, isSparkle = smartBadge.isSparkle)
                                    }
                                }

                                Spacer(modifier = Modifier.height(3.dp))

                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                val subDesc = progress?.nextEpisode?.let {
                                    "S${it.seasonNumber} E${it.episodeNumber} • \"${it.name.ifBlank { "Episode " + it.episodeNumber }}\""
                                } ?: item.releaseDate?.take(10) ?: "Ready to watch"

                                Text(
                                    text = subDesc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                if (progress != null && progress.totalEpisodes > 0) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    val watched = progress.watchedEpisodes
                                    val total = progress.totalEpisodes
                                    val ratio = (watched.toFloat() / total.toFloat()).coerceIn(0f, 1f)

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        LinearProgressIndicator(
                                            progress = { ratio },
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(5.dp)
                                                .clip(RoundedCornerShape(3.dp)),
                                            color = MaterialTheme.colorScheme.primary,
                                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                                        )
                                        Text(
                                            text = "$watched/$total",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
