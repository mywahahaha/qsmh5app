package com.qishimeng.game.web

import com.qishimeng.game.config.LayoutValue
import org.junit.Assert.assertEquals
import org.junit.Test

class WindowLayoutTest {
    @Test
    fun convertsPercentDpAndCenterAgainstCurrentViewport() {
        val layout = WindowLayout.resolve(
            LayoutValue.Percent(60f), LayoutValue.Percent(75f),
            LayoutValue.Center, LayoutValue.Center,
            viewportWidthPx = 1_080, viewportHeightPx = 2_400, density = 3f
        )

        assertEquals(648, layout.widthPx)
        assertEquals(1_800, layout.heightPx)
        assertEquals(216, layout.leftPx)
        assertEquals(300, layout.topPx)

        assertEquals(30, WindowLayout.toPixels(LayoutValue.Pixels(10), 1_000, 3f))
    }
}
