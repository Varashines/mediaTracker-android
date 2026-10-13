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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Color
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
 * Each section is a two-column grid capped at [TOP_N] entries, so the hub stays a
 * scannable overview instead of a wall of chips. Tapping a tile opens the filtered
 * titles in the shared section list.
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
        verticalArrangement = Arrangement.spacedBy(20.dp)
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
            item(key = "badges") {
                FacetSection(
                    title = "Recent Activity",
                    icon = Icons.Default.AutoAwesome,
                    subtitle = "Your library, lately",
                    nodes = badges,
                    style = FacetStyle.BADGE
                ) { node -> onSelectNode(node.name, filtered(DiscoveryFacet.BADGE, node.name)) }
            }
        }

        if (networks.isNotEmpty()) {
            item(key = "networks") {
                FacetSection(
                    title = "Networks",
                    icon = Icons.Default.Tv,
                    subtitle = "Top ${minOf(networks.size, TOP_N)} of ${networks.size}",
                    nodes = networks,
                    style = FacetStyle.NETWORK
                ) { node -> onSelectNode(node.name, filtered(DiscoveryFacet.NETWORK, node.name)) }
            }
        }

        if (studios.isNotEmpty()) {
            item(key = "studios") {
                FacetSection(
                    title = "Studios",
                    icon = Icons.Default.LocalMovies,
                    subtitle = "Top ${minOf(studios.size, TOP_N)} of ${studios.size}",
                    nodes = studios,
                    style = FacetStyle.NETWORK
                ) { node -> onSelectNode(node.name, filtered(DiscoveryFacet.STUDIO, node.name)) }
            }
        }

        if (genres.isNotEmpty()) {
            item(key = "genres") {
                FacetSection(
                    title = "Genres",
                    icon = Icons.Default.Movie,
                    subtitle = "${genres.size} represented",
                    nodes = genres,
                    style = FacetStyle.PLAIN
                ) { node -> onSelectNode(node.name, filtered(DiscoveryFacet.GENRE, node.name)) }
            }
        }

        if (languages.isNotEmpty()) {
            item(key = "languages") {
                FacetSection(
                    title = "Languages",
                    icon = Icons.Default.Language,
                    subtitle = "${languages.size} represented",
                    nodes = languages,
                    style = FacetStyle.PLAIN
                ) { node -> onSelectNode(node.name, filtered(DiscoveryFacet.LANGUAGE, node.name)) }
            }
        }

        if (providers.isNotEmpty()) {
            item(key = "providers") {
                FacetSection(
                    title = "Providers",
                    icon = Icons.Default.Public,
                    subtitle = "${providers.size} available",
                    nodes = providers,
                    style = FacetStyle.PLAIN
                ) { node -> onSelectNode(node.name, filtered(DiscoveryFacet.PROVIDER, node.name)) }
            }
        }
    }
}

private const val TOP_N = 12

private enum class FacetStyle { NETWORK, BADGE, PLAIN }

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
            Spacer(modifier = Modifier.height(10.dp))
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
    Column(modifier = Modifier.width(92.dp)) {
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

@Composable
private fun FacetSection(
    title: String,
    icon: ImageVector,
    subtitle: String,
    nodes: List<DiscoveryNode>,
    style: FacetStyle,
    onNodeClick: (DiscoveryNode) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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

        Spacer(modifier = Modifier.height(10.dp))

        nodes.take(TOP_N).chunked(2).forEach { rowNodes ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                rowNodes.forEach { node ->
                    FacetTile(
                        node = node,
                        style = style,
                        onClick = { onNodeClick(node) },
                        modifier = Modifier.weight(1f)
                    )
                }
                // Keep the last odd tile at half width rather than stretching it.
                if (rowNodes.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FacetTile(
    node: DiscoveryNode,
    style: FacetStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val badge = if (style == FacetStyle.BADGE) SmartBadge.fromLabel(node.name) else null
    val network = if (style == FacetStyle.NETWORK) {
        remember(node.name) { NetworkResolver.resolveByName(node.name, isTV = true) }
    } else null

    val markerColor: Color = badge?.backgroundColor
        ?: network?.badgeColor
        ?: MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    val markerTextColor: Color = badge?.textColor
        ?: network?.textColor
        ?: MaterialTheme.colorScheme.primary
    val markerLabel: String = network?.shortName?.take(3)?.uppercase()
        ?: node.name.take(2).uppercase()

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = RoundedCornerShape(9.dp), color = markerColor, modifier = Modifier.size(32.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = markerLabel,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        color = markerTextColor
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
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
