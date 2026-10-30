package com.qishimeng.game.config

data class InitConfig(
    val bgImage: String?,
    val privacyAuto: Boolean,
    val aliyunOnekeyOpen: Boolean,
    val aliyunSchemeSecret: String?,
    val aliyunExchangeUrl: String?,
    val heartbeatIntervalSeconds: Int?,
    val heartbeatUrl: String?,
    val floatingBall: FloatingBallConfig?,
    val pages: Map<String, PageSpec>,
    val updateInfo: Map<String, Any?>,
    val jumpDomains: Set<String> = emptySet(),
    val aliyunScreenCode: Int = 1,
    val aliyunDialogWidthPercent: Int = 60,
    val aliyunDialogHeightPercent: Int = 75,
    val aliyunTitle: String = "手机快捷登录",
    val aliyunLoginText: String = "手机号一键登录",
    val aliyunOtherLoginText: String = "使用其他方式登录",
    val aliyunPrimaryColor: String = "#CC0000",
    val aliyunProtocols: List<Pair<String, String>> = emptyList(),
    val aliyunTotalTimeoutMillis: Int = 5_000
)

data class FloatingBallConfig(
    val show: Boolean,
    val imageDefault: String,
    val imageActive: String,
    val width: Int,
    val height: Int,
    val left: Int?,
    val right: Int?,
    val top: Int?,
    val bottom: Int?
)

data class PageSpec(
    val instance: String,
    val url: String,
    val width: LayoutValue,
    val height: LayoutValue,
    val left: LayoutValue,
    val top: LayoutValue
)

sealed interface LayoutValue {
    data class Pixels(val value: Int) : LayoutValue
    data class Percent(val value: Float) : LayoutValue
    data object Center : LayoutValue
}

sealed interface ParseResult<out T> {
    data class Success<T>(val value: T) : ParseResult<T>
    data class Failure(val reason: String) : ParseResult<Nothing>
}
