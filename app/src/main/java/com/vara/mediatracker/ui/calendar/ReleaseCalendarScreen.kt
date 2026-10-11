package com.vara.mediatracker.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vara.mediatracker.data.badge.BadgeEngine
import com.vara.mediatracker.data.badge.BadgeResult
import com.vara.mediatracker.data.badge.SmartBadge
import com.vara.mediatracker.data.db.AppDatabase
import com.vara.mediatracker.di.AppContainer
import com.vara.mediatracker.data.model.EpisodeEntity
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.data.model.MediaIds
import com.vara.mediatracker.ui.common.parseTargetAirDateTime
import com.vara.mediatracker.ui.common.rememberCountdownText
import com.vara.mediatracker.ui.home.SmartBadgePill
import com.vara.mediatracker.ui.theme.AppColors
import java.text.SimpleDateFormat
import java.util.*

/**
 * One row in the release calendar.
 *
 * A show that drops several episodes on the same day is represented by a single
 * event rather than one card per episode — that is how streaming drops actually
 * look, and one card per episode buried everything else under a wall of repeats.
 * Movies are always their own event, since they never share a date with anything.
 */
data class CalendarReleaseEvent(
    val mediaItem: MediaItemEntity,
    val dateString: String,      // "yyyy-MM-dd"
    val isEpisode: Boolean,
    val episodeCount: Int = 0,
    val episodeLabels: List<String> = emptyList()   // ["S2 E4", "S2 E5", ...]
) {
    /** Compact secondary line: "Movie Premiere", "S2 E4", or "S2 E4 +2 more". */
    val contextLabel: String
        get() = when {
            !isEpisode -> "Movie Premiere"
            episodeCount <= 1 -> episodeLabels.firstOrNull().orEmpty()
            else -> "${episodeLabels.first()} +${episodeCount - 1} more"
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReleaseCalendarScreen(
    items: List<MediaItemEntity>,
    container: AppContainer,
    onSelectMedia: (String) -> Unit
) {
    val allEpisodes by container.mediaDao.getAllEpisodesFlow().collectAsState(initial = emptyList())

    val todayCalendar = remember { Calendar.getInstance() }
    val todayString = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }

    var selectedYearMonth by remember {
        mutableStateOf(Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
        })
    }

    var selectedDateString by remember { mutableStateOf<String?>(todayString) }
    var selectedFilter by remember { mutableStateOf("All") } // "All", "Movies", "TV Shows"

    val episodesByShow = remember(allEpisodes) {
        allEpisodes.groupBy { it.showId }
    }

    // Badges are read from Room rather than recomputed here. A background worker
    // keeps this table current, so the UI never runs BadgeEngine across the whole
    // library on the main thread — and badges are already correct on first paint.
    val badgeRows by container.database.badgeDao().observeBadges()
        .collectAsState(initial = emptyList())
    val smartBadgesMap = remember(badgeRows) {
        badgeRows.associate { row ->
            row.mediaId to BadgeResult(
                SmartBadge.fromLabel(row.badgeLabel) ?: SmartBadge.NEW,
                isSparkle = row.isSparkle
            )
        }
    }

    // Compute month boundaries
    val monthFormat = remember { SimpleDateFormat("MMMM yyyy", Locale.getDefault()) }
    val dayOfMonthFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }

    // Precalculate all events in current month
    val monthEventsMap = remember(items, allEpisodes, selectedYearMonth) {
        val cal = selectedYearMonth.clone() as Calendar
        cal.set(Calendar.DAY_OF_MONTH, 1)
        buildMonthReleaseEvents(
            items = items,
            episodes = allEpisodes,
            year = cal.get(Calendar.YEAR),
            month = cal.get(Calendar.MONTH)
        ).groupBy { it.dateString }
    }

    // Days in current month grid
    val daysInMonth = remember(selectedYearMonth) {
        val cal = selectedYearMonth.clone() as Calendar
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val firstDayOfWeek = cal.get(Calendar.DAY_OF_WEEK) // 1 = Sunday, 2 = Monday...

        // Shift so Monday is index 0
        val offset = (firstDayOfWeek - Calendar.MONDAY + 7) % 7

        val list = mutableListOf<String?>()
        repeat(offset) { list.add(null) }

        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        for (day in 1..maxDay) {
            cal.set(Calendar.DAY_OF_MONTH, day)
            list.add(sdf.format(cal.time))
        }
        list
    }

    // Events for selected date or whole month
    val displayedEvents = remember(monthEventsMap, selectedDateString, selectedFilter) {
        val rawList = if (selectedDateString != null) {
            monthEventsMap[selectedDateString] ?: emptyList()
        } else {
            monthEventsMap.values.flatten().sortedBy { it.dateString }
        }

        when (selectedFilter) {
            "Movies" -> rawList.filter { !it.isEpisode }
            "TV Shows" -> rawList.filter { it.isEpisode }
            else -> rawList
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Month Header & Navigation
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            selectedYearMonth = (selectedYearMonth.clone() as Calendar).apply {
                                add(Calendar.MONTH, -1)
                            }
                            selectedDateString = null
                        }) {
                            Icon(Icons.Default.ChevronLeft, contentDescription = "Previous Month")
                        }

                        Text(
                            text = monthFormat.format(selectedYearMonth.time),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        IconButton(onClick = {
                            selectedYearMonth = (selectedYearMonth.clone() as Calendar).apply {
                                add(Calendar.MONTH, 1)
                            }
                            selectedDateString = null
                        }) {
                            Icon(Icons.Default.ChevronRight, contentDescription = "Next Month")
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Days of week header (Mon - Sun)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        listOf("M", "T", "W", "T", "F", "S", "S").forEach { dayLabel ->
                            Text(
                                text = dayLabel,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.width(36.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Month Calendar Grid (7 columns)
                    val chunkedWeeks = daysInMonth.chunked(7)
                    chunkedWeeks.forEach { week ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            week.forEach { dateStr ->
                                if (dateStr == null) {
                                    Spacer(modifier = Modifier.size(36.dp))
                                } else {
                                    val isSelected = selectedDateString == dateStr
                                    val isToday = dateStr == todayString
                                    val dayNum = dateStr.takeLast(2).toIntOrNull()?.toString() ?: ""
                                    val eventCount = monthEventsMap[dateStr]?.size ?: 0

                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(
                                                when {
                                                    isSelected -> AppColors.Accent
                                                    isToday -> AppColors.Accent.copy(alpha = 0.25f)
                                                    eventCount > 0 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                                    else -> Color.Transparent
                                                }
                                            )
                                            .clickable {
                                                selectedDateString = if (isSelected) null else dateStr
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Text(
                                                text = dayNum,
                                                fontSize = 12.sp,
                                                fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Medium,
                                                color = when {
                                                    isSelected -> Color.White
                                                    isToday -> AppColors.Accent
                                                    else -> MaterialTheme.colorScheme.onSurface
                                                }
                                            )
                                            if (eventCount > 0) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(4.dp)
                                                        .clip(CircleShape)
                                                        .background(if (isSelected) Color.White else AppColors.Gold)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
        }

        // 2. Filter chips & Section summary
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("All", "Movies", "TV Shows").forEach { filter ->
                        FilterChip(
                            selected = selectedFilter == filter,
                            onClick = { selectedFilter = filter },
                            label = { Text(filter, fontSize = 11.sp) }
                        )
                    }
                }

                if (selectedDateString != null) {
                    TextButton(onClick = { selectedDateString = null }) {
                        Text("Show All Month", fontSize = 11.sp, color = AppColors.Accent)
                    }
                }
            }
        }

        // 3. Release Items Header
        item {
            val titleText = if (selectedDateString != null) {
                if (selectedDateString == todayString) "Releases Today" else "Releases on $selectedDateString"
            } else {
                "All Releases for ${monthFormat.format(selectedYearMonth.time)}"
            }
            Text(
                text = titleText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        // 4. Release Cards
        if (displayedEvents.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.EventBusy,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (selectedDateString != null) "No scheduled releases on this day" else "No releases scheduled for this month",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(displayedEvents, key = { "${it.mediaItem.id}_${it.dateString}_${it.isEpisode}" }) { event ->
                CalendarReleaseCard(
                    event = event,
                    smartBadge = smartBadgesMap[event.mediaItem.id],
                    onClick = { onSelectMedia(event.mediaItem.id) }
                )
            }
        }
    }
}

@Composable
fun CalendarReleaseCard(
    event: CalendarReleaseEvent,
    smartBadge: BadgeResult? = null,
    onClick: () -> Unit
) {
    val item = event.mediaItem
    val targetAirDate = remember(event.dateString, item.title, item.overview) {
        parseTargetAirDateTime(event.dateString, "${item.title} ${item.overview}")
    }
    val countdown = rememberCountdownText(targetAirDate)

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Poster Artwork Thumbnail
            Box(
                modifier = Modifier
                    .width(60.dp)
                    .height(90.dp)
                    .clip(RoundedCornerShape(10.dp))
            ) {
                if (item.posterUrl != null) {
                    AsyncImage(
                        model = item.posterUrl,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (item.type.contains("TV", ignoreCase = true)) Icons.Default.Tv else Icons.Default.Movie,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Metadata & Episode description
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (smartBadge != null) {
                        SmartBadgePill(badge = smartBadge.badge, isSparkle = smartBadge.isSparkle)
                    } else {
                        Surface(
                            color = Color.Black.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = if (item.type.contains("TV", ignoreCase = true)) "TV" else "FILM",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White.copy(alpha = 0.9f),
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }

                    if (countdown != null) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            ) {
                                Icon(
                                    Icons.Default.HourglassEmpty,
                                    contentDescription = null,
                                    tint = AppColors.Gold,
                                    modifier = Modifier.size(8.dp)
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text = countdown,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }

                    // Multi-episode drop: surfaces how many episodes land today,
                    // which the single context label can only hint at.
                    if (event.isEpisode && event.episodeCount > 1) {
                        Surface(
                            color = AppColors.Accent.copy(alpha = 0.9f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "${event.episodeCount} EP",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = event.contextLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = "Airs on " + event.dateString,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = AppColors.Gold
                )
            }
        }
    }
}
