package com.vara.mediatracker.ui.smarthub

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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vara.mediatracker.data.badge.BadgeResult
import com.vara.mediatracker.data.badge.SmartBadge
import com.vara.mediatracker.data.model.MediaIds
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.di.AppContainer
import com.vara.mediatracker.ui.common.ExpressiveCard
import com.vara.mediatracker.ui.common.ExpressiveSegmentedControl
import com.vara.mediatracker.ui.common.LibraryViewModel
import com.vara.mediatracker.ui.common.rememberTodayDateString
import com.vara.mediatracker.ui.theme.ExpressiveShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The Hub's two tabs. */
private enum class HubTab(val label: String) {
    SMART("Smart"),
    DISCOVER("Discover")
}

/**
 * Hub screen with two tabs: Smart (the system collections) and Discover (watch stats,
 * badges, genres and languages). Counts and membership come from data Android already
 * stores (persisted badges, state, taste, release dates, cached runtimes).
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
    var activeGroupByWeek by remember { mutableStateOf(false) }

    // The library join is produced once, off the UI thread, by the activity-scoped
    // ViewModel (see LibraryViewModel). This screen used to collect the whole ~9k-row
    // episode table and group it on the main thread.
    val libraryVm: LibraryViewModel = viewModel(factory = LibraryViewModel.factory(container))
    val core by libraryVm.core.collectAsState()
    val smartBadgesMap = core.badges
    val episodesByShow = core.episodesByShow
    val todayDate = rememberTodayDateString()

    // Runtime hints for Quick Bites: the cached movie runtime, and a show's typical
    // episode runtime from its episode rows. Eight category passes over the library, so
    // it runs on a background dispatcher and the UI only reads the finished result.
    val memberships by produceState(
        initialValue = emptyMap<SmartCategory, List<MediaItemEntity>>(),
        items, smartBadgesMap, episodesByShow, todayDate
    ) {
        value = withContext(Dispatchers.Default) {
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
    }

    fun openSection(title: String, filtered: List<MediaItemEntity>, groupByWeek: Boolean = false) {
        activeTitle = title
        activeItems = filtered
        activeGroupByWeek = groupByWeek
    }

    val title = activeTitle
    if (title != null) {
        SmartCollectionListScreen(
            title = title,
            items = activeItems,
            onSelectMedia = onSelectMedia,
            onBack = { activeTitle = null; activeItems = emptyList() },
            groupByWeek = activeGroupByWeek
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
                    text = if (hubTab == HubTab.SMART) "Smart Hub" else "Discover",
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
                        onClick = {
                            openSection(
                                title = category.title,
                                filtered = members,
                                // Released This Week is bucketed by weekday, like macOS.
                                groupByWeek = category == SmartCategory.ON_THIS_WEEK
                            )
                        }
                    )
                }
            }

            HubTab.DISCOVER -> HubDiscoverContent(
                items = items,
                allEpisodes = core.allEpisodes,
                badgeRows = core.badgeRows,
                onSelectNode = { title, filtered -> openSection(title, filtered) }
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
