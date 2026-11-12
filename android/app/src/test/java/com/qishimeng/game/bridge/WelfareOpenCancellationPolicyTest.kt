package com.qishimeng.game.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WelfareOpenCancellationPolicyTest {
    @Test
    fun closeAndLogoutOperationsCancelPendingWelfareOpen() {
        assertTrue(shouldCancelPendingWelfareOpen("closeWelfarepage", emptyMap(), "web_game"))
        assertTrue(shouldCancelPendingWelfareOpen("closeMiniWindow", mapOf("page" to "web_welfare"), "web_game"))
        assertTrue(shouldCancelPendingWelfareOpen("closePage", emptyMap(), "web_welfare"))
        assertTrue(shouldCancelPendingWelfareOpen("logout", emptyMap(), "web_game"))
    }

    @Test
    fun verticalRequestCancelsButUnrelatedOperationsKeepPendingOpen() {
        assertTrue(shouldCancelPendingWelfareOpen("changeScreenmode", mapOf("mode" to "vertical"), "web_game"))
        assertFalse(shouldCancelPendingWelfareOpen("changeScreenmode", mapOf("mode" to "horizontal"), "web_game"))
        assertFalse(shouldCancelPendingWelfareOpen("closeMiniWindow", mapOf("page" to "web_login"), "web_game"))
        assertFalse(shouldCancelPendingWelfareOpen("closePage", emptyMap(), "web_game"))
    }
}
