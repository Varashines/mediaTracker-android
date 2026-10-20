package com.vara.mediatracker.ui.m3e

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.vara.mediatracker.ui.theme.LocalSemanticColors

/**
 * The one design vocabulary for this app.
 *
 * ## The rule
 *
 * Screens are built from Material 3 Expressive components plus the Material theme subsystems
 * (colour scheme, shape scale, type scale, motion scheme) — nothing else. In particular:
 *
 *  * no Material 2 widgets;
 *  * no raw colour, shape, type or spacing literals outside this package. If a screen needs a
 *    value, it belongs here, not inline;
 *  * composition is expected — a poster grid, calendar or spotlight has no Material component and
 *    is built *from* Material primitives. Composing is fine; inventing a parallel style is not.
 *
 * ## Selective expression
 *
 * Material's own guidance is that expressive shape, colour, motion and type are for the moments
 * that matter, used sparingly — "not using too many expressive tactics at the same time" — with
 * one or two hero moments in a product, not one per screen. So a screen should read as calm
 * Material with a deliberate accent, never as everything turned up.
 *
 * ## The logic boundary
 *
 * A UI rebuild may not touch `data/`, `util/`, `di/`, `MediaTrackerApp`, `SyncScheduler` or the
 * workers. Behaviour is preserved by construction that way, and the unit tests keep guarding it.
 *
 * ## Sanctioned exceptions
 *
 * These have no Material component and stay as composition, built from Material primitives:
 * poster cards and the library grid, the Home spotlight pager, the month calendar grid, the live
 * countdown, the wordmark, the skeleton shimmer, the two-action swipe row (Material's
 * `SwipeToDismissBox` only dismisses), the floating nav's haptic behaviour, the toast host, and
 * the monospace metadata style plus the asymmetric "tension" shapes — both deliberate accents
 * that Material's shape tactic explicitly endorses.
 */

/**
 * The page rhythm.
 *
 * One scale, used everywhere, so "spacious" is a property of the app rather than of whoever wrote
 * the screen last. `page` is the horizontal margin; the reference density is one short card per
 * row with generous air around it.
 */
object M3ESpacing {
    /** Horizontal margin from the screen edge. */
    val page = 24.dp

    /** Vertical space above the first element and below the last. */
    val pageVertical = 24.dp

    /**
     * Fallback trailing space at the end of a scroll, for the frame before the shell has measured
     * the real one — see [LocalNavClearance].
     *
     * The bar sits above the system bar, so the room a list must leave depends on that bar's height,
     * which is not knowable from here. This is a gesture-navigation estimate and nothing should rely
     * on it.
     */
    val bottomInset = 112.dp

    /** Space between two cards in a list. */
    val cardGap = 16.dp

    /** Space between two labelled sections. */
    val sectionGap = 32.dp

    /** Space between rows inside one card. */
    val rowGap = 12.dp

    /** Space between a leading icon and its text. */
    val inlineGap = 12.dp

    /** Space between small adjacent things: chips, badges, icon buttons. */
    val tightGap = 8.dp

    /** Minimum height of a tappable row. */
    val rowMinHeight = 56.dp

    /** The air the floating bar keeps above the system bar. Part of [LocalNavClearance]. */
    val navGap = 24.dp
}

/**
 * The room a scrolling list must leave at its foot, so the floating navigation bar does not sit on
 * the last row.
 *
 * A `CompositionLocal` rather than a constant, because the answer is not constant. The bar is
 * `navigationBarsPadding()` plus its own height plus a gap, and the inset underneath is what varies:
 * roughly 24dp under gesture navigation, roughly 48dp with three buttons. A fixed number is right on
 * one of them and leaves the last row under the bar on the other — which is the bug this replaced.
 *
 * The shell measures the bar and provides the result. [M3ESpacing.bottomInset] stands in for the
 * first frame, before there is anything to measure.
 */
val LocalNavClearance = compositionLocalOf { M3ESpacing.bottomInset }

