package com.qishimeng.game.web

import org.junit.Assert.*
import org.junit.Test

class PageLoadRecoveryTest {
    @Test fun proxyFailureIsRetriedThreeTimesThenKeepsItsDiagnosticCode() {
        val state = PageLoadRecovery()
        state.start()
        val error = PageLoadFailure.network(-1, "net::ERR_PROXY_CONNECTION_FAILED")
        assertEquals("H5_PROXY", error.code)
        state.fail(error)
        assertFalse("失败文档的提交不得当成成功", state.succeed())
        assertFalse("重复回调不能消耗次数", state.fail(error))
        assertTrue(state.retry())
        state.fail(error)
        assertTrue(state.retry())
        state.fail(error)
        assertEquals("第三次失败后仍需执行第三次自动重试", PageLoadPhase.WAITING_RETRY, state.phase)
        assertTrue(state.retry())
        state.fail(error)
        assertEquals(4, state.attempt)
        assertEquals(PageLoadPhase.ERROR, state.phase)
        assertFalse(state.retry())
        assertFalse(state.succeed())
        assertEquals("H5_PROXY", state.failure!!.code)
    }

    @Test fun successfulRetryIgnoresLateFailureAndManualReloadGetsFreshBudget() {
        val state = PageLoadRecovery()
        state.start()
        state.fail(PageLoadFailure.timeout())
        state.retry()
        assertTrue(state.succeed())
        assertFalse(state.fail(PageLoadFailure.timeout()))
        assertEquals(PageLoadPhase.READY, state.phase)
        assertNull(state.failure)
        state.start()
        assertEquals(1, state.attempt)
        assertEquals(PageLoadPhase.LOADING, state.phase)
    }

    @Test fun closeDisablesPendingRetryAndNewLoads() {
        val state = PageLoadRecovery()
        state.start()
        state.fail(PageLoadFailure.timeout())
        state.close()
        assertFalse(state.retry())
        assertFalse(state.start())
        assertFalse(state.succeed())
        assertFalse(state.fail(PageLoadFailure.timeout()))
    }

    @Test fun certificateErrorsAndPermanentHttpErrorsAreNotAutomaticallyRetried() {
        for (error in listOf(PageLoadFailure.ssl(), PageLoadFailure.http(404), PageLoadFailure.http(403))) {
            val state = PageLoadRecovery()
            state.start()
            state.fail(error)
            assertEquals(PageLoadPhase.ERROR, state.phase)
            assertFalse(state.retry())
        }
        assertTrue(PageLoadFailure.http(503).retryable)
        assertTrue(PageLoadFailure.http(408).retryable)
        assertTrue(PageLoadFailure.http(429).retryable)
    }

    @Test fun networkErrorsHaveStableSupportCodes() {
        val cases = listOf(-2 to "H5_DNS", -6 to "H5_CONNECT", -7 to "H5_CONNECT", -8 to "H5_TIMEOUT", -11 to "H5_SSL", -1 to "H5_NETWORK")
        cases.forEach { (code, expected) -> assertEquals(expected, PageLoadFailure.network(code, "").code) }
    }

    @Test fun unknownOrNonRepeatableNavigationDoesNotAutomaticallyReplay() {
        val state = PageLoadRecovery()
        state.start(allowAutomaticRetry = false)
        state.fail(PageLoadFailure.http(503))
        assertEquals(PageLoadPhase.ERROR, state.phase)
        assertFalse(state.retry())
        state.start()
        state.fail(PageLoadFailure.http(503))
        assertTrue("手动恢复 GET 页面后重新启用有限重试", state.retry())
    }
}
