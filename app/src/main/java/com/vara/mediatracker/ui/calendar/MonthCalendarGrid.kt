package com.vara.mediatracker.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vara.mediatracker.ui.common.springClickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Month grid with one consistent cell geometry.
 *
 * The header and the day rows previously used different arrangements and different
 * fixed widths (SpaceAround at 36dp vs SpaceBetween at 38dp), so the weekday letters
 * sat visibly off from the columns beneath them.
 *
 * Event density is encoded in the marker rather than just its presence. Nearly every
 * day in an actively-watched library has *something* airing, so a single dot on 28 of
 * 31 days carries no information; scaling and fading by count makes a busy day read
 * as busy instead of making every day look identical.
 */
@Composable
fun MonthCalendarGrid(
    month: Calendar,
    todayString: String,
    eventsByDate: Map<String, List<CalendarReleaseEvent>>,
    selectedDate: String?,
    onSelectDate: (String?) -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    modifier: Modifier = Modifier
) {
    val monthFormat = remember { SimpleDateFormat("MMMM yyyy", Locale.getDefault()) }
    val dayFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }

    val days = remember(month) { buildMonthDays(month, dayFormat) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPreviousMonth) {
                Icon(
                    Icons.Filled.KeyboardArrowLeft,
                    contentDescription = "Previous month",
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(
                text = monthFormat.format(month.time),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = onNextMonth) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForwardIos,
                    contentDescription = "Next month",
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // Weekday header uses the same seven equal columns as the day rows.
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach { label ->
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }

        Spacer(Modifier.height(2.dp))

        days.chunked(7).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { dateString ->
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        if (dateString == null) {
                            Spacer(Modifier.size(40.dp))
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
    val accent = MaterialTheme.colorScheme.primary

    val isEventDay = eventCount > 0
    val isFilledActive = isSelected || isToday

    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(
                if (isFilledActive) MaterialTheme.colorScheme.primary
                else Color.Transparent
            )
            .then(
                if (!isFilledActive && isEventDay) {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f), CircleShape)
                } else Modifier
            )
            .springClickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = dayNum,
                fontSize = 13.sp,
                fontWeight = if (isFilledActive) FontWeight.ExtraBold else FontWeight.SemiBold,
                color = if (isFilledActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
            )
            if (isEventDay) {
                Spacer(Modifier.height(1.dp))
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(
                            if (isFilledActive) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.primary
                        )
                )
            }
        }
    }
}

/**
 * Leading blanks for the first week so the 1st lands under the right weekday, padded
 * out to whole weeks.
 */
internal fun buildMonthDays(month: Calendar, format: SimpleDateFormat): List<String?> {
    val cal = month.clone() as Calendar
    cal.set(Calendar.DAY_OF_MONTH, 1)
    val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    // Monday-first offset.
    val offset = (cal.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7

    val out = MutableList<String?>(offset) { null }
    for (day in 1..maxDay) {
        cal.set(Calendar.DAY_OF_MONTH, day)
        out.add(format.format(cal.time))
    }
    while (out.size % 7 != 0) out.add(null)
    return out
}
