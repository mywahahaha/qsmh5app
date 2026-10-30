package com.qishimeng.game.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCodeProviderTest {
    @Test
    fun refusesToReadIdentifiersBeforeConsent() {
        var readCount = 0
        val provider = DeviceCodeProvider(object : DeviceIdentifiers {
            override fun oaid(): String? { readCount++; return "oaid-value" }
            override fun androidId(): String? { readCount++; return "android-id" }
        }, InMemoryDeviceCodePersistence())

        assertEquals("", provider.get(privacyAgreed = false))
        assertEquals(0, readCount)
    }

    @Test
    fun hashesOaidAndAndroidIdInUppercaseAndPersistsDiyCode() {
        val persistence = InMemoryDeviceCodePersistence()
        val provider = DeviceCodeProvider(object : DeviceIdentifiers {
            override fun oaid() = "OAID"
            override fun androidId() = "android-id"
        }, persistence, diyValue = { "random-diy" })
        val oaidCode = provider.get(true)
        assertEquals("0ED86B8D2DFE3691C7E6C7F081C8E667", oaidCode)
        assertEquals(oaidCode, persistence.deviceCode)

        val diyPersistence = InMemoryDeviceCodePersistence()
        val diyProvider = DeviceCodeProvider(object : DeviceIdentifiers {
            override fun oaid() = null
            override fun androidId() = null
        }, diyPersistence, diyValue = { "random-diy" })
        val diy = diyProvider.get(true)
        assertEquals("MD5", 32, diy.length)
        assertTrue(diy.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(diy, DeviceCodeProvider(object : DeviceIdentifiers {
            override fun oaid() = "new-oaid"
            override fun androidId() = "android-id"
        }, diyPersistence).get(true))
    }

    @Test
    fun fallsBackToAndroidIdWhenOaidReadFailsAndReusesStoredCode() {
        val persistence = InMemoryDeviceCodePersistence()
        val provider = DeviceCodeProvider(object : DeviceIdentifiers {
            override fun oaid(): String? = throw SecurityException("OAID unavailable")
            override fun androidId() = "android-id"
        }, persistence)
        val code = provider.get(true)
        assertEquals("B9833271804407EF8B71E643F7014364", code)
        assertEquals(code, persistence.deviceCode)
        assertEquals(code, DeviceCodeProvider(object : DeviceIdentifiers {
            override fun oaid(): String? = error("已保存后不得再次读取")
            override fun androidId(): String? = error("已保存后不得再次读取")
        }, persistence).get(true))
    }

    @Test
    fun generatesDiyWhenIdentifiersThrowAndIgnoresBlankStorage() {
        val persistence = InMemoryDeviceCodePersistence().apply { deviceCode = " " }
        val provider = DeviceCodeProvider(object : DeviceIdentifiers {
            override fun oaid(): String? = throw SecurityException()
            override fun androidId(): String? = throw SecurityException()
        }, persistence, diyValue = { "random-diy" })
        val code = provider.get(true)
        assertEquals(32, code.length)
        assertTrue(code.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(code, persistence.deviceCode)
        assertEquals("", provider.get(false))
    }
}
