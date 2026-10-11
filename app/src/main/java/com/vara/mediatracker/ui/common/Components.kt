package com.vara.mediatracker.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vara.mediatracker.ui.theme.LocalMotion

/**
 * Tappable surface with press feedback built in.
 *
 * Material's default is a ripple alone, which is easy to miss on a poster grid where
 * several cards can be touched in quick succession. This layers a subtle scale on top
 * so a touch registers instantly and springs back. The scale is applied through
 * `graphicsLayer`, so it never triggers relayout or remeasure of the parent.
 */
@Composable
fun ExpressiveCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(18.dp),
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    border: BorderStroke? = null,
    tonalElevation: Dp = 0.dp,
    pressedScale: Float = 0.97f,
    content: @Composable () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val motion = LocalMotion.current
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = motion.snappy,
        label = "cardScale"
    )

    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
        border = border,
        tonalElevation = tonalElevation,
        modifier = modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
    ) {
        content()
    }
}

data class NavDestination(
    val label: String,
    val icon: ImageVector
)

val AppDestinations = listOf(
    NavDestination("Home", Icons.Filled.Home),
    NavDestination("Calendar", Icons.Filled.CalendarMonth),
    NavDestination("Library", Icons.Filled.VideoLibrary),
    NavDestination("Settings", Icons.Filled.Settings)
)

private val NavItemWidth = 84.dp

/**
 * Floating navigation bar with an animated selection pill.
 *
 * The selected item is marked by a pill that fades and scales in rather than the
 * highlight snapping between slots, and the active label fades alongside it. The pill
 * sits behind the icons in a parallel row so it never shifts the content when the
 * label appears — otherwise the bar would jitter on every tap.
 */
@Composable
fun ExpressiveNavigationBar(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val motion = LocalMotion.current

    Surface(
        shape = RoundedCornerShape(28.dp),
        // Effectively opaque: at 0.95 poster artwork still bled through the bar and
        // collided with the labels.
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 10.dp,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        ),
        modifier = modifier
    ) {
        Box {
            // Indicator layer, drawn first so icons render above it.
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                AppDestinations.indices.forEach { index ->
                    val selected = index == selectedIndex
                    // Named distinctly: inside graphicsLayer { } a bare `alpha` would
                    // resolve to the layer's own property, not the animated value.
                    val pillAlpha by animateFloatAsState(
                        targetValue = if (selected) 1f else 0f,
                        animationSpec = motion.gentle,
                        label = "pillAlpha"
                    )
                    val pillScale by animateFloatAsState(
                        targetValue = if (selected) 1f else 0.82f,
                        animationSpec = motion.gentle,
                        label = "pillScale"
                    )
                    Box(
                        modifier = Modifier
                            .width(NavItemWidth)
                            .height(44.dp)
                            .graphicsLayer {
                                alpha = pillAlpha
                                scaleX = pillScale
                                scaleY = pillScale
                            }
                            .clip(RoundedCornerShape(22.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.20f))
                    )
                }
            }

            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                AppDestinations.forEachIndexed { index, destination ->
                    val selected = index == selectedIndex
                    val iconScale by animateFloatAsState(
                        targetValue = if (selected) 1.1f else 1f,
                        animationSpec = motion.snappy,
                        label = "navIconScale"
                    )
                    Surface(
                        onClick = { onSelect(index) },
                        shape = RoundedCornerShape(22.dp),
                        color = Color.Transparent,
                        modifier = Modifier
                            .height(44.dp)
                            .width(NavItemWidth)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.graphicsLayer {
                                scaleX = iconScale
                                scaleY = iconScale
                            }
                        ) {
                            Icon(
                                imageVector = destination.icon,
                                contentDescription = destination.label,
                                tint = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(21.dp)
                            )
                            AnimatedVisibility(
                                visible = selected,
                                enter = fadeIn(animationSpec = tween(motion.STANDARD)),
                                exit = fadeOut(animationSpec = tween(motion.FAST))
                            ) {
                                Row {
                                    Spacer(Modifier.width(7.dp))
                                    Text(
                                        text = destination.label,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Empty-state block.
 *
 * The old empty states were a bare icon and two lines of grey text with a lot of dead
 * space. This centres the message properly and gives it a tinted container so it
 * reads as a deliberate state rather than a failed load.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                    modifier = Modifier.size(36.dp)
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}