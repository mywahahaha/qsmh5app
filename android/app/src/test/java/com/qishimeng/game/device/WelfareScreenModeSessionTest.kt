package com.qishimeng.game.device

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WelfareScreenModeSessionTest {
    @Test
    fun portraitSelectsBusinessLandscapeAndWaitsForPhysicalRotation() {
        var selectedCount = 0
        var currentMode = ScreenMode.VERTICAL
        val requested = mutableListOf<Int>()
        val session = WelfareScreenModeSession(
            currentMode = { currentMode },
            selectHorizontal = { selectedCount++; currentMode = ScreenMode.HORIZONTAL },
            requestOrientation = requested::add
        )

        assertFalse(session.shouldOpenNow(Configuration.ORIENTATION_PORTRAIT))

        assertEquals(1, selectedCount)
        assertEquals(listOf(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE), requested)
        assertEquals(WelfarePageInfo(ScreenMode.VERTICAL, ScreenMode.HORIZONTAL), session.info())
        assertFalse(session.onConfigurationChanged(Configuration.ORIENTATION_PORTRAIT))
        assertTrue(session.onConfigurationChanged(Configuration.ORIENTATION_LANDSCAPE))
    }

    @Test
    fun existingLandscapeSelectsBusinessModeAndOpensWithoutOrientationRequest() {
        var selectedCount = 0
        val currentMode = ScreenMode.HORIZONTAL
        val requested = mutableListOf<Int>()
        val session = WelfareScreenModeSession(
            currentMode = { currentMode },
            selectHorizontal = { selectedCount++ },
            requestOrientation = requested::add
        )

        assertTrue(session.shouldOpenNow(Configuration.ORIENTATION_LANDSCAPE))

        assertEquals(1, selectedCount)
        assertTrue(requested.isEmpty())
        assertEquals(WelfarePageInfo(ScreenMode.HORIZONTAL, ScreenMode.HORIZONTAL), session.info())
    }

    @Test
    fun repeatedRequestWhileWaitingDoesNotRepeatSelectionOrOrientationRequest() {
        var selectedCount = 0
        var currentMode = ScreenMode.VERTICAL
        val requested = mutableListOf<Int>()
        val session = WelfareScreenModeSession(
            currentMode = { currentMode },
            selectHorizontal = { selectedCount++; currentMode = ScreenMode.HORIZONTAL },
            requestOrientation = requested::add
        )

        assertFalse(session.shouldOpenNow(Configuration.ORIENTATION_PORTRAIT))
        assertFalse(session.shouldOpenNow(Configuration.ORIENTATION_PORTRAIT))

        assertEquals(1, selectedCount)
        assertEquals(listOf(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE), requested)
        assertEquals("重复请求不能用横屏覆盖首次入场方向",
            WelfarePageInfo(ScreenMode.VERTICAL, ScreenMode.HORIZONTAL), session.info())
    }

    @Test
    fun cancellingPendingOpenNeverRestoresPreviousOrientation() {
        val requested = mutableListOf<Int>()
        val session = WelfareScreenModeSession(
            currentMode = { ScreenMode.VERTICAL },
            selectHorizontal = {},
            requestOrientation = requested::add
        )
        assertFalse(session.shouldOpenNow(Configuration.ORIENTATION_PORTRAIT))

        session.cancelPendingOpen()

        assertEquals(listOf(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE), requested)
        assertFalse(session.onConfigurationChanged(Configuration.ORIENTATION_LANDSCAPE))
        assertNull(session.info())
    }
}
