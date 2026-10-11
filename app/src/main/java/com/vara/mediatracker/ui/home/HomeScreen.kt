package com.vara.mediatracker.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.vara.mediatracker.data.model.MediaItemEntity

@Composable
fun HomeScreen(
    items: List<MediaItemEntity>,
    onSelectMedia: (String) -> Unit
) {
    var selectedCategory by remember { mutableStateOf("All") }

    val continueWatching = remember(items) {
        items.filter { it.state.equals("Watching", ignoreCase = true) || it.state.equals("Active", ignoreCase = true) }
    }
    val lovedItems = remember(items) {
        items.filter { it.taste.equals("Loved", ignoreCase = true) || it.taste.equals("Love", ignoreCase = true) }
    }
    val wishlistItems = remember(items) {
        items.filter { it.state.equals("Wishlist", ignoreCase = true) }
    }
    val recentlyCompleted = remember(items) {
        items.filter { it.state.equals("Completed", ignoreCase = true) }
    }

    // Hero Spotlight: top active or loved title with backdrop or poster
    val spotlightItem = remember(continueWatching, lovedItems, items) {
        continueWatching.firstOrNull { it.posterUrl != null }
            ?: lovedItems.firstOrNull { it.posterUrl != null }
            ?: items.firstOrNull { it.posterUrl != null }
    }

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
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // 1. Cinematic Hero Spotlight
        if (spotlightItem != null) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .height(240.dp)
                        .clickable { onSelectMedia(spotlightItem.id) },
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AsyncImage(
                            model = spotlightItem.posterUrl,
                            contentDescription = spotlightItem.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            Color.Black.copy(alpha = 0.5f),
                                            Color.Black.copy(alpha = 0.92f)
                                        )
                                    )
                                )
                        )
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(16.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = if (spotlightItem.state.equals("Watching", ignoreCase = true) || spotlightItem.state.equals("Active", ignoreCase = true)) "NOW WATCHING" else "FEATURED",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = spotlightItem.title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (spotlightItem.overview.isNotBlank()) {
                                Text(
                                    text = spotlightItem.overview,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.8f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. Quick Focus Category Pills
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val categories = listOf("All", "Watching (${continueWatching.size})", "Favorites (${lovedItems.size})", "Watchlist (${wishlistItems.size})", "Completed (${recentlyCompleted.size})")
                items(categories) { cat ->
                    val cleanName = cat.substringBefore(" ")
                    val isSelected = selectedCategory == cleanName
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedCategory = cleanName },
                        label = { Text(cat, fontSize = 12.sp) }
                    )
                }
            }
        }

        // 3. Continue Watching Carousel
        if ((selectedCategory == "All" || selectedCategory == "Watching") && continueWatching.isNotEmpty()) {
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

        // 4. Loved Favorites Carousel
        if ((selectedCategory == "All" || selectedCategory == "Favorites") && lovedItems.isNotEmpty()) {
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

        // 5. Up Next in Wishlist Carousel
        if ((selectedCategory == "All" || selectedCategory == "Watchlist") && wishlistItems.isNotEmpty()) {
            item {
                SectionHeader(title = "From Your Watchlist", count = wishlistItems.size)
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(wishlistItems.take(20), key = { it.id }) { item ->
                        HomePosterCard(item = item, onClick = { onSelectMedia(item.id) })
                    }
                }
            }
        }

        // 6. Recently Completed Carousel
        if ((selectedCategory == "All" || selectedCategory == "Completed") && recentlyCompleted.isNotEmpty()) {
            item {
                SectionHeader(title = "Completed", count = recentlyCompleted.size)
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(recentlyCompleted.take(20), key = { it.id }) { item ->
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
