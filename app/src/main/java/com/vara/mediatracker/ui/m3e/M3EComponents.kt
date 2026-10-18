package com.vara.mediatracker.ui.m3e

import androidx.compose.foundation.ExperimentalFoundationApi
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Surface
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.text.style.TextOverflow
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
                    color = M3EColor.accent()
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
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = M3EType.sectionTitle(),
            color = M3EColor.text()
        )
        if (trailing != null) {
            trailing()
        }
    }
}

/**
 * A card as a tonal plane: no outline, no shadow, shape from the theme scale.
 *
 * Depth comes from the surface tone rather than a stroke, which is how a Material card reads on a
 * Material background. Long-press callers keep using [com.vara.mediatracker.ui.common.FlatCard]
 * until they migrate.
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
 * Blurs whatever is behind a dialog, where the platform can.
 *
 * A dialog is a window, so this is the OS doing the work — and because the backdrop behind a modal
 * never moves, it is blurred once rather than per frame. That is the whole reason it belongs here
 * and not on the nav: a bar with content scrolling under it would re-blur every frame, and this app
 * is mostly scrolling.
 *
 * API 31 — Android 12 — and up. Below that the attribute does not exist and nothing happens, so the
 * dialogs have to look finished without it. They do: the scrim still carries them.
 */
@Composable
fun M3EGlassDialog(radius: Int = 24) {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window ?: return
    DisposableEffect(window) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.setBackgroundBlurRadius(radius)
            // The default scrim is heavy enough to hide the blur entirely, which leaves a darker
            // rectangle for no visible gain. Blur takes over part of what the dim was doing.
            window.setDimAmount(0.32f)
        }
        onDispose { }
    }
}
