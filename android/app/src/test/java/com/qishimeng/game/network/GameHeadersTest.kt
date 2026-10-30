package com.qishimeng.game.network

import com.qishimeng.game.auth.GameInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class GameHeadersTest {
    @Test
    fun addsAllSixHeadersAndEmptiesPrivacyAndSessionFieldsBeforeConsent() {
        val headers = GameHeaders.toMap(
            GameInfo("1-25-6#10028", 266, "1.0.1", "token", "android", "ABCD"),
            privacyAgreed = false
        )

        assertEquals(
            mapOf("Gameid" to "266", "Devicecode" to "", "Packagecode" to "1-25-6#10028", "Devicetype" to "android", "Accountoken" to "", "Gameversion" to "1.0.1"),
            headers
        )
    }

    @Test
    fun includesStoredTokenAndDeviceCodeAfterConsent() {
        val headers = GameHeaders.toMap(GameInfo("", 266, "1.0.1", "token", "android", "ABCD"), true)
        assertEquals("token", headers.getValue("Accountoken"))
        assertEquals("ABCD", headers.getValue("Devicecode"))
        assertEquals(6, headers.size)
    }
}
