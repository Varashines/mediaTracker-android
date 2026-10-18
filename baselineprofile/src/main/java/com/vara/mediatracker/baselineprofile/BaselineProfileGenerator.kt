package com.vara.mediatracker.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import org.junit.Rule
import org.junit.Test

/**
 * Generates the baseline profile, by running the app and recording what it touches.
 *
 * This is the real thing, and it replaces the hand-authored `baseline-prof.txt` rather than adding
 * to it: a recorded profile knows which methods actually dominate startup, which is precisely what
 * cannot be guessed by hand.
 *
 * It needs a device or an emulator — a real one, on API 28 or above — so it does not run as part of
 * an ordinary build. Run it with:
 *
 *     ./gradlew :app:generateBaselineProfile
 *
 * and commit the profile it writes into `app/src/main/baseline-prof.txt`.
 *
 * What is recorded here is startup and the first screen. That covers the cold start, which is the
 * part users feel; extending it to the detail screen, the calendar and search would widen it, and
 * the interactions would have to be written against the real UI rather than guessed at.
 */
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() {
        rule.collect(packageName = PACKAGE) {
            pressHome()
            startActivityAndWait()
        }
    }

    private companion object {
        const val PACKAGE = "com.vara.mediatracker"
    }
}
