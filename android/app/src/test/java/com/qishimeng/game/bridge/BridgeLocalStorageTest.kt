package com.qishimeng.game.bridge

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.*
import org.junit.Test

class BridgeLocalStorageTest {
    @Test fun convertsJsonNumbersAndStringsAndEmptiesOtherValueTypes() {
        val preferences = MemoryLocalStoragePreferences()
        val store = BridgeLocalStorage(preferences)
        val type = object : TypeToken<Map<String, Any?>>() {}.type
        val values: Map<String, Any?> = Gson().fromJson(
            """{"abc":123,"def":456,"fraction":1.25,"zero":0,"negative":-12,"text":"中文","empty":"","flag":true,"falseFlag":false,"object":{"a":1},"array":[1],"null":null}""", type
        )

        assertTrue(store.save(values.map { (key, value) -> mapOf("key" to key, "value" to value) }))
        assertEquals("123", store.get("abc"))
        assertEquals("456", store.get("def"))
        assertEquals("1.25", store.get("fraction"))
        assertEquals("0", store.get("zero"))
        assertEquals("-12", store.get("negative"))
        assertEquals("中文", store.get("text"))
        listOf("empty", "flag", "falseFlag", "object", "array", "null").forEach {
            assertEquals("", store.get(it))
        }
        assertEquals(12, preferences.values.size)
    }

    @Test fun overwritesOnlySubmittedKeysAndStoresInvalidValuesAsEmpty() {
        val preferences = MemoryLocalStoragePreferences()
        val store = BridgeLocalStorage(preferences)
        store.save(listOf(mapOf("key" to "abc", "value" to "old"), mapOf("key" to "def", "value" to "keep"), mapOf("key" to "", "value" to "empty-key")))
        store.save(mapOf("key" to "abc", "value" to 123))
        assertEquals("123", store.get("abc"))
        assertEquals("keep", store.get("def"))
        assertEquals("empty-key", store.get(""))
        store.save(mapOf("key" to "abc", "value" to null))
        assertEquals("", store.get("abc"))
        assertTrue(preferences.values.containsKey("abc"))
        assertTrue(store.save(emptyList<Any>()))
        assertEquals("keep", BridgeLocalStorage(preferences).get("def"))
    }

    @Test fun missingKeysReturnEmptyStringsAndWriteFailuresAreReported() {
        val preferences = MemoryLocalStoragePreferences()
        val store = BridgeLocalStorage(preferences)
        assertEquals("", store.get("missing"))
        preferences.writable = false
        assertFalse(store.save(mapOf("key" to "abc", "value" to 123)))
        assertEquals("", store.get("abc"))
    }

    @Test fun duplicateKeysInBatchUseLastValueAndPreserveUnsubmittedKeys() {
        val store = BridgeLocalStorage(MemoryLocalStoragePreferences())
        assertTrue(store.save(mapOf("key" to "kept", "value" to "existing")))
        assertTrue(store.save(listOf(
            mapOf("key" to "abc", "value" to "first"),
            mapOf("key" to "def", "value" to "456"),
            mapOf("key" to "abc", "value" to "last"),
            mapOf("key" to "xxx", "value" to "000")
        )))
        assertEquals("last", store.get("abc"))
        assertEquals("456", store.get("def"))
        assertEquals("000", store.get("xxx"))
        assertEquals("existing", store.get("kept"))
    }

    @Test fun invalidEntriesRejectEntireBatchBeforeWriting() {
        val preferences = MemoryLocalStoragePreferences()
        val store = BridgeLocalStorage(preferences)
        val invalid: List<Any?> = listOf(null, 123, "abc", emptyMap<String, Any>(), mapOf("abc" to 123),
            mapOf("key" to 123, "value" to "bad"), listOf(mapOf("key" to "abc", "value" to "new"), null),
            listOf(mapOf("key" to "abc", "value" to "new"), mapOf("value" to "bad")))
        for (input in invalid) {
            assertThrows(IllegalArgumentException::class.java) { store.save(input) }
            assertTrue("无效批次不可部分写入", preferences.values.isEmpty())
        }
    }
}

private class MemoryLocalStoragePreferences : LocalStoragePreferences {
    val values = mutableMapOf<String, String>()
    var writable = true
    override fun get(key: String): String? = values[key]
    override fun putAll(values: Map<String, String>): Boolean {
        if (!writable) return false
        this.values.putAll(values)
        return true
    }
}
