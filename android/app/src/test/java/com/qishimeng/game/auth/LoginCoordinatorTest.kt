package com.qishimeng.game.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class LoginCoordinatorTest {
    @Test
    fun usesTokenThenOnekeyAndFallsBackToH5() {
        val attempts = mutableListOf<String>()
        val coordinator = LoginCoordinator(
            tokenLogin = { attempts += "token"; TokenLoginOutcome.Fallback },
            onekeyLogin = { enabled -> attempts += "onekey:$enabled"; OneKeyLoginOutcome.Fallback }
        )

        assertEquals(LoginOutcome.ShowH5Login, coordinator.login(onekeyEnabled = true))
        assertEquals(listOf("token", "onekey:true"), attempts)
    }

    @Test
    fun skipsOnekeyWhenDisabledAndStopsAfterEitherSuccessfulMethod() {
        val disabled = LoginCoordinator({ TokenLoginOutcome.Fallback }, { error("must not run") })
        assertEquals(LoginOutcome.ShowH5Login, disabled.login(false))

        val tokenSuccess = LoginCoordinator({ TokenLoginOutcome.Success("token") }, { error("must not run") })
        assertEquals(LoginOutcome.Game("token"), tokenSuccess.login(true))

        val oneKeySuccess = LoginCoordinator({ TokenLoginOutcome.Fallback }, { OneKeyLoginOutcome.Success("onekey") })
        assertEquals(LoginOutcome.Game("onekey"), oneKeySuccess.login(true))
    }
}
