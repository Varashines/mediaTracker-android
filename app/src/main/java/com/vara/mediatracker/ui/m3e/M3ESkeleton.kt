package com.vara.mediatracker.ui.m3e

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * What a screen shows before its data has arrived.
 *
 * Every library flow starts at an empty value — `LibraryCore()` has no items, `HomeData()` has no
 * rails — so the first frames rendered the *finished* empty state: "0 titles", "Your library is up
 * to date", "Nothing to explore yet". A cold start therefore told the user their library was empty
 * before Room had answered.
 *
 * The design brief lists a skeleton shimmer among its sanctioned exceptions, so this is built from
 * Material primitives rather than a Material component. It is deliberately shape-only: no labels,
 * because a skeleton that says something is just a slower wrong answer.
 */
@Composable
fun M3ESkeleton(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900), RepeatMode.Reverse),
        label = "skeletonAlpha"
    )
    // `card()` and `textMuted()` are the page's own greys, so the shimmer belongs to the theme
    // rather than looking like a Material default dropped into it.
    val fill = M3EColor.card().copy(alpha = alpha)
    val heading = M3EColor.textMuted().copy(alpha = alpha * 0.5f)

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = M3ESpacing.page, vertical = M3ESpacing.pageVertical)
    ) {
        // The header block: an eyebrow, a title, a rail of pills.
        Box(Modifier.width(72.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(heading))
        Spacer(Modifier.height(M3ESpacing.tightGap))
        Box(Modifier.width(180.dp).height(28.dp).clip(RoundedCornerShape(10.dp)).background(heading))
        Spacer(Modifier.height(M3ESpacing.sectionGap))

        // Three cards at the rhythm the real screens use.
        repeat(3) { index ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(if (index == 0) 132.dp else 96.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(fill)
            )
            Spacer(Modifier.height(M3ESpacing.cardGap))
        }
    }
}
