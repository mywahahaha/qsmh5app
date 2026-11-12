package com.qishimeng.game.bridge

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class BridgeScriptTest {
    @Test
    fun publishesAllContractMethodsAndUsesCallbackResults() {
        listOf("getAccountToken", "getGameInfo", "changeScreenmode", "closeMiniWindow", "closeWelfarepage", "changeGameUrl", "saveGameRole", "getGameRole", "notifyLoginSuccess").forEach {
            assertTrue(BridgeScript.SOURCE.contains(it))
        }
        assertTrue(BridgeScript.SOURCE.contains("__QSMH5APP_CALLBACK__"))
        assertFalse(BridgeScript.SOURCE.contains("__QSMH5APP_NOTIFY__"))
        assertFalse(BridgeScript.SOURCE.contains("bridge.on"))
        assertTrue(BridgeScript.SOURCE.contains("postMessage"))
    }
}