/**
 * Where the poster that opened the current detail was, so the detail's poster can start there and
 * grow into place.
 *
 * Carries the title's id as well as its rectangle, because the rectangle is only meaningful for the
 * title it came from. Details are also opened from places with no poster — a related title, a search
 * result — and without the id those would animate in from wherever the last poster happened to be,
 * which is worse than not animating at all.
 *
 * The alternative is a shared-element transition, which does this properly and needs the outgoing
 * screen to stay composed through the animation. The shell composes one screen at a time on purpose,
 * so this carries the geometry across the gap instead of the view itself.
 */
class HeroOrigin {
    var itemId: String? = null
    var rect: Rect? = null

    fun record(id: String, bounds: Rect) {
        itemId = id
        rect = bounds
    }
}

val LocalHeroOrigin = compositionLocalOf { HeroOrigin() }

/**
 * How long a detail takes to arrive: the sheet rising, and the poster travelling into it.
 *
 * One number for both, because they are one motion. Tuned apart they read as two things happening at
 * once — which is exactly what a 1000ms poster against a 180ms screen fade looked like.
 */
const val M3E_DETAIL_ARRIVAL_MILLIS = 200

/**
 * Type roles, read through the theme so a single change to `AppTypography` moves the whole app.
 *
 * Exposed as functions because a `@Composable` getter cannot be a plain `val` here.
 */
object M3EType {
    /** A screen's own title: the editorial heading at the top of a tab. */
    @Composable
    fun screenTitle(): TextStyle = MaterialTheme.typography.displayLargeEmphasized

    /** A section heading inside a screen: "In Progress", "Up Next For You". */
    @Composable
    fun sectionTitle(): TextStyle = MaterialTheme.typography.headlineSmallEmphasized

    /** The primary line of a row or card. */
    @Composable
    fun rowTitle(): TextStyle = MaterialTheme.typography.bodyLarge

    /** The supporting line of a row or card. */
    @Composable
    fun rowSupporting(): TextStyle = MaterialTheme.typography.bodyMedium

    /** A large number: a stat, a count, a runtime. */
    @Composable
    fun metric(): TextStyle = MaterialTheme.typography.headlineMediumEmphasized

    /** Button and chip labels. */
    @Composable
    fun action(): TextStyle = MaterialTheme.typography.labelLargeEmphasized
}

/**
 * Shape roles.
 *
 * Cards and containers take the theme's scale rather than a literal, so the shape token that
 * Material derives its component shapes from is the same one the screens use.
 */
object M3EShape {
    /** A standard card or list container. */
    @Composable
    fun card() = MaterialTheme.shapes.large

    /** A hero or feature container. */
    @Composable
    fun hero() = MaterialTheme.shapes.extraLarge

    /** A chip, pill or small badge. */
    @Composable
    fun chip() = MaterialTheme.shapes.small

    /** A sheet or edge-attached surface — round on top. */
    @Composable
    fun sheet() = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
}

/**
 * Colour roles.
 *
 * Named for the job rather than the palette slot, so emphasis is a decision the design system
 * makes once and screens inherit. Accent and surface tones come from the theme; the semantic
 * tones come from the app's semantic palette.
 */
object M3EColor {
    /** The page behind everything. */
    @Composable
    fun page() = MaterialTheme.colorScheme.background

    /** A card sitting on the page — tonal, not outlined. */
    @Composable
    fun card() = MaterialTheme.colorScheme.surfaceContainerHigh

    /** The emphasised container: the one thing on a screen that should be noticed. */
    @Composable
    fun emphasis() = MaterialTheme.colorScheme.primaryContainer

    /** Content on the emphasised container. */
    @Composable
    fun onEmphasis() = MaterialTheme.colorScheme.onPrimaryContainer

    /** Primary text. */
    @Composable
    fun text() = MaterialTheme.colorScheme.onSurface

    /** Supporting text and inactive icons. */
    @Composable
    fun textMuted() = MaterialTheme.colorScheme.onSurfaceVariant

    /** The app's accent, for eyebrows and small labels sitting on the page background. */
    @Composable
    fun accent() = MaterialTheme.colorScheme.primary

    /** Attention without alarm. */
    @Composable
    fun warning() = LocalSemanticColors.current.warning

    /** A completed or positive value. */
    @Composable
    fun positive() = LocalSemanticColors.current.success
}
