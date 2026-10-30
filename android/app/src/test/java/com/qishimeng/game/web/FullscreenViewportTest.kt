package com.qishimeng.game.web

import org.junit.Assert.assertEquals
import org.junit.Test

class FullscreenViewportTest {
    @Test
    fun usesTheFullDisplayWhenHostHasNotBeenMeasuredYet() {
        assertEquals(
            FullscreenViewport(1_080, 2_400),
            FullscreenViewport.resolve(0, 0, 1_080, 2_400)
        )
    }

    @Test
    fun prefersMeasuredHostSizeAfterFullscreenLayout() {
        assertEquals(
            FullscreenViewport(1_080, 2_300),
            FullscreenViewport.resolve(1_080, 2_300, 1_080, 2_400)
        )
    }
}
