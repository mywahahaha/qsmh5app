package com.qishimeng.game.network

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.qishimeng.game.config.LayoutValue
import com.qishimeng.game.config.PageSpec

object HeartbeatResponseParser {
    fun parse(json: String): HeartbeatResponse {
        val root = JsonParser.parseString(json).asJsonObject
        val code = root.requiredInt("code")
        if (code != 0) return HeartbeatResponse(null, null, code)
        val element = root.get("data")
        if (element == null || element.isJsonNull || (element.isJsonArray && element.asJsonArray.size() == 0)) {
            return HeartbeatResponse(null, null, code)
        }
        require(element.isJsonObject) { "字段 data 类型错误" }
        val data = element.asJsonObject
        val popupPages = data.get("popup_pages")?.let { pages ->
            if (!pages.isJsonArray) throw IllegalArgumentException("字段 popup_pages 类型错误")
            pages.asJsonArray.map { it.asJsonObject.toPageSpec() }
        }
        val floating = data.get("floating_ball")?.let { element ->
            if (!element.isJsonObject) throw IllegalArgumentException("字段 floating_ball 类型错误")
            val obj = element.asJsonObject
            FloatingBallHeartbeat(
                show = obj.requiredBoolean("show"),
                active = obj.requiredBoolean("active")
            )
        }
        return HeartbeatResponse(popupPages, floating, code)
    }

    private fun JsonObject.toPageSpec(): PageSpec = PageSpec(
        instance = requiredString("instance"),
        url = requiredString("url"),
        width = requiredLayout("width"),
        height = requiredLayout("height"),
        left = requiredLayout("left"),
        top = requiredLayout("top")
    )

    private fun JsonObject.requiredString(name: String): String = get(name)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString
        ?: throw IllegalArgumentException("字段 $name 缺失或类型错误")

    private fun JsonObject.requiredInt(name: String): Int = get(name)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
        ?.asBigDecimal?.intValueExact()
        ?: throw IllegalArgumentException("字段 $name 缺失或类型错误")

    private fun JsonObject.requiredBoolean(name: String): Boolean = get(name)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
        ?.asBoolean
        ?: throw IllegalArgumentException("字段 $name 缺失或类型错误")

    private fun JsonObject.requiredLayout(name: String): LayoutValue {
        val element: JsonElement = get(name) ?: throw IllegalArgumentException("字段 $name 缺失")
        if (!element.isJsonPrimitive) throw IllegalArgumentException("字段 $name 类型错误")
        val primitive = element.asJsonPrimitive
        if (primitive.isNumber) return LayoutValue.Pixels(primitive.asInt)
        if (!primitive.isString) throw IllegalArgumentException("字段 $name 类型错误")
        val value = primitive.asString
        if (value == "center") return LayoutValue.Center
        if (value.endsWith("%")) return LayoutValue.Percent(value.dropLast(1).toFloat())
        return value.toIntOrNull()?.let(LayoutValue::Pixels)
            ?: throw IllegalArgumentException("字段 $name 格式错误")
    }
}
