package com.vara.mediatracker.ui.m3e

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import coil3.compose.AsyncImage
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.vara.mediatracker.ui.theme.AppTextStyles

/**
 * The components screens are built from, on top of [M3E].
 *
 * Each one *is* a Material component — these wrappers exist so the vocabulary is applied
 * consistently (a screen header always looks the same, a card is always tonal, a section is
 * always spaced the same way) rather than to introduce a look of their own. If a wrapper starts
 * carrying its own styling, it belongs in Material instead.
 */

/**
 * A tab's header: a small monospace eyebrow over the editorial title, with optional actions.
 *
 * The eyebrow-over-display-title pair is the treatment M3 Expressive uses for screen headers, and
 * the monospace face is this app's deliberate accent.
 */
@Composable
fun M3EScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    /**
     * A smaller title that stays on one line.
     *
     * For a pushed list, where the title can be a word in another script: at display size a single
     * long word is laid out as two lines, because a script without spaces still offers break
     * opportunities inside a word. Smaller and single-line keeps "हिन्दी" as one word.
     */
    compact: Boolean = false
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (eyebrow != null) {
                Text(
                    text = eyebrow.uppercase(),
                    style = AppTextStyles.MonoLabel,
                    // 10sp monospace: the raw accent is nowhere near AA at this size.
                    color = M3EColor.accentText()
                )
            }
            Text(
                text = title,
                style = if (compact) M3EType.sectionTitle() else M3EType.screenTitle(),
                color = M3EColor.text(),
                maxLines = if (compact) 1 else 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (trailing != null) {
            trailing()
        }
    }
}

/**
 * A section inside a screen: a heading with an optional action, spaced by the page rhythm.
 */
@Composable
fun M3ESectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    /** A mark for the section, as the Mac gives every heading one. */
    icon: ImageVector? = null,
    /** Per section rather than global: a heading's colour is part of what it says. */
    tint: Color = M3EColor.accent(),
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(M3ESpacing.tightGap))
            }
            Text(
                text = title,
                style = M3EType.sectionTitle(),
                color = M3EColor.text()
            )
        }
        if (trailing != null) {
            trailing()
        }
    }
}

/**
 * A card as a tonal plane: no outline, no shadow, shape from the theme scale.
 *
 * Depth comes from the surface tone rather than a stroke, which is how a Material card reads on a
 */
@Composable
fun M3ETonalCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val colors = CardDefaults.cardColors(
        containerColor = M3EColor.card(),
        contentColor = M3EColor.text()
    )
    val shape = M3EShape.card()
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            colors = colors,
            content = { content() }
        )
    } else {
        Card(modifier = modifier, shape = shape, colors = colors, content = { content() })
    }
}

/** Content padding for a card, from the rhythm scale. */
val M3ECardPadding: Modifier
    @Composable get() = Modifier.padding(
        horizontal = M3ESpacing.cardGap,
        vertical = M3ESpacing.inlineGap
    )

/**
 * A poster card that names itself on a long press instead of carrying a permanent caption.
 *
 * A caption under every poster is what the Library looked like before the artwork was worth
 * trusting: it doubles the height of a row, and the title is often the least informative thing on
 * the card — you recognise a title long before you read it. Holding the poster still answers the
 * question, for the cases where recognition is not enough.
 *
 * The label is a popup rather than Material's tooltip. A tooltip is anchored to its content, and
 * anchoring it measured the card in a way a lazy grid cell could not constrain — a cell that should
 * have been a third of the row came out at full width. This wrapper only adds a gesture and, while
 * held, a popup; the card it wraps is measured exactly as it would be on its own.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun M3EArtworkCard(
    title: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    var named by remember { mutableStateOf(false) }
    Box(
        modifier = modifier.combinedClickable(
            onClick = { onClick?.invoke() },
            onLongClick = { named = true }
        )
    ) {
        content()
        if (named) {
            Popup(
                alignment = Alignment.Center,
                onDismissRequest = { named = false },
                properties = PopupProperties(focusable = true)
            ) {
                Surface(
                    onClick = { named = false },
                    shape = M3EShape.chip(),
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    tonalElevation = 3.dp
                ) {
                    Text(
                        text = title,
                        style = M3EType.rowTitle(),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}

/**
 * A poster tile's place on screen, and the tap that hands it to [LocalHeroOrigin].
 *
 * Every tappable poster has to go through this. The first version recorded the origin inside one
 * tile, so the detail's poster grew out of the right place from Home's Recently added rail and out of
 * nowhere from everywhere else — Library's grid, the pushed lists, and every `TitleRow`, which
 * carries Continue watching, Coming soon and Finished. A transition that works from one list and not
 * five others is worse than one that never worked, because it looks like a bug rather than a choice.
 *
 * The bounds are captured per tile, on layout, and only handed over when that tile is the one tapped:
 * recording into shared state on every layout would write on every scroll frame.
 *
 * The modifier goes on the **poster**, not on the card around it. A row card is wide and short, and
 * the detail's poster is 2:3 — scaling one to the other squashed it vertically rather than growing
 * it, which is exactly what it looked like.
 */
/**
 * Button colours that tint rather than fill.
 *
 * A filled button takes the accent at full strength. On a page that already uses the accent for its
 * headings and icons, that makes every action shout at the same volume as the thing it is acting
 * on. A tint is the same colour at low alpha: the button still reads as the accent thing on the
 * row, without competing with the content beside it.
 *
 * A caller that passes its own colour (a destructive action, a warning) gets that colour tinted by
 * the same rule, so nothing reverts to a slab of solid paint.
 */
