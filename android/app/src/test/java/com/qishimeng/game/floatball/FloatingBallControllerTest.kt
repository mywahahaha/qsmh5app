package com.qishimeng.game.floatball

import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingBallControllerTest {
    @org.junit.Test
    fun newSessionRestoresInitialVisibilityAfterHeartbeatHidBall() {
        val controller = FloatingBallController(true)
        controller.setSessionReady(true)
        controller.setRuntimeState(false, true)
        controller.resetSession()
        org.junit.Assert.assertFalse(controller.state().visible)
        controller.setSessionReady(true)
        org.junit.Assert.assertEquals(FloatingBallState(true, false), controller.state())
    }

    @org.junit.Test
    fun sessionResetDoesNotEnableBallDisabledByInitialization() {
        val controller = FloatingBallController(false)
        controller.setRuntimeState(true, true)
        controller.resetSession()
        controller.setSessionReady(true)
        org.junit.Assert.assertEquals(FloatingBallState(false, false), controller.state())
    }
    @Test
    fun gameAndLoginGateVisibilityAndWelfareTemporarilyHidesLatestRuntimeState() {
        val controller = FloatingBallController(initiallyEnabled = true)
        assertEquals(false, controller.state().visible)
        controller.setSessionReady(true)
        assertEquals(true, controller.state().visible)
        controller.setWelfareOpen(true)
        controller.setRuntimeState(show = false, active = true)
        assertEquals(false, controller.state().visible)
        controller.setWelfareOpen(false)
        assertEquals(false, controller.state().visible)
        assertEquals(true, controller.state().active)
    }

    @Test
    fun unchangedImageStateDoesNotRequestReloadAndLayoutUsesScreenScaling() {
        var reloads = 0
        val controller = FloatingBallController(true) { reloads++ }
        controller.setSessionReady(true)
        controller.setRuntimeState(true, false)
        controller.setRuntimeState(true, true)
        controller.setRuntimeState(false, true)
        assertEquals(1, reloads)
        assertEquals(144, FloatingBallLayout.toPixels(36, screenWidthDp = 720, density = 2f))
    }
}
