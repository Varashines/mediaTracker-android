package com.vara.mediatracker.ui.discovery

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LocalMovies
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vara.mediatracker.data.badge.BadgeResult
import com.vara.mediatracker.data.model.MediaBadgeEntity
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.ui.common.EmptyState

/**
 * Discovery Hub: the library aggregated into Networks, Studios, Genres, Languages,
 * Providers, and Recent Activity. Tapping a node hands back the filtered titles for the
 * caller to open in the shared section list.
 */
@Composable
fun DiscoveryHubContent(
    items: List<MediaItemEntity>,
    badgeRows: List<MediaBadgeEntity>,
    smartBadgesMap: Map<String, BadgeResult>,
    onSelectNode: (title: String, filtered: List<MediaItemEntity>) -> Unit,
    modifier: Modifier = Modifier
) {
    val networks = remember(items) { DiscoveryAggregator.networks(items) }
    val studios = remember(items) { DiscoveryAggregator.studios(items) }
    val genres = remember(items) { DiscoveryAggregator.genres(items) }
    val languages = remember(items) { DiscoveryAggregator.languages(items) }
    val providers = remember(items) { DiscoveryAggregator.providers(items) }
    val badges = remember(badgeRows) { DiscoveryAggregator.badges(badgeRows) }

    val hasAny = networks.isNotEmpty() || studios.isNotEmpty() || genres.isNotEmpty() ||
        languages.isNotEmpty() || providers.isNotEmpty() || badges.isNotEmpty()

    if (!hasAny) {
        EmptyState(
            icon = Icons.Default.AutoAwesome,
            title = "No discovery data yet",
            body = "Add some titles to your library to see discovery insights here.",
            modifier = modifier
        )
        return
    }

    fun filtered(facet: DiscoveryFacet, nodeName: String): List<MediaItemEntity> =
        items.filter { item ->
            DiscoveryAggregator.matches(facet, item, nodeName, smartBadgesMap[item.id]?.badge?.label)
        }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        if (badges.isNotEmpty()) {
            discoverySection(
                title = "Recent Activity",
                icon = Icons.Default.AutoAwesome,
                subtitle = "Your library, lately",
                nodes = badges
            ) { node -> onSelectNode(node.name, filtered(DiscoveryFacet.BADGE, node.name)) }
        }

        if (networks.isNotEmpty()) {
            discoverySection(
                title = "Networks",
                icon = Icons.Default.Tv,
                subtitle = "${networks.size} in your library",
                nodes = networks
            ) { node -> onSelectNode(node.name, filtered(DiscoveryFacet.NETWORK, node.name)) }
        }

        if (studios.isNotEmpty()) {
            discoverySection(
                title = "Studios",
                icon = Icons.Default.LocalMovies,
                subtitle = "${studios.size} in your library",
                nodes = studios
            ) { node -> onSelectNode(node.name, filtered(DiscoveryFacet.STUDIO, node.name)) }
        }

        if (genres.isNotEmpty()) {
            discoverySection(
                title = "Genres",
                icon = Icons.Default.Movie,
                subtitle = "${genres.size} represented",
                nodes = genres
            ) { node -> onSelectNode(node.name, filtered(DiscoveryFacet.GENRE, node.name)) }
        }

        if (languages.isNotEmpty()) {
            discoverySection(
                title = "Languages",
                icon = Icons.Default.Language,
                subtitle = "${languages.size} represented",
                nodes = languages
            ) { node -> onSelectNode(node.name, filtered(DiscoveryFacet.LANGUAGE, node.name)) }
        }

        if (providers.isNotEmpty()) {
            discoverySection(
                title = "Providers",
                icon = Icons.Default.Public,
                subtitle = "${providers.size} available",
                nodes = providers
            ) { node -> onSelectNode(node.name, filtered(DiscoveryFacet.PROVIDER, node.name)) }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.discoverySection(
    title: String,
    icon: ImageVector,
    subtitle: String,
    nodes: List<DiscoveryNode>,
    onNodeClick: (DiscoveryNode) -> Unit
) {
    item(key = "header_$title") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                modifier = Modifier.width(30.dp).height(30.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(16.dp).height(16.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    item(key = "rail_$title") {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(nodes, key = { "$title-${it.name}" }) { node ->
                DiscoveryNodeChip(node = node, onClick = { onNodeClick(node) })
            }
        }
    }
}

@Composable
private fun DiscoveryNodeChip(node: DiscoveryNode, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)),
        modifier = Modifier.widthIn(min = 120.dp, max = 200.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .width(6.dp)
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = node.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${node.count} ${if (node.count == 1) "title" else "titles"}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
