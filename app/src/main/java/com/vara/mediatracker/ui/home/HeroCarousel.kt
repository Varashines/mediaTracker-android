package com.vara.mediatracker.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vara.mediatracker.data.badge.BadgeResult
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.ui.theme.LocalMotion
import kotlinx.coroutines.launch

/**
 * Full-bleed paging hero.
 *
 * Home previously opened with five horizontal rails of identically-shaped posters, so
 * nothing on the screen had priority and the answer to "what should I watch" required
 * scanning. A paging carousel makes exactly one title the focal point and lets the
 * thumb move between candidates instead of dragging a thin rail sideways.
 *
 * Full-bleed rather than inset: the artwork is the content, and page dots carry the
 * position without spending a row on a header.
 */
@Composable
fun HeroCarousel(
    items: List<MediaItemEntity>,
    badges: Map<String, BadgeResult?>,
    progress: Map<String, TVShowProgress>,
    onSelectMedia: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return
    val motion = LocalMotion.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { items.size })

    Box(modifier = modifier.fillMaxWidth()) {
        HorizontalPager(
            state = pagerState,
            pageSpacing = 12.dp,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            modifier = Modifier.fillMaxWidth()
        ) { page ->
            val item = items[page]
            val info = progress[item.id]

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(268.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .clickable { onSelectMedia(item.id) }
            ) {
                AsyncImage(
                    model = item.backdropUrl ?: item.posterUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                // Two-stop scrim: enough to hold text at the bottom without flattening
                // the artwork the way a single flat overlay did.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.0f to Color.Black.copy(alpha = 0.05f),
                                0.45f to Color.Black.copy(alpha = 0.25f),
                                1.0f to Color.Black.copy(alpha = 0.92f)
                            )
                        )
                )

                badges[item.id]?.let {
                    SmartBadgePill(
                        badge = it.badge,
                        isSparkle = it.isSparkle,
                        modifier = Modifier.align(Alignment.TopStart).padding(14.dp)
                    )
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    val subtitle = when {
                        !item.type.contains("TV", ignoreCase = true) ->
                            item.releaseDate?.take(4)?.let { "Film • $it" } ?: "Film"
                        info?.nextEpisode != null ->
                            "Next: S${info.nextEpisode.seasonNumber} E${info.nextEpisode.episodeNumber}  •  ${info.watchedEpisodes}/${info.totalEpisodes} watched"
                        info != null && info.totalEpisodes > 0 ->
                            "${info.watchedEpisodes}/${info.totalEpisodes} watched"
                        else -> "Continue watching"
                    }
                    Text(
                        text = subtitle,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                    if (info != null && info.totalEpisodes > 0) {
                        Spacer(Modifier.height(6.dp))
                        val ratio = (info.watchedEpisodes.toFloat() / info.totalEpisodes).coerceIn(0f, 1f)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.55f)
                                .height(3.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.28f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(ratio)
                                    .height(3.dp)
                                    .clip(CircleShape)
                                    .background(Color.White)
                            )
                        }
                    }
                }
            }
        }

        if (items.size > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 22.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items.indices.forEach { index ->
                    val selected = pagerState.currentPage == index
                    val width by animateFloatAsState(
                        targetValue = if (selected) 16.dp.value else 6.dp.value,
                        animationSpec = motion.gentle,
                        label = "dotWidth"
                    )
                    val color by animateColorAsState(
                        targetValue = if (selected) Color.White else Color.White.copy(alpha = 0.4f),
                        animationSpec = tween(motion.STANDARD),
                        label = "dotColor"
                    )
                    Box(
                        modifier = Modifier
                            .width(width.dp)
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(color)
                            .clickable { scope.launch { pagerState.animateScrollToPage(index) } }
                    )
                }
            }
        }
    }
}

/**
 * Dense strip for time-sensitive releases.
 *
 * Deliberately not poster-sized. These need to be scannable as a timeline, not
 * admired — giving them full posters put them at the same visual weight as the hero
 * and the browsing rails.
 */
@Composable
fun ReleaseStrip(
    items: List<MediaItemEntity>,
    badges: Map<String, BadgeResult?>,
    onSelectMedia: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return
    LazyRow(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(items = items, key = { it.id }) { item ->
            val badge = badges[item.id]?.badge
            val accent = when (badge) {
                com.vara.mediatracker.data.badge.SmartBadge.PREMIERE -> Color(0xFFD946EF)
                com.vara.mediatracker.data.badge.SmartBadge.NEW -> Color(0xFF10B981)
                com.vara.mediatracker.data.badge.SmartBadge.SOON -> Color(0xFFF59E0B)
                else -> Color(0xFF0A84FF)
            }
            Column(
                modifier = Modifier
                    .width(58.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onSelectMedia(item.id) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = item.releaseDate?.takeLast(2) ?: "--",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = accent
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = item.title,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    lineHeight = 12.sp
                )
            }
        }
    }
}
