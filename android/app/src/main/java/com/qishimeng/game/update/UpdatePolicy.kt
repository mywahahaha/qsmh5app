package com.qishimeng.game.update

import java.net.URI

sealed interface UpdateDimension {
    data class Dp(val value: Float) : UpdateDimension
    data class Percent(val value: Float) : UpdateDimension
}

data class UpdateInfo(
    val title: String,
    val description: String,
    val version: String,
    val downloadUrl: String,
    val force: Boolean,
    val width: UpdateDimension = UpdateDimension.Dp(50f),
    val height: UpdateDimension? = null,
    val popup: Boolean = false,
    val time: String = ""
)

sealed interface UpdateDecision {
    data object Continue : UpdateDecision
    data class Offer(val info: UpdateInfo) : UpdateDecision
    data class Abort(val message: String) : UpdateDecision
}

object UpdatePolicy {
    fun evaluate(fields: Map<String, Any?>): UpdateDecision {
        if (fields.isEmpty()) return UpdateDecision.Continue
        return try {
            if (!fields.flag("open")) return UpdateDecision.Continue
            val width = dimension(fields["width"], "width")
            val heightValue = fields["height"]
            val height = if (heightValue is String && heightValue.trim() == "auto") null
            else dimension(heightValue, "height")
            val popup = fields.flag("popup")
            val force = fields.flag("force")
            val url = fields.text("url").trim()
            require(isHttpUrl(url)) { "更新地址无效" }
            UpdateDecision.Offer(UpdateInfo(
                fields.text("title"), fields.text("desc"), fields.text("version"), url, force,
                width, height, popup, fields.text("time")
            ))
        } catch (error: IllegalArgumentException) {
            UpdateDecision.Abort(error.message ?: "更新配置无效")
        }
    }

    fun isHttpUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null
    }.getOrDefault(false)

    private fun Map<String, Any?>.flag(name: String): Boolean {
        val number = (get(name) as? Number)?.toDouble()
        require(number == 0.0 || number == 1.0) { "$name 字段无效" }
        return number == 1.0
    }

    private fun Map<String, Any?>.text(name: String): String {
        require(get(name) is String) { "$name 字段无效" }
        return get(name) as String
    }

    private fun dimension(value: Any?, name: String): UpdateDimension {
        if (value is Number) return UpdateDimension.Dp(value.toFloat().also { requireDp(it, name) })
        require(value is String) { "$name 尺寸无效" }
        val text = value.trim()
        val isPercent = text.endsWith("%")
        val number = text.removeSuffix("%").trim().toFloatOrNull()
        require(number != null && number.isFinite() && number > 0f) { "$name 尺寸无效" }
        if (isPercent) require(number <= 100f) { "$name 百分比无效" }
        return if (isPercent) UpdateDimension.Percent(number) else UpdateDimension.Dp(number)
    }

    private fun requireDp(value: Float, name: String) {
        require(value.isFinite() && value > 0f) { "$name 尺寸无效" }
    }
}
