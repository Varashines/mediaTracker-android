package com.vara.mediatracker.ui.smarthub

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.vara.mediatracker.data.model.MediaIds
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.di.AppContainer
import com.vara.mediatracker.ui.common.ExpressiveCard
import com.vara.mediatracker.ui.common.ExpressiveSegmentedControl
import com.vara.mediatracker.ui.common.SectionListScreen
import com.vara.mediatracker.ui.common.rememberTodayDateString
import com.vara.mediatracker.ui.discovery.DiscoveryHubContent
import com.vara.mediatracker.ui.home.TVShowProgress
import com.vara.mediatracker.ui.theme.ExpressiveShapes

/** The two hubs hosted by the Smart tab. */
private enum class HubTab(val label: String) {
    SMART("Smart"),
    DISCOVER("Discover")
}

/**
 * Hub screen hosting the Smart collections and the Discovery Hub behind one tab.
 *
 * Smart counts and membership come from data Android already stores (persisted badges,
 * state, taste, release dates, cached runtimes); Discovery aggregates the facet columns
 * backfilled by MetadataEnrichmentWorker. Both open the shared SectionListScreen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartHubScreen(
    items: List<MediaItemEntity>,
    container: AppContainer,
    onSelectMedia: (String) -> Unit,
    onOpenSearch: (() -> Unit)? = null
) {
    var hubTab by remember { mutableStateOf(HubTab.SMART) }
    var activeTitle by remember { mutableStateOf<String?>(null) }
    var activeItems by remember { mutableStateOf<List<MediaItemEntity>>(emptyList()) }

    val allEpisodes by container.mediaDao.getAllEpisodesFlow().collectAsState(initial = emptyList())
    val badgeRows by container.database.badgeDao().observeBadges().collectAsState(initial = emptyList())
    val smartBadgesMap = remember(badgeRows) {
        badgeRows.associate { row ->
            row.mediaId to BadgeResult(
                SmartBadge.fromLabel(row.badgeLabel) ?: SmartBadge.NEW,
                isSparkle = row.isSparkle
            )
        }
    }
    val episodesByShow = remember(allEpisodes) { allEpisodes.groupBy { it.showId } }
    val todayDate = rememberTodayDateString()

    // Runtime hints for Quick Bites: the cached movie runtime, and a show's typical
    // episode runtime from its episode rows.
    val memberships = remember(items, smartBadgesMap, episodesByShow, todayDate) {
        SmartCategory.values().associateWith { category ->
            items.filter { item ->
                val key = if (item.type.contains("TV", ignoreCase = true)) MediaIds.tvShowKey(item.id) else null
                SmartCategoryMatcher.matches(
                    category = category,
                    item = item,
                    badgeLabel = smartBadgesMap[item.id]?.badge?.label,
                    runtimeMinutes = container.preferences.getCachedRuntime(item.id),
                    episodeRuntimeMinutes = key?.let { k ->
                        episodesByShow[k]?.firstOrNull { (it.runtime ?: 0) > 0 }?.runtime
                    }
                )
            }
        }
    }

    val showProgressMap = remember(items, episodesByShow, todayDate) {
        items.filter { it.type.contains("TV", ignoreCase = true) }.associate { item ->
            val showKey = MediaIds.tvShowKey(item.id)
            val showEps = (episodesByShow[showKey] ?: episodesByShow[item.id] ?: emptyList())
                .sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))
            item.id to TVShowProgress(
                totalEpisodes = showEps.size,
                watchedEpisodes = showEps.count { it.isWatched },
                nextEpisode = showEps.firstOrNull { !it.isWatched },
                nextAiringDate = showEps.firstOrNull { ep ->
                    !ep.isWatched && !ep.airDate.isNullOrBlank() && ep.airDate > todayDate
                }?.airDate
            )
        }
    }

    val networkMap = remember(items) {
        buildMap {
            items.forEach { item ->
                container.preferences.getCachedNetwork(item.id)?.let { put(item.id, it) }
            }
        }
    }

    fun openSection(title: String, filtered: List<MediaItemEntity>) {
        activeTitle = title
        activeItems = filtered
    }

    val title = activeTitle
    if (title != null) {
        BackHandler { activeTitle = null; activeItems = emptyList() }
        SectionListScreen(
            title = title,
            items = activeItems,
            showProgressMap = showProgressMap,
            smartBadgesMap = smartBadgesMap,
            networkMap = networkMap,
            onSelectMedia = onSelectMedia,
            onBack = { activeTitle = null; activeItems = emptyList() }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "CURATED",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 1.2.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (hubTab == HubTab.SMART) "Smart Hub" else "Discovery Hub",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (onOpenSearch != null) {
                IconButton(
                    onClick = onOpenSearch,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }

        ExpressiveSegmentedControl(
            items = HubTab.values().toList(),
            selectedItem = hubTab,
            onItemSelected = { hubTab = it },
            labelProvider = { it.label },
            iconProvider = { if (it == HubTab.SMART) Icons.Default.AutoAwesome else Icons.Default.Explore },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        when (hubTab) {
            HubTab.SMART -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 108.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(SmartCategory.values().toList(), key = { it.name }) { category ->
                    val members = memberships[category].orEmpty()
                    SmartCategoryCard(
                        category = category,
                        count = members.size,
                        onClick = { openSection(category.title, members) }
                    )
                }
            }

            HubTab.DISCOVER -> DiscoveryHubContent(
                items = items,
                badgeRows = badgeRows,
                smartBadgesMap = smartBadgesMap,
                onSelectNode = { sectionTitle, filtered -> openSection(sectionTitle, filtered) }
            )
        }
    }
}

@Composable
private fun SmartCategoryCard(
    category: SmartCategory,
    count: Int,
    onClick: () -> Unit
) {
    ExpressiveCard(
        onClick = onClick,
        shape = ExpressiveShapes.Card,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)),
        pressedScale = 0.96f,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 132.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = category.icon(),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (count > 0) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    }
                ) {
                    Text(
                        text = "$count",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        color = if (count > 0) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = category.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = category.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun SmartCategory.icon(): ImageVector = when (this) {
    SmartCategory.RELEASE_RADAR -> Icons.Default.AutoAwesome
    SmartCategory.SMART_UPCOMING -> Icons.Default.Event
    SmartCategory.CATCH_UP -> Icons.Default.Undo
    SmartCategory.LOVED -> Icons.Default.Favorite
    SmartCategory.BINGE -> Icons.Default.Collections
    SmartCategory.QUICK_BITES -> Icons.Default.Timer
    SmartCategory.ARCHIVE -> Icons.Default.Archive
    SmartCategory.ON_THIS_WEEK -> Icons.Default.DateRange
}
