package com.vara.mediatracker.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
 * Reordered away from Home/Calendar/Library/Settings. Calendar is a *view* of upcoming
 * releases rather than a place you live, so it does not deserve a permanent slot; it is
 * reached from the Home header. Search took its place because for a tracker the primary
 * job is adding and finding content, not just looking at what is already there.
 */
val AppDestinations = listOf(
    // The app mark rather than a house: Home is the app itself, so saying so with
    // the brand is honest, and it is the only destination without a semantic icon.
    NavDestination("Home", MediaTrackerMark),
    NavDestination("Search", Icons.Filled.Search),
    NavDestination("Library", Icons.Filled.VideoLibrary),
    NavDestination("Settings", Icons.Filled.Settings)
)

private val NavItemWidth = 78.dp

/**
 * Icon-only bottom navigation with a sliding active indicator.
 *
 * Labels were previously shown for the selected destination only. That is worse than
 * either extreme on a phone: the bar changes width and the whole row reflows on every
 * tap, and the shifting text draws the eye away from the icons it is labelling. Icon-only
 * is now the rule, matching how most media apps do it.
 *
 * Each destination gets an equal slice of the width so the bar reads as evenly divided
 * regardless of icon size, and the indicator is a pill behind the active icon — the
 * current Material 3 pattern — rather than a colour change on the icon itself.
 */
@Composable
fun ExpressiveNavigationBar(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val motion = LocalMotion.current

    Surface(
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 12.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppDestinations.forEachIndexed { index, destination ->
                val selected = index == selectedIndex
                val indicatorAlpha by animateFloatAsState(
                    targetValue = if (selected) 1f else 0f,
                    animationSpec = motion.gentle,
                    label = "navIndicatorAlpha"
                )
                val indicatorScale by animateFloatAsState(
                    targetValue = if (selected) 1f else 0.6f,
                    animationSpec = motion.gentle,
                    label = "navIndicatorScale"
                )
                val iconScale by animateFloatAsState(
                    targetValue = if (selected) 1f else 0.94f,
                    animationSpec = motion.snappy,
                    label = "navIconScale"
                )

                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    // Active indicator sits behind the icon.
                    Box(
                        modifier = Modifier
                            .width(64.dp)
                            .height(32.dp)
                            .graphicsLayer {
                                alpha = indicatorAlpha
                                scaleX = indicatorScale
                                scaleY = indicatorScale
                            }
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.20f))
                    )
                    Box(
                        modifier = Modifier.size(44.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = destination.icon,
                            contentDescription = destination.label,
                            tint = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(23.dp)
                                .graphicsLayer {
                                    scaleX = iconScale
                                    scaleY = iconScale
                                }
                        )
                    }
                    // Full-row tap target; the icon alone is a small target on a phone.
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clip(CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(),
                                onClick = { onSelect(index) }
                            )
                    )
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
/**
 * Collapsing large-title header, the standard mobile pattern.
 *
 * The previous shell spent a full ~120dp showing the single word "Home" and kept that
 * space forever. On a phone that is a fifth of the viewport spent on a label. This
 * starts large and shrinks to a compact bar as the content scrolls, with the bar
 * background fading in only once it would otherwise sit over artwork.
 *
 * Driven by the LazyListState of the screen's own scroll container.
 */
@Composable
fun CollapsingHeader(
    title: String,
    scrollState: LazyListState,
    modifier: Modifier = Modifier,
    actions: @Composable (() -> Unit)? = null
) {
    val motion = LocalMotion.current
    val density = LocalDensity.current
    val collapsePx = with(density) { 64.dp.toPx() }

    // Read inside derivedStateOf so only this composable recomposes while scrolling,
    // rather than the whole screen behind it.
    val fraction by remember(scrollState) {
        derivedStateOf {
            val raw = scrollState.firstVisibleItemIndex * 200f + scrollState.firstVisibleItemScrollOffset
            (raw / collapsePx).coerceIn(0f, 1f)
        }
    }
    val barAlpha by animateFloatAsState(fraction, motion.gentle, label = "headerAlpha")

    // The status-bar inset belongs on the outer Box, once. Both halves used to
    // apply it themselves, so they disagreed about where the content area began,
    // and the 68dp of dead space above the large title came from the big title
    // being vertically centred inside a box that the bar had already sized.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
    ) {
        // Collapsed bar: fades in as the large title fades out.
        Surface(
            color = MaterialTheme.colorScheme.background.copy(alpha = barAlpha * 0.95f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = barAlpha)
                )
                Spacer(Modifier.weight(1f))
                actions?.invoke()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 4.dp, bottom = 8.dp),
            contentAlignment = Alignment.TopStart
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 1f - fraction)
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
