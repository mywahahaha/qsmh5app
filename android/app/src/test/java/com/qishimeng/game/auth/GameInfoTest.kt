package com.qishimeng.game.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class GameInfoTest {
    @Test
    fun bridgePreservesDeviceCodePreparedByConsentGate() {
        val localInfo = GameInfo("channel", 266, "1.0.1", "token", "android", "DEVICECODE")

        assertEquals("DEVICECODE", localInfo.forBridge(true).deviceCode)
        assertEquals("token", localInfo.forBridge(true).accountToken)
        assertEquals("DEVICECODE", localInfo.deviceCode)
    }

    @Test
    fun bridgeKeepsDeviceCodeEmptyBeforeConsent() {
        val localInfo = GameInfo("channel", 266, "1.0.1", "", "android", "STORED-CODE")
        assertEquals("", localInfo.forBridge(false).deviceCode)
    }
}
