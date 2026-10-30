package com.qishimeng.game.config

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Locale

object InitConfigParser {
    fun parse(json: String): ParseResult<InitConfig> {
        return try {
        val root = JsonParser.parseString(json).asJsonObject
        val code = root.requiredInt("code")
        if (code != 0) return ParseResult.Failure(
            root.optionalString("msg")?.takeIf(String::isNotBlank) ?: "初始化接口返回 code=$code"
        )
        val data = root.requiredObject("data")
        val privacyAuto = data.optionalBoolean("privacyAuto") ?: false
        val aliyun = data.optionalObject("aliyunOnekeyConfig")
        val aliyunConfig = aliyun?.optionalObject("config")
        val popup = data.optionalObject("popupInterval")
        val floatball = data.optionalObject("floatball")
        val pagesObject = data.optionalObject("h5Pages")
        val ui = aliyunConfig?.optionalObject("ui")
        val protocols = aliyunConfig?.optionalArray("app_protocols")?.map { element ->
            val protocol = element.asJsonObject
            protocol.requiredString("name") to protocol.requiredString("url")
        }.orEmpty()
        val pages = pagesObject?.entrySet()?.associate { (key, value) ->
            key to value.asJsonObject.toPageSpec()
        }.orEmpty()

        ParseResult.Success(
            InitConfig(
                bgImage = data.optionalString("bgImage")?.trim()?.takeIf(String::isNotEmpty),
                privacyAuto = privacyAuto,
                aliyunOnekeyOpen = aliyun?.optionalInt("open") == 1,
                aliyunSchemeSecret = aliyunConfig?.optionalString("scheme_secret"),
                aliyunExchangeUrl = aliyunConfig?.optionalString("exchange_url"),
                heartbeatIntervalSeconds = popup?.optionalInt("interval"),
                heartbeatUrl = popup?.optionalString("url"),
                floatingBall = floatball?.toFloatingBallConfig(),
                pages = pages,
                // 更新配置错误只终止更新，不得把成功初始化变为失败。
                updateInfo = data.get("updateInfo")?.let { update ->
                    when {
                        update.isJsonNull -> emptyMap()
                        update.isJsonObject -> update.asJsonObject.entrySet().associate { it.key to it.value.toSafeValue() }
                        else -> mapOf("open" to null)
                    }
                }.orEmpty(),
                jumpDomains = data.optionalArray("jumpDomain")?.mapNotNull { domain ->
                    val value = domain.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                        ?: throw IllegalArgumentException("字段 jumpDomain 类型错误")
                    value.trim()
                        .lowercase(Locale.ROOT)
                        .takeIf(String::isNotEmpty)
                }?.toSet().orEmpty(),
                aliyunScreenCode = aliyunConfig?.optionalInt("screen_code") ?: 1,
                aliyunDialogWidthPercent = ui?.optionalInt("dialog_width_percent") ?: 60,
                aliyunDialogHeightPercent = ui?.optionalInt("dialog_height_percent") ?: 75,
                aliyunTitle = ui?.optionalString("title") ?: "手机快捷登录",
                aliyunLoginText = ui?.optionalString("login_text") ?: "手机号一键登录",
                aliyunOtherLoginText = ui?.optionalString("other_login_text") ?: "使用其他方式登录",
                aliyunPrimaryColor = ui?.optionalString("primary_color") ?: "#CC0000",
                aliyunProtocols = protocols,
                aliyunTotalTimeoutMillis = aliyunConfig?.optionalInt("launch_total_timeout_ms") ?: 5_000
            )
        )
        } catch (exception: Exception) {
            ParseResult.Failure(exception.message ?: "初始化配置格式无效")
        }
    }

    private fun JsonObject.toPageSpec(): PageSpec = PageSpec(
        instance = requiredString("instance"),
        url = requiredString("url"),
        width = requiredLayoutValue("width"),
        height = requiredLayoutValue("height"),
        left = requiredLayoutValue("left"),
        top = requiredLayoutValue("top")
    )

    private fun JsonObject.toFloatingBallConfig(): FloatingBallConfig {
        val width = requiredInt("width")
        val height = requiredInt("height")
        if (width <= 0 || height <= 0) throw IllegalArgumentException("floatball 尺寸无效")
        return FloatingBallConfig(
            show = optionalBoolean("show") ?: false,
            imageDefault = requiredString("image_default"),
            imageActive = requiredString("image_active"),
            width = width,
            height = height,
            left = optionalInt("left"),
            right = optionalInt("right"),
            top = optionalInt("top"),
            bottom = optionalInt("bottom")
        )
    }

    private fun JsonObject.requiredObject(name: String): JsonObject = get(name)?.takeIf { it.isJsonObject }?.asJsonObject
        ?: throw IllegalArgumentException("字段 $name 缺失或类型错误")

    private fun JsonObject.optionalObject(name: String): JsonObject? {
        val element = get(name) ?: return null
        if (!element.isJsonObject) throw IllegalArgumentException("字段 $name 类型错误")
        return element.asJsonObject
    }

    private fun JsonObject.optionalArray(name: String): List<JsonElement>? {
        val element = get(name) ?: return null
        if (!element.isJsonArray) throw IllegalArgumentException("字段 $name 类型错误")
        return element.asJsonArray.toList()
    }

    private fun JsonObject.requiredString(name: String): String = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        ?: throw IllegalArgumentException("字段 $name 缺失或类型错误")

    private fun JsonObject.optionalString(name: String): String? {
        val element = get(name) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) throw IllegalArgumentException("字段 $name 类型错误")
        return element.asString
    }

    private fun JsonObject.requiredInt(name: String): Int = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
        ?: throw IllegalArgumentException("字段 $name 缺失或类型错误")

    private fun JsonObject.optionalInt(name: String): Int? {
        val element = get(name) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) throw IllegalArgumentException("字段 $name 类型错误")
        return element.asInt
    }

    private fun JsonObject.optionalBoolean(name: String): Boolean? {
        val element = get(name) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isBoolean) throw IllegalArgumentException("字段 $name 类型错误")
        return element.asBoolean
    }

    private fun JsonObject.requiredLayoutValue(name: String): LayoutValue {
        val element = get(name) ?: throw IllegalArgumentException("字段 $name 缺失")
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

    private fun JsonElement.toSafeValue(): Any? = when {
        isJsonNull -> null
        isJsonObject -> asJsonObject.entrySet().associate { it.key to it.value.toSafeValue() }
        isJsonArray -> asJsonArray.map { it.toSafeValue() }
        asJsonPrimitive.isBoolean -> asBoolean
        asJsonPrimitive.isNumber -> asNumber
        else -> asString
    }
}
