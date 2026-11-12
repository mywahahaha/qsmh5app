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
    fun reportsChangedModeBeforeSystemConfigurationCatchesUp() {
        val controller = ScreenModeController(
            currentOrientation = { Configuration.ORIENTATION_PORTRAIT },
            requestOrientation = {}
        )

        assertTrue(controller.change("horizontal"))

        assertEquals(ScreenMode.HORIZONTAL, controller.currentMode())
    }

    @Test
    fun keepsReportedModeWhenOnlySystemConfigurationChanges() {
        var orientation = Configuration.ORIENTATION_PORTRAIT
        val controller = ScreenModeController(
            currentOrientation = { orientation },
            requestOrientation = {}
        )
        assertEquals(ScreenMode.VERTICAL, controller.currentMode())

        orientation = Configuration.ORIENTATION_LANDSCAPE

        assertEquals(ScreenMode.VERTICAL, controller.currentMode())
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
    fun restoresImmutablePackageModeAfterRepeatedRuntimeChanges() {
        val requested = mutableListOf<Int>()
        val controller = ScreenModeController(
            packageMode = ScreenMode.HORIZONTAL,
            currentOrientation = { Configuration.ORIENTATION_LANDSCAPE },
            requestOrientation = requested::add
        )

        controller.change("vertical")
        controller.change("horizontal")
        controller.change("vertical")
        controller.restorePackageMode()

        assertEquals(ScreenMode.HORIZONTAL, controller.packageMode)
        assertEquals(ScreenMode.HORIZONTAL, controller.currentMode())
        assertEquals(
            listOf(
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            ),
            requested
        )
    }

    @Test
    fun restoresPackagedVerticalModeAfterRuntimeLandscape() {
        val requested = mutableListOf<Int>()
        val controller = ScreenModeController(
            packageMode = ScreenMode.VERTICAL,
            currentOrientation = { Configuration.ORIENTATION_PORTRAIT },
            requestOrientation = requested::add
        )

        controller.change("horizontal")
        controller.restorePackageMode()

        assertEquals(ScreenMode.VERTICAL, controller.currentMode())
        assertEquals(
            listOf(
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            ),
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
