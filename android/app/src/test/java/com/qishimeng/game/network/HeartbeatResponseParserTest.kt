package com.qishimeng.game.network

import org.junit.Assert.*
import org.junit.Test

class HeartbeatResponseParserTest {
    @Test(expected = ArithmeticException::class)
    fun fractionalCodeCannotBeTruncatedIntoSuccess() {
        HeartbeatResponseParser.parse("""{"code":0.5,"data":{"popup_pages":[]}}""")
    }

    @Test
    fun missingPopupListAllowsIndependentFloatingBallUpdate() {
        val response = HeartbeatResponseParser.parse("""{"code":0,"msg":"success","data":{"floating_ball":{"show":false,"active":true}}}""")
        assertNull(response.popupPages)
        assertEquals(FloatingBallHeartbeat(false, true), response.floatingBall)
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedFloatingStateDoesNotMasqueradeAsFalse() {
        HeartbeatResponseParser.parse("""{"code":0,"data":{"floating_ball":{"show":"false","active":true}}}""")
    }
}
