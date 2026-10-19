package com.vara.mediatracker.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

/**
 * True while a full-screen overlay (detail / search) sits above the tab tree.
 *
 * In-screen `BackHandler`s (e.g. a collection list) must yield to the overlay's handler,
 * otherwise — because they register *after* the overlay's — they steal the back gesture
 * and pop the screen underneath while the overlay stays open.
 */
val LocalOverlayOpen = compositionLocalOf { false }

/**
 * A `BackHandler` that yields to a full-screen overlay (detail / search) while one is open.
 *
 * Use this instead of a bare `BackHandler` for any in-screen "back" (a section list, a
 * collection list, …). It enforces two rules that a raw `BackHandler` gets wrong:
 *
 *  - **Call it unconditionally** and drive it with [enabled]. Composing a `BackHandler`
 *    inside an `if` changes the composition order, and `OnBackPressedDispatcher` invokes
 *    the most recently added *enabled* callback — so a conditionally-composed handler can
 *    end up outranking the overlay's and silently swallow the back event.
 *  - **Yield while an overlay is open.** The overlay's own handler is registered once at
 *    startup, so any screen handler registered later (i.e. when its list opens) sits *above*
 *    it in the dispatcher. Without this guard the list closes invisibly under the overlay
 *    and the detail stays put, so back "does nothing".
 *
 * The [LocalOverlayOpen] read lives in here so the host screen itself does not recompose
 * every time an overlay opens or closes.
 */
@Composable
fun OverlayAwareBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled && !LocalOverlayOpen.current, onBack = onBack)
}
