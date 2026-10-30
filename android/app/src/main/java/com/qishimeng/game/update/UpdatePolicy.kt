package com.qishimeng.game.update

import java.net.URI

data class UpdateInfo(
    val title: String,
    val description: String,
    val version: String,
    val downloadUrl: String,
    val force: Boolean,
    val widthPercent: Float = 50f,
    val heightPercent: Float? = null,
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
            val width = percent(fields["width"], "width")
            val height = if (fields["height"] == "auto") null else percent(fields["height"], "height")
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

    private fun percent(value: Any?, name: String): Float {
        val result = when (value) {
            is Number -> value.toFloat()
            is String -> value.trim().removeSuffix("%").toFloatOrNull()
            else -> null
        }
        require(result != null && result.isFinite() && result > 0f && result <= 100f) { "$name 百分比无效" }
        return result
    }
}
