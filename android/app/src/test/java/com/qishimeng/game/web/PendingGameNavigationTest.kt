package com.qishimeng.game.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingGameNavigationTest {
    @Test
    fun deferredUrlReplacesOlderValueAndIsConsumedOnlyOnce() {
        val pending = PendingGameNavigation()

        pending.defer("https://game.example/first")
        pending.defer("https://game.example/latest")

        assertEquals("https://game.example/latest", pending.take())
        assertNull(pending.take())
    }

    @Test
    fun clearDiscardsDeferredUrlWithoutLoadingIt() {
        val pending = PendingGameNavigation()
        pending.defer("https://game.example/deferred")

        pending.clear()

        assertNull(pending.take())
    }
}
