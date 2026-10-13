package com.vara.mediatracker.ui.calendar

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vara.mediatracker.ui.common.springClickable
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Breathable Calendar Grid matching macOS ReleaseCalendarView aesthetic.
 *
 * Supports both full month view and compact single-week view (e.g. when scrolling
 * down or toggled by the user) to keep viewport focused on release items.
 */
@Composable
fun MonthCalendarGrid(
    month: Calendar,
    todayString: String,
    eventsByDate: Map<String, List<CalendarReleaseEvent>>,
    selectedDate: String?,
    onSelectDate: (String?) -> Unit,
    modifier: Modifier = Modifier,
    isCompactWeek: Boolean = false
) {
    val dayFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    val firstDayOfWeek = remember { Calendar.getInstance().firstDayOfWeek }
    val weekdayLabels = remember(firstDayOfWeek) { weekdayLabelsFor(firstDayOfWeek) }
    val days = remember(month, firstDayOfWeek) { buildMonthDays(month, dayFormat, firstDayOfWeek) }

    // In compact week mode, find the 7-day row containing selectedDate or todayString
    val displayWeeks = remember(days, isCompactWeek, selectedDate, todayString) {
        val weeks = days.chunked(7)
        if (!isCompactWeek) {
            weeks
        } else {
            val targetDate = selectedDate ?: todayString
            val matchingWeek = weeks.firstOrNull { week -> week.contains(targetDate) }
            if (matchingWeek != null) listOf(matchingWeek) else weeks.take(1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
    ) {
        // Weekday header with generous breathing room
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp)
        ) {
            weekdayLabels.forEach { label ->
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                    )
                }
            }
        }

        displayWeeks.forEachIndexed { index, week ->
            if (index > 0) {
                Spacer(Modifier.height(8.dp))
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { dateString ->
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        if (dateString == null) {
                            Spacer(Modifier.size(42.dp))
                        } else {
                            DayCell(
                                dateString = dateString,
                                isToday = dateString == todayString,
                                isSelected = dateString == selectedDate,
                                eventCount = eventsByDate[dateString]?.size ?: 0,
                                onClick = { onSelectDate(if (dateString == selectedDate) null else dateString) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    dateString: String,
    isToday: Boolean,
    isSelected: Boolean,
    eventCount: Int,
    onClick: () -> Unit
) {
    val dayNum = dateString.takeLast(2).toIntOrNull()?.toString() ?: ""
    val isEventDay = eventCount > 0
    val isFilledActive = isSelected || isToday

    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                when {
                    isSelected -> MaterialTheme.colorScheme.primary
                    isToday -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                    else -> Color.Transparent
                }
            )
            .then(
                when {
                    isSelected -> Modifier
                    isToday -> Modifier.border(
                        1.5.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                        RoundedCornerShape(14.dp)
                    )
                    isEventDay -> Modifier.border(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                        RoundedCornerShape(14.dp)
                    )
                    else -> Modifier
                }
            )
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append("Day $dayNum")
                    if (isToday) append(", today")
                    if (isEventDay) append(", $eventCount release${if (eventCount == 1) "" else "s"}")
                    if (isSelected) append(", selected")
                }
                role = Role.Button
            }
            .springClickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = dayNum,
                fontSize = 13.sp,
                fontWeight = if (isFilledActive) FontWeight.Black else FontWeight.SemiBold,
                color = when {
                    isSelected -> MaterialTheme.colorScheme.onPrimary
                    isToday -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )
            if (isEventDay) {
                Spacer(Modifier.height(3.dp))
                Box(
                    modifier = Modifier
                        .size(if (eventCount >= 3) 5.dp else 4.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.primary.copy(
                                alpha = if (eventCount >= 3) 1f else 0.75f
                            )
                        )
                )
            } else {
                Spacer(Modifier.height(7.dp))
            }
        }
    }
}

/**
 * Leading blanks for the first week so the 1st lands under the right weekday, padded
 * out to whole weeks. [firstDayOfWeek] follows the device locale rather than assuming
 * Monday, and matches the weekday header rendered above the grid.
 */
internal fun buildMonthDays(
    month: Calendar,
    format: SimpleDateFormat,
    firstDayOfWeek: Int = Calendar.MONDAY
): List<String?> {
    val cal = month.clone() as Calendar
    cal.set(Calendar.DAY_OF_MONTH, 1)
    val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val offset = (cal.get(Calendar.DAY_OF_WEEK) - firstDayOfWeek + 7) % 7

    val out = MutableList<String?>(offset) { null }
    for (day in 1..maxDay) {
        cal.set(Calendar.DAY_OF_MONTH, day)
        out.add(format.format(cal.time))
    }
    while (out.size % 7 != 0) out.add(null)
    return out
}

/** Two-letter weekday headers in locale order, starting from [firstDayOfWeek]. */
private fun weekdayLabelsFor(firstDayOfWeek: Int): List<String> {
    val symbols = DateFormatSymbols.getInstance()
    return (0 until 7).map { offset ->
        val dayConstant = ((firstDayOfWeek - 1 + offset) % 7) + 1
        symbols.shortWeekdays.getOrElse(dayConstant) { "" }.take(2)
    }
}