@Composable
fun tintedButtonColors(tint: Color = M3EColor.accent()): ButtonColors =
    ButtonDefaults.buttonColors(
        containerColor = tint.copy(alpha = 0.16f),
        contentColor = tint,
        disabledContainerColor = tint.copy(alpha = 0.07f),
        disabledContentColor = tint.copy(alpha = 0.38f)
    )

class TileOrigin(val modifier: Modifier, val record: () -> Unit)

@Composable
fun rememberTileOrigin(itemId: String): TileOrigin {
    val origin = LocalHeroOrigin.current
    var bounds by remember { mutableStateOf(Rect.Zero) }
    return TileOrigin(
        modifier = Modifier.onGloballyPositioned { bounds = it.boundsInRoot() },
        record = { origin.record(itemId, bounds) }
    )
}

/**
 * What a surface says when it has nothing to show.
 *
 * Every screen used to write its own version of this — a bare `Text` in some places, a card in
 * others, an eyebrow string in another — so "empty" looked like six different things. One
 * component and one voice: a short line saying what is missing, a second saying what to do about
 * it, and optionally the control that does it.
 *
 * Centred and merged into one accessibility node, so a screen reader hears the sentence rather
 * than three fragments of it.
 */
@Composable
fun EmptyState(
    title: String,
    body: String? = null,
    modifier: Modifier = Modifier,
    /** The way out. Only the surfaces that have one offer it. */
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = M3ESpacing.page, vertical = M3ESpacing.sectionGap),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            style = M3EType.rowTitle(),
            color = M3EColor.text(),
            textAlign = TextAlign.Center
        )
        if (body != null) {
            Spacer(Modifier.height(M3ESpacing.tightGap))
            Text(
                text = body,
                style = M3EType.rowSupporting(),
                color = M3EColor.textMuted(),
                textAlign = TextAlign.Center
            )
        }
        if (action != null) {
            Spacer(Modifier.height(M3ESpacing.cardGap))
            action()
        }
    }
}

/**
 * A poster, rendered the one way this app renders posters.
 *
 * Ten call sites had their own copy of this: `AsyncImage` with a chip clip and the card tone behind
 * it, so a tile that has no artwork yet shows the plate rather than a hole. They differed only in
 * size — which the caller passes — and in whether the image is decorative (inside a collage, where
 * the surrounding row already names the title) or carries the title itself.
 */
@Composable
fun PosterTile(
    posterUrl: String?,
    title: String?,
    modifier: Modifier = Modifier,
    /** True when a parent already names the thing, so the image must not repeat it. */
    decorative: Boolean = false
) {
    AsyncImage(
        model = posterUrl,
        contentDescription = if (decorative) null else title,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .clip(M3EShape.chip())
            .background(M3EColor.card())
    )
}

/**
 * The frame every list screen shares.
 *
 * Nine screens were each opening with the same four moves — status-bar inset, page margin, header,
 * and the clearance the floating nav needs at the bottom — and each closing with the same spacer.
 * Written by hand, that is four chances per screen to get the margin or the clearance slightly
 * wrong, which is exactly what had happened.
 *
 * Only the middle is a screen's own business, so that is the only thing it supplies.
 */
@Composable
fun ScreenScaffold(
    title: String,
    eyebrow: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(M3ESpacing.cardGap),
    content: LazyListScope.() -> Unit
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(M3EColor.page())
            .statusBarsPadding()
            .padding(horizontal = M3ESpacing.page),
        verticalArrangement = verticalArrangement
    ) {
        item {
            Spacer(Modifier.height(M3ESpacing.pageVertical))
            M3EScreenHeader(title = title, eyebrow = eyebrow, trailing = trailing)
            Spacer(Modifier.height(M3ESpacing.cardGap))
        }
        content()
        item { Spacer(Modifier.height(LocalNavClearance.current)) }
    }
}

/**
 * The grid twin of [ScreenScaffold].
 *
 * Library and Collections are two-column grids rather than lists, so they cannot use the list
 * scaffold without lying about what they are — but they were repeating the same four moves around
 * the grid. The header, the inset, the margin and the nav clearance are stated once here too.
 *
 * `columns` is free because the two screens genuinely differ: the library is three posters across,
 * collections two, and that is a design decision rather than a constant.
 */
@Composable
fun GridScaffold(
    title: String,
    eyebrow: String? = null,
    columns: Int = 2,
    modifier: Modifier = Modifier,
    /** False when the grid is embedded somewhere that supplies its own heading, as the Hub does. */
    header: Boolean = true,
    trailing: @Composable (() -> Unit)? = null,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(M3ESpacing.cardGap),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(M3ESpacing.cardGap),
    content: LazyGridScope.() -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = modifier
            .fillMaxSize()
            .background(M3EColor.page())
            .statusBarsPadding()
            .padding(horizontal = M3ESpacing.page),
        horizontalArrangement = horizontalArrangement,
        verticalArrangement = verticalArrangement
    ) {
        if (header) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Spacer(Modifier.height(M3ESpacing.pageVertical))
                M3EScreenHeader(title = title, eyebrow = eyebrow, trailing = trailing)
                Spacer(Modifier.height(M3ESpacing.cardGap))
            }
        }
        content()
        item(span = { GridItemSpan(maxLineSpan) }) {
            Spacer(Modifier.height(LocalNavClearance.current))
        }
    }
}
