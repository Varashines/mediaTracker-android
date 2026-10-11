package com.vara.mediatracker.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import com.vara.mediatracker.data.model.MediaItemEntity

@Composable
fun HomeScreen(
    items: List<MediaItemEntity>,
    onSelectMedia: (String) -> Unit
) {
    val continueWatching = items.filter { it.state.equals("Watching", ignoreCase = true) || it.state.equals("Active", ignoreCase = true) }
    val lovedItems = items.filter { it.taste.equals("Loved", ignoreCase = true) || it.taste.equals("Love", ignoreCase = true) }
    val wishlistItems = items.filter { it.state.equals("Wishlist", ignoreCase = true) }
    val recentlyCompleted = items.filter { it.state.equals("Completed", ignoreCase = true) }

    if (items.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.CloudSync,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text("Your Library is Empty", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Sync with Supabase in Settings to import your titles",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // 1. Continue Watching Carousel
        if (continueWatching.isNotEmpty()) {
            item {
                SectionHeader(title = "Continue Watching", count = continueWatching.size)
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(continueWatching, key = { it.id }) { item ->
                        HomePosterCard(item = item, onClick = { onSelectMedia(item.id) })
                    }
                }
            }
        }

        // 2. Loved Favorites Carousel
        if (lovedItems.isNotEmpty()) {
            item {
                SectionHeader(title = "Loved Favorites (❤️)", count = lovedItems.size)
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(lovedItems, key = { it.id }) { item ->
                        HomePosterCard(item = item, onClick = { onSelectMedia(item.id) })
                    }
                }
            }
        }

        // 3. Up Next in Wishlist Carousel
        if (wishlistItems.isNotEmpty()) {
            item {
                SectionHeader(title = "From Your Watchlist", count = wishlistItems.size)
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(wishlistItems.take(15), key = { it.id }) { item ->
                        HomePosterCard(item = item, onClick = { onSelectMedia(item.id) })
                    }
                }
            }
        }

        // 4. Recently Completed Carousel
        if (recentlyCompleted.isNotEmpty()) {
            item {
                SectionHeader(title = "Completed", count = recentlyCompleted.size)
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(recentlyCompleted.take(15), key = { it.id }) { item ->
                        HomePosterCard(item = item, onClick = { onSelectMedia(item.id) })
                    }
                }
            }
        }
    }
}

@Composable
fun SectionHeader(title: String, count: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 16.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.width(6.dp))
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Text(
                text = count.toString(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
fun HomePosterCard(item: MediaItemEntity, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .width(130.dp)
            .clickable(onClick = onClick)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(185.dp)
            ) {
                if (item.posterUrl != null) {
                    AsyncImage(
                        model = item.posterUrl,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                if (item.taste == "Loved" || item.taste == "Love") {
                    Surface(
                        color = Color(0xFFE91E63).copy(alpha = 0.9f),
                        shape = RoundedCornerShape(bottomStart = 8.dp),
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier
                                .padding(4.dp)
                                .size(12.dp)
                        )
                    }
                }
            }

            Text(
                text = item.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
    }
}
