package com.qishimeng.game.bridge

import java.math.BigDecimal

interface LocalStoragePreferences {
    fun get(key: String): String?
    fun putAll(values: Map<String, String>): Boolean
}

class BridgeLocalStorage(private val preferences: LocalStoragePreferences) {
    fun save(arguments: Any?): Boolean {
        val entries = when (arguments) {
            is Map<*, *> -> listOf(arguments)
            is List<*> -> arguments
            else -> throw IllegalArgumentException("参数必须为键值对象或键值对象数组")
        }
        val values = linkedMapOf<String, String>()
        entries.forEach { entry ->
            require(entry is Map<*, *>) { "每项必须为键值对象" }
            val key = entry["key"] as? String ?: throw IllegalArgumentException("key 参数无效")
            values[key] = when (val value = entry["value"]) {
                is String -> value
                // 规范数字中的小数零，123.0 需存成 "123"。
                is Number -> BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
                else -> ""
            }
        }
        // 全批校验完再写入，重复 key 使用最后一项的值。
        return preferences.putAll(values)
    }

    fun get(key: String): String = preferences.get(key).orEmpty()
}
