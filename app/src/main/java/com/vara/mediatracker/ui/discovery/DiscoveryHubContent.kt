package com.vara.mediatracker.ui.discovery

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import com.vara.mediatracker.data.badge.SmartBadge
import com.vara.mediatracker.data.model.MediaBadgeEntity
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.ui.common.EmptyState
import com.vara.mediatracker.util.NetworkResolver

/**
 * Discovery Hub: the library aggregated into Networks, Studios, Genres, Languages,
 * Providers, and Recent Activity.
 *
 * Tapping a node hands back the filtered titles for the caller to open in the shared
 * section list. While the metadata backfill is still running a progress note explains
 * why sections may look sparse.
 */
@OptIn(ExperimentalLayoutApi::class)
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

    val enriched = remember(items) { items.count { it.genres != null } }
    val isIndexing = enriched < items.size

    val hasAny = networks.isNotEmpty() || studios.isNotEmpty() || genres.isNotEmpty() ||
        languages.isNotEmpty() || providers.isNotEmpty() || badges.isNotEmpty()

    if (!hasAny) {
        EmptyState(
            icon = Icons.Default.AutoAwesome,
            title = if (isIndexing) "Indexing your library…" else "No discovery data yet",
            body = if (isIndexing) {
                "Reading genres, languages, and providers from TMDB. This fills in over a few passes."
            } else {
                "Add some titles to your library to see discovery insights here."
            },
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
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        item(key = "summary") {
            DiscoverySummary(
                total = items.size,
                enriched = enriched,
                networks = networks.size,
                genres = genres.size
            )
        }

        if (badges.isNotEmpty()) {
            badgeRail(badges) { node -> onSelectNode(node.name, filtered(DiscoveryFacet.BADGE, node.name)) }
        }

        if (networks.isNotEmpty()) {
            networkRail(networks) { node -> onSelectNode(node.name, filtered(DiscoveryFacet.NETWORK, node.name)) }
        }

        if (studios.isNotEmpty()) {
            chipSection(
                title = "Studios",
                icon = Icons.Default.LocalMovies,
                subtitle = "${studios.size} represented",
                nodes = studios
            ) { node -> onSelectNode(node.name, filtered(DiscoveryFacet.STUDIO, node.name)) }
        }

        if (genres.isNotEmpty()) {
            chipSection(
                title = "Genres",
                icon = Icons.Default.Movie,
                subtitle = "${genres.size} represented",
                nodes = genres
            ) { node -> onSelectNode(node.name, filtered(DiscoveryFacet.GENRE, node.name)) }
        }

        if (languages.isNotEmpty()) {
            chipSection(
                title = "Languages",
                icon = Icons.Default.Language,
                subtitle = "${languages.size} represented",
                nodes = languages
            ) { node -> onSelectNode(node.name, filtered(DiscoveryFacet.LANGUAGE, node.name)) }
        }

        if (providers.isNotEmpty()) {
            chipSection(
                title = "Providers",
                icon = Icons.Default.Public,
                subtitle = "${providers.size} available",
                nodes = providers
            ) { node -> onSelectNode(node.name, filtered(DiscoveryFacet.PROVIDER, node.name)) }
        }
    }
}

@Composable
private fun DiscoverySummary(total: Int, enriched: Int, networks: Int, genres: Int) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "YOUR LIBRARY, MAPPED",
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.0.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                SummaryStat(total, "titles")
                SummaryStat(networks, "networks")
                SummaryStat(genres, "genres")
            }
            if (enriched < total) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Indexing $enriched of $total titles…",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SummaryStat(value: Int, label: String) {
    Column(modifier = Modifier.width(90.dp)) {
        Text(
            text = "$value",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun LazyListScope.badgeRail(
    nodes: List<DiscoveryNode>,
    onNodeClick: (DiscoveryNode) -> Unit
) {
    item(key = "recent_header") {
        SectionHeader(
            title = "Recent Activity",
            icon = Icons.Default.AutoAwesome,
            subtitle = "Your library, lately"
        )
    }
    item(key = "recent_rail") {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(nodes, key = { "badge-${it.name}" }) { node ->
                val badge = SmartBadge.fromLabel(node.name)
                Surface(
                    onClick = { onNodeClick(node) },
                    shape = RoundedCornerShape(16.dp),
                    color = badge?.backgroundColor ?: MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = badge?.textColor ?: MaterialTheme.colorScheme.onSurface
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            text = "${node.count}",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = node.name,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.4.sp
                        )
                    }
                }
            }
        }
    }
}

private fun LazyListScope.networkRail(
    nodes: List<DiscoveryNode>,
    onNodeClick: (DiscoveryNode) -> Unit
) {
    item(key = "networks_header") {
        SectionHeader(
            title = "Networks",
            icon = Icons.Default.Tv,
            subtitle = "${nodes.size} in your library"
        )
    }
    item(key = "networks_rail") {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(nodes, key = { "net-${it.name}" }) { node ->
                val info = remember(node.name) { NetworkResolver.resolveByName(node.name, isTV = true) }
                Surface(
                    onClick = { onNodeClick(node) },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)),
                    modifier = Modifier.widthIn(min = 120.dp, max = 180.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = info.badgeColor,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Text(
                                    text = info.shortName.take(3),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black,
                                    color = info.textColor
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = node.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${node.count} ${if (node.count == 1) "title" else "titles"}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
private fun LazyListScope.chipSection(
    title: String,
    icon: ImageVector,
    subtitle: String,
    nodes: List<DiscoveryNode>,
    onNodeClick: (DiscoveryNode) -> Unit
) {
    item(key = "chips_$title") {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            SectionHeader(title = title, icon = icon, subtitle = subtitle, padded = false)
            Spacer(modifier = Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                nodes.forEach { node ->
                    DiscoveryChip(node = node, onClick = { onNodeClick(node) })
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    icon: ImageVector,
    subtitle: String,
    padded: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (padded) Modifier.padding(horizontal = 16.dp) else Modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
            modifier = Modifier.size(30.dp)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
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

@Composable
private fun DiscoveryChip(node: DiscoveryNode, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = node.name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${node.count}",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
