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
import com.vara.mediatracker.ui.common.ExpressiveCard
import com.vara.mediatracker.ui.common.formatReleaseDay
import com.vara.mediatracker.ui.common.rememberCountdownText
import com.vara.mediatracker.ui.common.rememberTodayDateString
import com.vara.mediatracker.ui.home.SectionHeader
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

    val todayString = rememberTodayDateString()

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
        // Clears the floating nav bar plus the gesture inset. At 24dp the last card
        // rendered underneath it.
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Month grid
        item {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                MonthCalendarGrid(
                    month = selectedYearMonth,
                    todayString = todayString,
                    eventsByDate = monthEventsMap,
                    selectedDate = selectedDateString,
                    onSelectDate = { selectedDateString = it },
                    onPreviousMonth = {
                        selectedYearMonth = (selectedYearMonth.clone() as Calendar).apply {
                            add(Calendar.MONTH, -1)
                        }
                        selectedDateString = null
                    },
                    onNextMonth = {
                        selectedYearMonth = (selectedYearMonth.clone() as Calendar).apply {
                            add(Calendar.MONTH, 1)
                        }
                        selectedDateString = null
                    },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)
                )
            }
        }

        // 2. Filters
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("All", "Movies", "TV Shows").forEach { filter ->
                        FilterChip(
                            selected = selectedFilter == filter,
                            onClick = { selectedFilter = filter },
                            shape = RoundedCornerShape(12.dp),
                            label = { Text(filter, fontSize = 12.sp) }
                        )
                    }
                }
                if (selectedDateString != null) {
                    TextButton(onClick = { selectedDateString = null }) {
                        Text(
                            "Show all",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // 3. Release Items Header
        item {
            val titleText = if (selectedDateString != null) {
                "Releases on ${formatReleaseDay(selectedDateString)}"
            } else {
                "All Releases for ${monthFormat.format(selectedYearMonth.time)}"
            }
            SectionHeader(
                title = titleText,
                accent = AppColors.Gold,
                horizontalPadding = 0.dp
            )
        }


        // 4. Release Cards
        if (displayedEvents.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (selectedDateString != null) "Nothing scheduled on this day"
                               else "No releases scheduled for ${monthFormat.format(selectedYearMonth.time)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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

    ExpressiveCard(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f),
        tonalElevation = 1.dp,
        pressedScale = 0.985f,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Poster Artwork Thumbnail
            Box(
                modifier = Modifier
                    .width(58.dp)
                    .height(84.dp)
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

                Spacer(modifier = Modifier.height(3.dp))

                // Was "Airs on 2026-10-12" — a raw ISO value that reads like a database
                // dump. Now "Today" / "Tomorrow" / "Wed 15 Oct".
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Schedule,
                        contentDescription = null,
                        tint = AppColors.Gold.copy(alpha = 0.9f),
                        modifier = Modifier.size(11.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = formatReleaseDay(event.dateString),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.Gold
                    )
                    if (event.isEpisode && event.episodeCount > 1) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "• ${event.episodeCount} episodes",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
