package com.qishimeng.game.bridge

import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeScriptTest {
    @Test
    fun publishesAllContractMethodsAndUsesCallbackResults() {
        listOf("getAccountToken", "getGameInfo", "closeMiniWindow", "changeGameUrl", "saveGameRole", "getGameRole", "notifyLoginSuccess").forEach {
            assertTrue(BridgeScript.SOURCE.contains(it))
        }
        assertTrue(BridgeScript.SOURCE.contains("__QSMH5APP_CALLBACK__"))
        assertTrue(BridgeScript.SOURCE.contains("postMessage"))
    }
}
