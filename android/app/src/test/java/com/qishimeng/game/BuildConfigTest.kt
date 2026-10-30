package com.qishimeng.game

import org.junit.Assert.assertEquals
import org.junit.Test

class BuildConfigTest {
    @Test
    fun buildConstantsAreInjectableAndDeviceTypeIsAndroid() {
        assertEquals("android", BuildConfig.GAME_DEVICE_TYPE)
        assertEquals(0, BuildConfig.GAME_ID)
        assertEquals("0.0.0-dev", BuildConfig.GAME_VERSION)
        assertEquals("com.qishimeng.template", BuildConfig.APPLICATION_ID_VALUE)
    }
}
