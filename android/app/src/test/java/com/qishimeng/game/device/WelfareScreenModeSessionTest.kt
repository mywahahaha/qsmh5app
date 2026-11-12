package com.qishimeng.game.device

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WelfareScreenModeSessionTest {
    @Test
    fun portraitSelectsBusinessLandscapeAndWaitsForPhysicalRotation() {
        var selectedCount = 0
        val requested = mutableListOf<Int>()
        val session = WelfareScreenModeSession(
            selectHorizontal = { selectedCount++ },
            requestOrientation = requested::add
        )

        assertFalse(session.shouldOpenNow(Configuration.ORIENTATION_PORTRAIT))

        assertEquals(1, selectedCount)
        assertEquals(listOf(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE), requested)
        assertFalse(session.onConfigurationChanged(Configuration.ORIENTATION_PORTRAIT))
        assertTrue(session.onConfigurationChanged(Configuration.ORIENTATION_LANDSCAPE))
    }

    @Test
    fun existingLandscapeSelectsBusinessModeAndOpensWithoutOrientationRequest() {
        var selectedCount = 0
        val requested = mutableListOf<Int>()
        val session = WelfareScreenModeSession(
            selectHorizontal = { selectedCount++ },
            requestOrientation = requested::add
        )

        assertTrue(session.shouldOpenNow(Configuration.ORIENTATION_LANDSCAPE))

        assertEquals(1, selectedCount)
        assertTrue(requested.isEmpty())
    }

    @Test
    fun repeatedRequestWhileWaitingDoesNotRepeatSelectionOrOrientationRequest() {
        var selectedCount = 0
        val requested = mutableListOf<Int>()
        val session = WelfareScreenModeSession(
            selectHorizontal = { selectedCount++ },
            requestOrientation = requested::add
        )

        assertFalse(session.shouldOpenNow(Configuration.ORIENTATION_PORTRAIT))
        assertFalse(session.shouldOpenNow(Configuration.ORIENTATION_PORTRAIT))

        assertEquals(1, selectedCount)
        assertEquals(listOf(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE), requested)
    }

    @Test
    fun cancellingPendingOpenNeverRestoresPreviousOrientation() {
        val requested = mutableListOf<Int>()
        val session = WelfareScreenModeSession(
            selectHorizontal = {},
            requestOrientation = requested::add
        )
        assertFalse(session.shouldOpenNow(Configuration.ORIENTATION_PORTRAIT))

        session.cancelPendingOpen()

        assertEquals(listOf(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE), requested)
        assertFalse(session.onConfigurationChanged(Configuration.ORIENTATION_LANDSCAPE))
    }
}
