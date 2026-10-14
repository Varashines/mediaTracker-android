package com.vara.mediatracker.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.clickable
import androidx.compose.material3.ripple
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
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

/**
 * Tactile spring click feedback modifier matching .m3e-spring-tap from the web showcase.
 * Scales down to pressedScale on tap and bounces back using AppMotion.snappy.
 */
@Composable
fun Modifier.springClickable(
    enabled: Boolean = true,
    pressedScale: Float = 0.95f,
    onClick: () -> Unit
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val motion = LocalMotion.current
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = motion.snappy,
        label = "springClickScale"
    )

    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interaction,
            indication = ripple(bounded = true),
            enabled = enabled,
            onClick = onClick
        )
}

/**
 * Connected M3 Expressive Segmented Pill Control.
 * Renders a pill container with animated active squircle selection and tactile taps.
 */
@Composable
fun <T> ExpressiveSegmentedControl(
    items: List<T>,
    selectedItem: T,
    onItemSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    labelProvider: @Composable (T) -> String,
    iconProvider: (@Composable (T) -> ImageVector?)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    activeContentColor: Color = MaterialTheme.colorScheme.onPrimary,
    inactiveContentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = containerColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .padding(4.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { item ->
                val isSelected = item == selectedItem
                val motion = LocalMotion.current
                val animScale by animateFloatAsState(
                    targetValue = if (isSelected) 1f else 0.98f,
                    animationSpec = motion.snappy,
                    label = "pillScale"
                )

                Surface(
                    onClick = { onItemSelected(item) },
                    shape = RoundedCornerShape(14.dp),
                    color = if (isSelected) activeColor else Color.Transparent,
                    contentColor = if (isSelected) activeContentColor else inactiveContentColor,
                    modifier = Modifier
                        .weight(1f)
                        .graphicsLayer {
                            scaleX = animScale
                            scaleY = animScale
                        }
                ) {
                    Row(
                        modifier = Modifier
                            .padding(vertical = 12.dp, horizontal = 6.dp)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val icon = iconProvider?.invoke(item)
                        if (icon != null) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = if (isSelected) activeContentColor else inactiveContentColor
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                        }
                        Text(
                            text = labelProvider(item),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                            color = if (isSelected) activeContentColor else inactiveContentColor
                        )
                    }
                }
            }
        }
    }
}

/**
 * M3 Expressive connected toggle group, built on the real `ToggleButton` +
 * `ButtonGroupDefaults` connected shapes (shape-morph selection, springy press). Used for
 * the detail status picker and the Library's group-by / sort.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun <T> ExpressiveToggleGroup(
    items: List<T>,
    selectedItem: T,
    onItemSelected: (T) -> Unit,
    labelProvider: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    iconProvider: (@Composable (T) -> ImageVector?)? = null
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items.forEach { item ->
            ToggleButton(
                checked = item == selectedItem,
                onCheckedChange = { onItemSelected(item) },
                shapes = ButtonGroupDefaults.connectedMiddleButtonShapes(),
                modifier = Modifier.semantics { role = Role.RadioButton }
            ) {
                val icon = iconProvider?.invoke(item)
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = labelProvider(item),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

data class NavDestination(
    val label: String,
    val icon: ImageVector
)
/**
 * Stroked rounded rectangle, written out by corner so the path stays a single
 * closed contour the way the launcher icon draws it.
 */
private fun PathBuilder.strokedRoundedRect(
    left: Float, top: Float, right: Float, bottom: Float, radius: Float
) {
    moveTo(left + radius, top)
    lineTo(right - radius, top)
    arcTo(radius, radius, 0f, isMoreThanHalf = false, isPositiveArc = true, right, top + radius)
    lineTo(right, bottom - radius)
    arcTo(radius, radius, 0f, isMoreThanHalf = false, isPositiveArc = true, right - radius, bottom)
    lineTo(left + radius, bottom)
    arcTo(radius, radius, 0f, isMoreThanHalf = false, isPositiveArc = true, left, bottom - radius)
    lineTo(left, top + radius)
    arcTo(radius, radius, 0f, isMoreThanHalf = false, isPositiveArc = true, left + radius, top)
    close()
}

