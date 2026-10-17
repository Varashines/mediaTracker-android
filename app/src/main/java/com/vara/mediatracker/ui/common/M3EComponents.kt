package com.vara.mediatracker.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ripple
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
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vara.mediatracker.ui.theme.AppTextStyles
import com.vara.mediatracker.ui.theme.ExpressiveShapes
import com.vara.mediatracker.ui.theme.LocalMotion
import com.vara.mediatracker.ui.theme.LocalSemanticColors
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
                .width(6.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(accentColor)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = title.uppercase(Locale.US),
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.8.sp,
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
        ChipTone.Warning -> LocalSemanticColors.current.warning to LocalSemanticColors.current.warning.copy(alpha = 0.16f)
        ChipTone.Accent -> MaterialTheme.colorScheme.onPrimaryContainer to MaterialTheme.colorScheme.primaryContainer
        ChipTone.Muted -> MaterialTheme.colorScheme.onSurfaceVariant to MaterialTheme.colorScheme.surfaceContainerHighest
        ChipTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant to MaterialTheme.colorScheme.surfaceContainerHigh
    }
    Surface(shape = MaterialTheme.shapes.small, color = bg, modifier = modifier) {
        Text(
            text = label.uppercase(Locale.US),
            style = AppTextStyles.MonoLabel,
            color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            maxLines = 1
        )
    }
}

/** Expressive wavy progress bar with an optional mono fraction label. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
        LinearWavyProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
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
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FlatCard(
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    // Default comes from the theme rather than a literal, so the shape scale is the single source.
    val resolvedShape = shape ?: MaterialTheme.shapes.large
    if (onClick != null) {
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val motion = LocalMotion.current
        val scale by animateFloatAsState(
            targetValue = if (pressed) 0.97f else 1f,
            animationSpec = motion.snappy,
            label = "flatCardScale"
        )
        Surface(
            shape = resolvedShape,
            color = containerColor,
                        modifier = modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .combinedClickable(
                    interactionSource = interaction,
                    indication = ripple(),
                    onClick = onClick,
                    onLongClick = onLongClick
                )
        ) {
            content()
        }
    } else {
        Surface(shape = resolvedShape, color = containerColor, modifier = modifier) {
            content()
        }
    }
}

/**
 * Translucent top-bar background so scrolled content stays faintly visible behind it —
 * readable, not opaque. Approximates the prototype's `backdrop-blur` with a scrim; a
 * true backdrop blur needs a haze layer.
 */
@Composable
fun Modifier.topBarScrim(): Modifier =
    this.background(MaterialTheme.colorScheme.background.copy(alpha = 0.86f))
