package com.qishimeng.game.network

import com.qishimeng.game.auth.GameInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class GameHeadersTest {
    @Test
    fun sendsNotchStatusBeforeConsentAndEmptiesPrivacyAndSessionFields() {
        val headers = GameHeaders.toMap(
            GameInfo("1-25-6#10028", 266, "1.0.1", "token", "android", "ABCD"),
            privacyAgreed = false
        )

        assertEquals(
            mapOf("Gameid" to "266", "Devicecode" to "", "Packagecode" to "1-25-6#10028", "Devicetype" to "android", "Accountoken" to "", "Gameversion" to "1.0.1", "Notchscreen" to "0", "Screenmode" to "vertical"),
            headers
        )
    }

    @Test
    fun includesStoredTokenAndDeviceCodeAfterConsent() {
        val headers = GameHeaders.toMap(GameInfo("", 266, "1.0.1", "token", "android", "ABCD"), true)
        assertEquals("token", headers.getValue("Accountoken"))
        assertEquals("ABCD", headers.getValue("Devicecode"))
        assertEquals("0", headers.getValue("Notchscreen"))
        assertEquals("vertical", headers.getValue("Screenmode"))
        assertEquals(8, headers.size)
    }

    @Test
    fun cutoutValueIsSentAsOneAndExposedToH5BridgeWithoutPrivacyConsent() {
        val info = GameInfo("", 266, "1.0.1", "token", "android", "private-device", notchScreen = 1)
        assertEquals("1", GameHeaders.toMap(info, false).getValue("Notchscreen"))
        val bridgeInfo = info.forBridge(false)
        assertEquals(1, bridgeInfo.notchScreen)
        assertEquals("", bridgeInfo.deviceCode)
    }

    @Test
    fun horizontalScreenModeIsSharedByHeadersAndH5Bridge() {
        val info = GameInfo("", 266, "1.0.1", "", "android", "", screenMode = "horizontal")

        assertEquals("horizontal", GameHeaders.toMap(info, false).getValue("Screenmode"))
        assertEquals("horizontal", info.forBridge(false).screenMode)
    }
}
