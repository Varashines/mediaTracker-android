package com.vara.mediatracker.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vara.mediatracker.ui.theme.AppTextStyles
import com.vara.mediatracker.ui.theme.ExpressiveShapes
import java.util.Locale

/**
 * Section header matching the M3E prototype: a small accent bar, a bold title, and an
 * optional trailing action ("View All (12)").
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(16.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accentColor)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (actionLabel != null && onAction != null) {
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
    }
}

/** Mono micro-label for metadata (network, counts, dates). */
@Composable
fun MonoLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Text(
        text = text.uppercase(Locale.US),
        style = AppTextStyles.MonoLabel,
        color = color,
        modifier = modifier,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/** Semantic tones for [StatusChip]. */
enum class ChipTone { Neutral, Positive, Warning, Accent, Muted }

/**
 * Small rounded status tag, e.g. Watched / Upcoming / Locked, or an item's state.
 */
@Composable
fun StatusChip(
    label: String,
    tone: ChipTone,
    modifier: Modifier = Modifier
) {
    val (fg, bg) = when (tone) {
        ChipTone.Positive -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        ChipTone.Warning -> Color(0xFFFFB300) to Color(0xFFFFB300).copy(alpha = 0.16f)
        ChipTone.Accent -> MaterialTheme.colorScheme.onPrimaryContainer to MaterialTheme.colorScheme.primaryContainer
        ChipTone.Muted -> MaterialTheme.colorScheme.onSurfaceVariant to MaterialTheme.colorScheme.surfaceContainerHighest
        ChipTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant to MaterialTheme.colorScheme.surfaceContainerHigh
    }
    Surface(shape = RoundedCornerShape(8.dp), color = bg, modifier = modifier) {
        Text(
            text = label.uppercase(Locale.US),
            style = AppTextStyles.MonoLabel,
            color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            maxLines = 1
        )
    }
}

/** Thin expressive progress bar with an optional mono fraction label. */
@Composable
fun ProgressLine(
    fraction: Float,
    modifier: Modifier = Modifier,
    label: String? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        if (label != null) {
            Text(
                text = label,
                style = AppTextStyles.MonoValue,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Colored square tile carrying a network / studio short name (prototype's release rows). */
@Composable
fun NetworkBadgeTile(
    name: String,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = tint.copy(alpha = 0.18f),
        modifier = modifier.size(40.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = name.take(4).uppercase(Locale.US),
                style = AppTextStyles.MonoLabel,
                color = tint,
                maxLines = 1
            )
        }
    }
}

/**
 * Flat M3E card: `surfaceContainerHigh` with a hairline outline, no elevation. The
 * prototype's cards read as tinted planes rather than floating shadows.
 */
@Composable
fun FlatCard(
    modifier: Modifier = Modifier,
    shape: Shape = ExpressiveShapes.Card,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    if (onClick != null) {
        Surface(onClick = onClick, shape = shape, color = containerColor, border = border, modifier = modifier) {
            content()
        }
    } else {
        Surface(shape = shape, color = containerColor, border = border, modifier = modifier) {
            content()
        }
    }
}
