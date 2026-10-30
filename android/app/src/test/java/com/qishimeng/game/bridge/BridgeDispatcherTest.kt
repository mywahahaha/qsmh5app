package com.qishimeng.game.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeDispatcherTest {
    @Test
    fun returnsStandardResultAndRejectsUnknownMethods() {
        val dispatcher = BridgeDispatcher(mapOf("echo" to { args -> args["value"] }))
        assertEquals(BridgeResult(0, "ok", "hello"), dispatcher.dispatch("echo", mapOf("value" to "hello")))
        assertEquals(9999, dispatcher.dispatch("missing", emptyMap()).code)
    }

    @Test
    fun mapsInvalidArgumentsToParameterError() {
        val dispatcher = BridgeDispatcher(mapOf("needsValue" to { args ->
            require(args["value"] is String)
            args["value"]
        }))

        assertEquals(1001, dispatcher.dispatch("needsValue", emptyMap()).code)
        assertTrue(dispatcher.dispatch("needsValue", emptyMap()).msg.isNotBlank())
    }
}
