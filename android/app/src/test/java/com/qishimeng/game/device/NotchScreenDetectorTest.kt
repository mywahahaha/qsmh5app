package com.qishimeng.game.device

import android.graphics.Rect
import android.view.DisplayCutout
import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NotchScreenDetectorTest {
    @Test fun screenWithoutCutoutIsZero() {
        assertEquals(0, NotchScreenDetector.fromCutout(null))
        assertEquals(0, NotchScreenDetector.fromCutout(DisplayCutout(Rect(), emptyList())))
    }

    @Test fun cameraHoleOrNotchAtEitherEdgeIsOne() {
        assertEquals(1, NotchScreenDetector.fromBoundingRects(listOf(Rect(420, 0, 500, 80))))
        assertEquals(1, NotchScreenDetector.fromBoundingRects(listOf(Rect(0, 420, 80, 500))))
    }

    @Test fun androidNineAndTenAllowCutoutsOnShortEdges() {
        assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES,
            NotchScreenDetector.layoutMode(28))
        assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES,
            NotchScreenDetector.layoutMode(29))
    }

    @Test fun androidElevenAndLaterAllowCutoutsOnAllEdges() {
        assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS,
            NotchScreenDetector.layoutMode(30))
        assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS,
            NotchScreenDetector.layoutMode(35))
    }
}
