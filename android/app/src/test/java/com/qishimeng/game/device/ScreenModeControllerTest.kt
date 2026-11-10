package com.qishimeng.game.device

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenModeControllerTest {
    @Test
    fun reportsHorizontalAndVerticalFromCurrentConfiguration() {
        assertEquals("horizontal", ScreenMode.fromConfiguration(Configuration.ORIENTATION_LANDSCAPE).value)
        assertEquals("vertical", ScreenMode.fromConfiguration(Configuration.ORIENTATION_PORTRAIT).value)
    }

    @Test
    fun changesVerticalApplicationToHorizontal() {
        val requested = mutableListOf<Int>()
        val controller = ScreenModeController(
            currentOrientation = { Configuration.ORIENTATION_PORTRAIT },
            requestOrientation = requested::add
        )

        assertTrue(controller.change("horizontal"))
        assertEquals(listOf(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE), requested)
    }

    @Test
    fun changesHorizontalApplicationToVertical() {
        val requested = mutableListOf<Int>()
        val controller = ScreenModeController(
            currentOrientation = { Configuration.ORIENTATION_LANDSCAPE },
            requestOrientation = requested::add
        )

        assertTrue(controller.change("vertical"))
        assertEquals(listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT), requested)
    }

    @Test
    fun ignoresSameModeAndStillReturnsTrue() {
        val requested = mutableListOf<Int>()
        val controller = ScreenModeController(
            currentOrientation = { Configuration.ORIENTATION_LANDSCAPE },
            requestOrientation = requested::add
        )

        assertTrue(controller.change("horizontal"))
        assertTrue(requested.isEmpty())
    }

    @Test
    fun latestRequestOverridesOppositePendingRequest() {
        val requested = mutableListOf<Int>()
        val controller = ScreenModeController(
            currentOrientation = { Configuration.ORIENTATION_PORTRAIT },
            requestOrientation = requested::add
        )

        assertTrue(controller.change("horizontal"))
        assertTrue(controller.change("vertical"))

        assertEquals(
            listOf(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT),
            requested
        )
    }

    @Test
    fun rejectsUnsupportedModeWithRequiredMessage() {
        val controller = ScreenModeController(
            currentOrientation = { Configuration.ORIENTATION_PORTRAIT },
            requestOrientation = {}
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            controller.change("diagonal")
        }
        assertEquals("参数无效", error.message)
    }

    @Test
    fun rejectsMissingModeWithRequiredMessage() {
        val controller = ScreenModeController(
            currentOrientation = { Configuration.ORIENTATION_PORTRAIT },
            requestOrientation = {}
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            controller.change(null)
        }
        assertEquals("参数无效", error.message)
    }
}