/**
 * The app mark as a vector, ported from generate_icon.swift.
 *
 * generate_icon.swift draws on a 1024pt canvas with the origin at the bottom
 * left, so each coordinate is divided by 1024/24 and its y flipped. The three
 * shapes it lays down are a stroked player card, a filled play triangle, and a
 * smaller stroked rule under the card; all three are kept here.
 *
 * They were then scaled 2x about the icon centre. In the launcher the mark sits
 * inside a rounded square with a wide margin around it, which at 23dp leaves it
 * occupying about a third of the space it needs, so the nav bar would have shown
 * a speck. Stroke widths scale with the content rather than staying fixed, which
 * is what keeps the card from vanishing at this size.
 *
 * Only the mark is used, not the gradient plate behind it. The nav bar is
 * icon-only and already draws an active indicator behind whatever icon it holds;
 * a second filled shape underneath would fight it.
 */
val MediaTrackerMark: ImageVector = ImageVector.Builder(
    name = "MediaTracker",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).apply {
    val ink = SolidColor(Color.White)

    path(
        name = "player",
        fill = null,
        stroke = ink,
        strokeAlpha = 1f,
        strokeLineWidth = 1.4f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round
    ) {
        strokedRoundedRect(2.60f, 1.61f, 21.40f, 14.55f, 1.88f)
    }

    path(
        name = "rule",
        fill = null,
        stroke = ink,
        strokeAlpha = 0.85f,
        strokeLineWidth = 1.4f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round
    ) {
        strokedRoundedRect(2.60f, 17.09f, 16.68f, 22.71f, 0.94f)
    }

    path(
        name = "play",
        fill = ink,
        stroke = null,
        fillAlpha = 0.9f
    ) {
        moveTo(10.50f, 10.54f)
        lineTo(10.50f, 5.83f)
        lineTo(14.25f, 8.15f)
        close()
    }
}.build()

/**
 * Bottom navigation destinations.
 *
 * Home / Hub / Radar / Library / Settings. The Hub hosts the macOS smart categories
 * (Release Radar, Premiere Radar, Catch Up, Loved, Binge, Quick Bites, Shelved,
 * Released This Week) and the Discovery Hub behind one tab, so it reads as a single
 * place you browse rather than a "Smart" mode. Radar stays a top-level view of
 * upcoming releases because it is time-sensitive rather than a place you browse.
 */
val AppDestinations = listOf(
    NavDestination("Home", Icons.Default.Home),
    NavDestination("Hub", Icons.Default.Widgets),
    NavDestination("Radar", Icons.Default.CalendarMonth),
    NavDestination("Library", Icons.Default.VideoLibrary),
    NavDestination("Settings", Icons.Default.Settings)
)

/**
 * Floating Navigation Bar with M3E Active Squircle Pill Indicator and labels,
 * matching the HTML prototype layout.
 */
@Composable
fun ExpressiveNavigationBar(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = rememberHaptics()
    val motion = LocalMotion.current
    Surface(
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.95f),
        tonalElevation = 4.dp,
        shadowElevation = 16.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.40f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppDestinations.forEachIndexed { index, destination ->
                val selected = index == selectedIndex

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .springClickable(
                            pressedScale = 0.90f,
                            onClick = {
                                haptics.tap()
                                onSelect(index)
                            }
                        )
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val pillColor by animateColorAsState(
                        targetValue = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        animationSpec = tween(motion.FAST),
                        label = "navPill"
                    )
                    val iconColor by animateColorAsState(
                        targetValue = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = tween(motion.FAST),
                        label = "navIcon"
                    )
                    Box(
                        modifier = Modifier
                            .width(44.dp)
                            .height(26.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(pillColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = destination.icon,
                            contentDescription = destination.label,
                            tint = iconColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = destination.label,
                        fontSize = 10.sp,
                        maxLines = 1,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * "View All (n)" section action. Boxed rather than a bare clickable Text so the tap
 * target clears the 48dp minimum; the previous 2dp-padded text was roughly a third
 * of that and easy to miss.
 */
@Composable
fun SectionViewAllLink(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
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
/**
 * Horizontal rail that snaps between items.
 *
 * A plain LazyRow stops wherever the finger lifts, which on a poster rail leaves one
 * card half off-screen. Snapping means every card is either fully visible or fully
 * out of the way.
 */
@Composable
fun SnapRail(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
    itemSpacing: Dp = 12.dp,
    content: LazyListScope.() -> Unit
) {
    val listState = rememberLazyListState()
    LazyRow(
        state = listState,
        modifier = modifier,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(itemSpacing),
        flingBehavior = rememberSnapFlingBehavior(lazyListState = listState),
        content = content
    )
}
