package com.qishimeng.game.core

import org.junit.Assert.assertEquals
import org.junit.Test

class StartupCoordinatorTest {
    @Test
    fun loginStagesFollowPrivacyTokenAliyunH5ThenGameOrder() {
        assertEquals(
            listOf(StartupState.Privacy, StartupState.Update, StartupState.TokenLogin, StartupState.AliyunLogin, StartupState.H5Login, StartupState.Game),
            StartupState.loginOrder
        )
    }

    @Test
    fun initializationFailureDoesNotExposeGameState() {
        assertEquals(StartupState.Failed, StartupState.afterInitialization(false))
    }
}
