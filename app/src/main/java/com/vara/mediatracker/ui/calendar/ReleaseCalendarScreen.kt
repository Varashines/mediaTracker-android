package com.vara.mediatracker.ui.calendar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vara.mediatracker.ui.common.LibraryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.vara.mediatracker.data.badge.BadgeEngine
import com.vara.mediatracker.data.badge.BadgeResult
import com.vara.mediatracker.data.badge.SmartBadge
import com.vara.mediatracker.data.db.AppDatabase
import com.vara.mediatracker.di.AppContainer
import com.vara.mediatracker.data.model.EpisodeEntity
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.data.model.MediaIds
import com.vara.mediatracker.ui.common.parseAirInstant
import com.vara.mediatracker.ui.common.ExpressiveCard
import com.vara.mediatracker.ui.common.formatReleaseDay
import com.vara.mediatracker.ui.common.rememberCountdownText
import com.vara.mediatracker.ui.common.rememberTodayDateString
import com.vara.mediatracker.ui.common.springClickable
import com.vara.mediatracker.ui.home.SectionHeader
import com.vara.mediatracker.ui.home.SmartBadgePill
import com.vara.mediatracker.ui.theme.AppColors
import com.vara.mediatracker.ui.theme.ExpressiveShapes
import com.vara.mediatracker.util.AirTimeResolver
import com.vara.mediatracker.util.NetworkResolver
import com.vara.mediatracker.data.prefs.PreferencesManager
import com.vara.mediatracker.data.sync.ReleaseAlertScheduler
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
    val dateString: String,      // "yyyy-MM-dd" local drop day
    val isEpisode: Boolean,
    val episodeCount: Int = 0,
    val episodeLabels: List<String> = emptyList(),   // ["S2 E4", "S2 E5", ...]
    /** Original US air date of the first episode, for rule resolution. */
    val airDate: String? = null,
    /** Real air time of the first episode that day, from TVMaze, when known. */
    val airTime: String? = null,
    val airstamp: String? = null
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
    onSelectMedia: (String) -> Unit,
    onOpenSearch: (() -> Unit)? = null
) {
    // The library join is produced once, off the UI thread, by the activity-scoped
    // ViewModel (see LibraryViewModel) instead of collecting and grouping the whole
    // episode table here on the main thread.
    val libraryVm: LibraryViewModel = viewModel(factory = LibraryViewModel.factory(container))
    val core by libraryVm.core.collectAsState()
    val allEpisodes = core.allEpisodes
    val episodesByShow = core.episodesByShow
    val smartBadgesMap = core.badges

    val todayString = rememberTodayDateString()

    var selectedYearMonth by remember {
        mutableStateOf(Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
        })
    }

    var selectedDateString by remember { mutableStateOf<String?>(todayString) }
    var selectedFilter by remember { mutableStateOf("All") } // "All", "Movies", "TV Shows"

    // Compute month boundaries
    val monthFormat = remember { SimpleDateFormat("MMMM yyyy", Locale.getDefault()) }

    // Precalculate all events in current month. Building a month walks every item and
    // every episode, so it runs on a background dispatcher.
    val monthEventsMap by produceState(
        initialValue = emptyMap<String, List<CalendarReleaseEvent>>(),
        items, allEpisodes, selectedYearMonth
    ) {
        value = withContext(Dispatchers.Default) {
            val cal = selectedYearMonth.clone() as Calendar
            cal.set(Calendar.DAY_OF_MONTH, 1)
            buildMonthReleaseEvents(
                items = items,
                episodes = allEpisodes,
                year = cal.get(Calendar.YEAR),
                month = cal.get(Calendar.MONTH)
            ).groupBy { it.dateString }
        }
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

    // Track scrolling to shrink calendar to single-week mode when browsing releases.
    val listState = rememberLazyListState()

    // null = follow the scroll position; true/false = the user pinned Week/Month. The
    // override resets when the month changes so scrolling keeps working afterwards.
    var manualWeekMode by remember { mutableStateOf<Boolean?>(null) }
    // Collapse to the week strip only once the month card has genuinely left the
    // viewport. The old check flipped on the first pixel of scroll (index > 0), which
    // made the calendar feel twitchy; this waits until the grid is well out of view.
    val scrolledPastTop by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 2 ||
                (listState.firstVisibleItemIndex == 2 && listState.firstVisibleItemScrollOffset > 220)
        }
    }
    val isCompactWeek = manualWeekMode ?: scrolledPastTop

    fun changeMonth(delta: Int) {
        selectedYearMonth = (selectedYearMonth.clone() as Calendar).apply { add(Calendar.MONTH, delta) }
        selectedDateString = null
        manualWeekMode = null
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 140.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Release Radar Top Header matching HTML
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "TIMELINE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 1.2.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Release Radar",
                        style = MaterialTheme.typography.headlineMedium,
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
        }

        // 2. Month grid Card: month navigation and the Week/Month toggle live together
        // here, next to the grid they control.
        item {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f)
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { changeMonth(-1) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Previous month",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Text(
                                text = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(selectedYearMonth.time),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                            IconButton(
                                onClick = { changeMonth(1) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = "Next month",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        WeekMonthToggle(
                            isWeek = isCompactWeek,
                            onSelect = { week -> manualWeekMode = week }
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    MonthCalendarGrid(
                        month = selectedYearMonth,
                        todayString = todayString,
                        eventsByDate = monthEventsMap,
                        selectedDate = selectedDateString,
                        onSelectDate = { selectedDateString = it },
                        isCompactWeek = isCompactWeek,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // 3. Drawer Header with pink count pill matching HTML
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .width(6.dp)
                            .height(18.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (selectedDateString != null) "RELEASES ON ${formatReleaseDay(selectedDateString).uppercase()}"
                               else "ALL RELEASES FOR ${monthFormat.format(selectedYearMonth.time).uppercase()}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.8.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Text(
                        text = "${displayedEvents.size} ${if (displayedEvents.size == 1) "release" else "releases"}",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
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
        } else if (selectedDateString != null) {
            items(displayedEvents, key = { "${it.mediaItem.id}_${it.dateString}_${it.isEpisode}" }) { event ->
                CalendarReleaseCard(
                    event = event,
                    smartBadge = smartBadgesMap[event.mediaItem.id],
                    onClick = { onSelectMedia(event.mediaItem.id) }
                )
            }
        } else {
            // Whole-month view: group the releases under a day header so a long list
            // reads as a schedule rather than a wall of cards.
            displayedEvents.groupBy { it.dateString }.forEach { (date, dayEvents) ->
                item(key = "day_$date") {
                    ReleaseDayHeader(dateString = date, count = dayEvents.size)
                }
                items(dayEvents, key = { "${it.mediaItem.id}_${it.dateString}_${it.isEpisode}" }) { event ->
                    CalendarReleaseCard(
                        event = event,
                        smartBadge = smartBadgesMap[event.mediaItem.id],
                        onClick = { onSelectMedia(event.mediaItem.id) }
                    )
                }
            }
        }
    }
}

/** Day separator shown between date groups in the whole-month view. */
@Composable
private fun ReleaseDayHeader(dateString: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = formatReleaseDay(dateString),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "$count ${if (count == 1) "release" else "releases"}",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * The Radar's air-time label, shown in the device's local zone with no suffix.
 *
 * Resolves the real instant (airstamp/airtime, else the streaming rule) and formats it
 * locally — so a Marvel drop at 9 PM ET on Oct 13 reads as "6:30 AM" in IST.
 */
private fun resolveEventAirTimeLabel(
    event: CalendarReleaseEvent,
    serviceName: String?,
    timezone: String?
): String? {
    val instant = parseAirInstant(event.airDate ?: event.dateString, event.airTime, event.airstamp, timezone, serviceName)
        ?: return null
    return SimpleDateFormat("h:mm a", Locale.getDefault()).format(instant)
}

/**
 * Compact Week/Month segmented control for the calendar card. Replaces the old
 * "Expand"/"Shrink" pill, which described the action rather than the state and was
 * easy to misread.
 */
@Composable
private fun WeekMonthToggle(isWeek: Boolean, onSelect: (Boolean) -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Row(modifier = Modifier.padding(3.dp)) {
            WeekMonthSegment("Week", selected = isWeek) { onSelect(true) }
            WeekMonthSegment("Month", selected = !isWeek) { onSelect(false) }
        }
    }
}

@Composable
private fun WeekMonthSegment(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(11.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

@Composable
fun CalendarReleaseCard(
    event: CalendarReleaseEvent,
    smartBadge: BadgeResult? = null,
    onClick: () -> Unit
) {
    val item = event.mediaItem
    val context = LocalContext.current
    val prefs = remember(context) { PreferencesManager(context.applicationContext) }
    var isAlertSet by remember(item.id, event.dateString) {
        mutableStateOf(prefs.isReleaseAlertSet(item.id, event.dateString))
    }
    val netInfo = remember(item) { NetworkResolver.resolve(item) }
    val serviceHint = remember(item, netInfo.name) {
        AirTimeResolver.serviceHint(item.network ?: netInfo.name, item.studios, item.genres)
    }
    val airTimeLabel = remember(event.airDate, event.airTime, event.airstamp, serviceHint, item.timezone) {
        resolveEventAirTimeLabel(event, serviceHint, item.timezone)
    }
    val alertInstant = remember(event.airDate, event.airTime, event.airstamp, serviceHint, item.timezone) {
        parseAirInstant(event.airDate ?: event.dateString, event.airTime, event.airstamp, item.timezone, serviceHint)
    }
    val canAlert = alertInstant != null && alertInstant.time > System.currentTimeMillis()

    ExpressiveCard(
        onClick = onClick,
        shape = ExpressiveShapes.Hero,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        tonalElevation = 2.dp,
        pressedScale = 0.985f,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Series Poster Artwork Thumbnail with dynamic network overlay
            Box(
                modifier = Modifier
                    .width(52.dp)
                    .height(68.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            ) {
                val poster = item.posterUrl ?: item.backdropUrl
                if (poster != null) {
                    AsyncImage(
                        model = poster,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(netInfo.badgeColor.copy(alpha = 0.85f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = netInfo.shortName,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            color = netInfo.textColor
                        )
                    }
                }

                // Tiny dynamic network badge at bottom of poster
                Surface(
                    color = Color.Black.copy(alpha = 0.75f),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(3.dp)
                ) {
                    Text(
                        text = netInfo.shortName,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = netInfo.badgeColor.copy(alpha = 0.9f)
                    ) {
                        Text(
                            text = netInfo.name.uppercase(),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = netInfo.textColor,
                            letterSpacing = 0.5.sp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    if (smartBadge != null) {
                        com.vara.mediatracker.ui.home.SmartBadgePill(
                            badge = smartBadge.badge,
                            isSparkle = smartBadge.isSparkle
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = if (airTimeLabel != null) "${event.contextLabel} • $airTimeLabel" else event.contextLabel,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Set Alert Pill Button — only for a release that has not aired yet.
            if (canAlert) {
                Button(
                    onClick = {
                        val next = !isAlertSet
                        isAlertSet = next
                        prefs.setReleaseAlert(item.id, event.dateString, next)
                        if (next) {
                            val subtitle = airTimeLabel?.let { "${event.contextLabel} • $it" }
                                ?: event.contextLabel
                            ReleaseAlertScheduler.schedule(
                                context = context,
                                mediaId = item.id,
                                dateString = event.dateString,
                                title = item.title,
                                subtitle = subtitle,
                                triggerAtMillis = alertInstant!!.time
                            )
                        } else {
                            ReleaseAlertScheduler.cancel(context, item.id, event.dateString)
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isAlertSet) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.primary,
                        contentColor = if (isAlertSet) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (isAlertSet) "Alert Set" else "Set Alert",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
