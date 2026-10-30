package com.qishimeng.game.network

import com.qishimeng.game.auth.GameInfo
import com.qishimeng.game.config.LayoutValue
import com.qishimeng.game.config.PageSpec
import com.qishimeng.game.floatball.FloatingBallController
import com.qishimeng.game.floatball.FloatingBallState
import com.qishimeng.game.web.PopupWindowCoordinator
import com.qishimeng.game.web.WebInstanceRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

class HeartbeatFlowIntegrationTest {
    @Test
    fun hiddenBallThenLogoutAndReloginRestartsHeartbeatUsingInitialBallState() {
        val scheduler = ManualHeartbeatScheduler()
        val ball = FloatingBallController(true)
        val session = PopupHeartbeatSession("https://box.74meng.com/popup", 30,
            { HeartbeatResponse(null, FloatingBallHeartbeat(false, true)) }, { it() },
            { response -> response.floatingBall?.let { ball.setRuntimeState(it.show, it.active) } }, scheduler)
        ball.setSessionReady(true)
        session.update(true, ball.state().visible)
        scheduler.runNext()
        assertFalse(ball.state().visible)
        session.stop()
        ball.resetSession()
        ball.setSessionReady(true)
        session.update(true, ball.state().visible)
        assertEquals(FloatingBallState(true, false), ball.state())
        assertEquals(listOf(30_000L, 30_000L, 30_000L), scheduler.delays)
        session.close()
    }

    @Test
    fun hiddenBallContinuesPollingAndNextResponseRestoresItWhileSynchronizingOnlyOperationalPopups() {
        val bodies = ArrayDeque(listOf(
            """{"code":0,"msg":"success","data":{"popup_pages":[{"instance":"promo","url":"https://box.74meng.com/first","width":"100%","height":"100%","left":0,"top":0}],"floating_ball":{"show":false,"active":true}}}""",
            """{"code":100,"msg":"ignored","data":{"popup_pages":[],"floating_ball":{"show":true,"active":false}}}""",
            """{"code":0,"msg":"success","data":{"popup_pages":[{"instance":"promo","url":"https://box.74meng.com/changed","width":1,"height":1,"left":0,"top":0}],"floating_ball":{"show":true,"active":true}}}""",
            """{"code":0,"msg":"success","data":{"popup_pages":[],"floating_ball":{"show":true,"active":false}}}"""
        ))
        val api = ApiClient(HttpTransport { ApiResponse(200, bodies.removeFirst()) })
        val scheduler = ManualHeartbeatScheduler()
        val windows = WebInstanceRegistry()
        windows.open(PageSpec("custom_function", "https://box.74meng.com/function", LayoutValue.Pixels(100),
            LayoutValue.Pixels(100), LayoutValue.Pixels(0), LayoutValue.Pixels(0)))
        val popups = PopupWindowCoordinator(windows, setOf("custom_function"))
        val iconChanges = mutableListOf<Boolean>()
        val ball = FloatingBallController(true) { iconChanges += it }
        ball.setSessionReady(true)
        lateinit var session: PopupHeartbeatSession
        session = PopupHeartbeatSession("https://box.74meng.com/popup", 30,
            { url -> api.postHeartbeat(url, GameInfo("", 266, "1.0", "test-token", "android", ""), true) },
            { it() }, { response ->
                response.floatingBall?.let { ball.setRuntimeState(it.show, it.active) }
                popups.apply(response)
                session.update(true, ball.state().visible)
            }, scheduler)

        session.update(true, true)
        assertTrue(bodies.size == 4) // 未到首次间隔，不请求。
        scheduler.runNext()
        assertEquals(FloatingBallState(false, true), ball.state())
        assertEquals(listOf("custom_function", "promo"), windows.order())
        scheduler.runNext()
        assertEquals(FloatingBallState(false, true), ball.state())
        assertEquals(listOf("custom_function", "promo"), windows.order())
        scheduler.runNext()
        assertEquals(FloatingBallState(true, true), ball.state())
        assertEquals("https://box.74meng.com/first", windows.get("promo")?.url)
        assertEquals(listOf(true), iconChanges) // active 未变，不重复更新图标。
        scheduler.runNext()
        assertEquals(listOf("custom_function"), windows.order())
        assertEquals(FloatingBallState(true, false), ball.state())
        assertEquals(listOf(true, false), iconChanges)
        session.close()
    }
}
