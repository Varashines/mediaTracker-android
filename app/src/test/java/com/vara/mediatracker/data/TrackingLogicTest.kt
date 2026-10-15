package com.vara.mediatracker.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackingLogicTest {

    @Test
    fun formatRuntime_formatsTVMinutesCorrectly() {
        assertEquals("45m", TrackingLogic.formatRuntime(45, isTV = true))
        assertEquals("60m", TrackingLogic.formatRuntime(60, isTV = true))
        assertEquals("NA", TrackingLogic.formatRuntime(null, isTV = true))
        assertEquals("NA", TrackingLogic.formatRuntime(0, isTV = true))
    }

    @Test
    fun formatRuntime_formatsMovieHoursAndMinutesCorrectly() {
        assertEquals("2h 15m", TrackingLogic.formatRuntime(135, isTV = false))
        assertEquals("2h", TrackingLogic.formatRuntime(120, isTV = false))
        assertEquals("45m", TrackingLogic.formatRuntime(45, isTV = false))
        assertEquals("NA", TrackingLogic.formatRuntime(null, isTV = false))
    }
}
