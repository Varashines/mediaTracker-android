package com.vara.mediatracker.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Thin, intent-named wrapper over Compose haptics.
 *
 * Android only exposes a couple of feedback primitives, so the vocabulary is small on
 * purpose: [tap] for a light selection tick (nav switches, chip selects), [toggle] for a
 * firmer confirmation (mark watched, status/taste changes).
 */
class Haptics(private val feedback: HapticFeedback) {
    /** Light tick for selections. */
    fun tap() = feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)

    /** Firmer pulse for confirmations / state changes. */
    fun toggle() = feedback.performHapticFeedback(HapticFeedbackType.LongPress)

    /** Alias for a decisive action. */
    fun confirm() = feedback.performHapticFeedback(HapticFeedbackType.LongPress)
}

@Composable
fun rememberHaptics(): Haptics {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { Haptics(feedback) }
}
