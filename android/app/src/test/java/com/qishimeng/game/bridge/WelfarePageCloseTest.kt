package com.qishimeng.game.bridge

import com.qishimeng.game.device.ScreenMode
import com.qishimeng.game.device.WelfarePageInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertThrows

class WelfarePageCloseTest {
    @Test
    fun closeRequestDefaultsToDiscardingPendingGameUrl() {
        assertFalse(welfareCloseLoadsPendingGameUrl(null))
        assertFalse(welfareCloseLoadsPendingGameUrl(false))
        assertTrue(welfareCloseLoadsPendingGameUrl(true))
    }

    @Test
    fun validModeClosesWelfareBeforeChangingScreenMode() {
        val actions = mutableListOf<String>()

        val closed = closeWelfarePage(
            mode = "vertical",
            close = { actions += "close"; true },
            changeScreenMode = { mode -> actions += "change:$mode"; true }
        )

        assertTrue(closed)
        assertEquals(listOf("close", "change:vertical"), actions)
    }

    @Test
    fun invalidModesCloseWelfareWithoutChangingScreenMode() {
        listOf<Any?>(null, "diagonal", 1, true).forEach { mode ->
            var changeCount = 0

            val closed = closeWelfarePage(
                mode = mode,
                close = { true },
                changeScreenMode = { changeCount++; true }
            )

            assertTrue("无效 mode=$mode 仍应关闭大厅", closed)
            assertEquals("无效 mode=$mode 不得切换屏幕", 0, changeCount)
        }
    }

    @Test
    fun returnsCloseResultEvenWhenValidModeRequestsRotation() {
        var requestedMode: Any? = null

        val closed = closeWelfarePage(
            mode = "horizontal",
            close = { false },
            changeScreenMode = { mode -> requestedMode = mode; true }
        )

        assertFalse(closed)
        assertEquals("horizontal", requestedMode)
    }

    @Test
    fun welfareInfoIsSerializedOnlyForWelfarePage() {
        val info = WelfarePageInfo(ScreenMode.VERTICAL, ScreenMode.HORIZONTAL)

        assertEquals(
            mapOf("entryMode" to "vertical", "currentMode" to "horizontal"),
            welfareInfoForBridge("web_welfare", info)
        )
        assertThrows(IllegalArgumentException::class.java) {
            welfareInfoForBridge("web_game", info)
        }
        assertThrows(IllegalArgumentException::class.java) {
            welfareInfoForBridge("web_welfare", null)
        }
    }
}
