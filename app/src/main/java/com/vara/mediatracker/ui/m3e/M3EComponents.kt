package com.vara.mediatracker.ui.m3e

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    trailing: @Composable (() -> Unit)? = null
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
                style = M3EType.screenTitle(),
                color = M3EColor.text()
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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun M3EArtworkCard(
    title: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val tooltipState = rememberTooltipState()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(title, style = M3EType.rowSupporting()) } },
        state = tooltipState,
        modifier = modifier
    ) {
        Box(
            modifier = if (onClick != null) {
                Modifier.clickable(onClick = onClick)
            } else {
                Modifier
            }
        ) {
            content()
        }
    }
}
