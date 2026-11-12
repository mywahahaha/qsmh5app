package com.qishimeng.game.bridge

import com.qishimeng.game.auth.AccountTokenStore
import com.qishimeng.game.auth.GameInfo
import com.qishimeng.game.auth.OneKeyLoginOutcome
import com.qishimeng.game.auth.OnekeyConfig
import com.qishimeng.game.auth.OnekeyLoginService
import com.qishimeng.game.device.WelfarePageInfo
import com.qishimeng.game.privacy.PrivacyConsentStore
import com.qishimeng.game.role.GameRoleStore
import com.qishimeng.game.web.WebContainer

class BridgeActions(
    private val privacyStore: PrivacyConsentStore,
    private val tokenStore: AccountTokenStore,
    private val gameInfo: () -> GameInfo,
    private val roleStore: GameRoleStore,
    private val localStorage: BridgeLocalStorage,
    private val externalApps: ExternalApps,
    private val webContainer: WebContainer,
    private val onekeyLoginService: OnekeyLoginService,
    private val onekeyConfig: () -> OnekeyConfig,
    private val setFloatingBall: (Boolean, Boolean) -> Boolean,
    private val currentInstance: String,
    private val onLoginSuccess: (String) -> Unit,
    private val onPrivacyAgreed: () -> Unit,
    private val onLogout: () -> Unit,
    private val onPrivacyRejected: () -> Unit,
    private val onPhoneLoginFallback: () -> Unit,
    private val changeScreenMode: (Any?) -> Boolean,
    private val openMiniWindow: (String) -> Boolean = { webContainer.openInstance(it) != null },
    private val cancelPendingWelfareOpen: () -> Unit = {},
    private val getWelfareInfo: () -> WelfarePageInfo? = { null },
    private val changeGameUrl: (String, Boolean) -> Boolean = webContainer::changeGameUrl,
    private val closeWelfarePageAction: (Any?, Boolean) -> Boolean = { mode, loadPendingGameUrl ->
        val pendingUrl = if (loadPendingGameUrl) webContainer.takePendingGameUrl()
        else null.also { webContainer.clearPendingGameUrl() }
        val closed = closeWelfarePage(
            mode,
            close = { webContainer.close("web_welfare") },
            changeScreenMode = changeScreenMode
        )
        pendingUrl?.let { webContainer.changeGameUrl(it) }
        closed
    }
) {
    // 第二个参数由 JS 执行完成后调用，避免先销毁登录 WebView 导致成功回调丢失。
    fun dispatch(method: String, args: Any?, completion: (BridgeResult, () -> Unit) -> Unit) {
        when (method) {
            "requestPhoneLogin" -> requestPhoneLogin(completion)
            else -> completion(dispatchSync(method, args), {})
        }
    }

    private fun requestPhoneLogin(completion: (BridgeResult, () -> Unit) -> Unit) {
        val lock = Any()
        val loginWindow = webContainer.get("web_login")
        var presented = false
        var delivered = false
        var pendingOutcome: OneKeyLoginOutcome? = null
        fun finishLogin(outcome: OneKeyLoginOutcome) {
            if (outcome is OneKeyLoginOutcome.Success) onLoginSuccess(outcome.accountToken)
            else onPhoneLoginFallback()
        }
        onekeyLoginService.login(onekeyConfig(), privacyStore.isAgreed(), onPresented = {
            synchronized(lock) {
                presented = true
                completion(BridgeResult(0, "唤起成功", true)) {
                    synchronized(lock) {
                        if (!delivered) {
                            if (loginWindow != null && webContainer.get("web_login") === loginWindow) webContainer.close("web_login")
                            delivered = true
                            pendingOutcome?.let(::finishLogin)
                            pendingOutcome = null
                        }
                    }
                }
            }
        }) { outcome ->
            synchronized(lock) {
                if (presented) {
                    // 展示后的结果只驱动原生登录流程，不再回调已结束的 Bridge 请求。
                    if (delivered) finishLogin(outcome) else pendingOutcome = outcome
                } else {
                    val code = if (outcome == OneKeyLoginOutcome.Disabled) 3003 else 3001
                    val message = if (code == 3003) "一键登录未开启" else "一键登录唤起失败"
                    completion(BridgeResult(code, message, null), {})
                }
            }
        }
    }

    fun dispatchSync(method: String, arguments: Any?): BridgeResult = try {
        @Suppress("UNCHECKED_CAST")
        val args = arguments as? Map<String, Any?> ?: emptyMap()
        if (shouldCancelPendingWelfareOpen(method, args, currentInstance)) cancelPendingWelfareOpen()
        when (method) {
            "agreePrivacyPolicy" -> {
                privacyStore.agree()
                // 同意成功回调前完成设备码生成和保存，后续 H5 与原生请求读取同一个值。
                gameInfo()
                onPrivacyAgreed()
                BridgeResult(0, "ok", emptyMap<String, Any>())
            }
            "rejectPrivacyPolicy" -> {
                privacyStore.reject()
                onPrivacyRejected()
                BridgeResult(0, "ok", emptyMap<String, Any>())
            }
            "notifyLoginSuccess" -> {
                val token = args.string("accountToken")
                require(token.isNotBlank()) { "accountToken 不能为空" }
                tokenStore.token = token
                onLoginSuccess(token)
                BridgeResult(0, "ok", true)
            }
            "getAccountToken" -> BridgeResult(0, "ok", mapOf("accountToken" to tokenStore.token.orEmpty()))
            "getGameInfo" -> BridgeResult(0, "ok", gameInfo().forBridge(privacyStore.isAgreed()))
            "getWelfareInfo" -> BridgeResult(0, "ok", welfareInfoForBridge(currentInstance, getWelfareInfo()))
            "changeScreenmode" -> BridgeResult(0, "ok", changeScreenMode(args["mode"]))
            "saveGameRole" -> BridgeResult(0, "ok", roleStore.save(args))
            "saveLocalStorage" -> BridgeResult(0, "ok", localStorage.save(arguments))
            "getLocalStorage" -> BridgeResult(0, "ok", localStorage.get(args.string("key")))
            "isAppInstalled" -> BridgeResult(0, "ok", externalApps.isInstalled(args["packageName"]))
            "openApp" -> BridgeResult(0, "ok", externalApps.open(args["packageName"]))
            "getGameRole" -> {
                val gameId = args.intOrNull("gameId")
                BridgeResult(0, "ok", gameId?.let(roleStore::get) ?: false)
            }
            "setFloatingBall" -> BridgeResult(0, "ok", setFloatingBall(args.boolean("show"), args.boolean("active")))
            "openMiniWindow" -> BridgeResult(0, "ok", openMiniWindow(args.string("page")))
            "closeMiniWindow" -> BridgeResult(0, "ok", closeWindow(args.string("page")))
            "closeWelfarepage" -> BridgeResult(0, "ok", closeWelfarePageAction(
                args["mode"],
                welfareCloseLoadsPendingGameUrl(args["loadPendingGameUrl"])
            ))
            "changeGameUrl" -> BridgeResult(0, "ok", changeGameUrl(
                args.string("url"),
                args.optionalBoolean("waitCloseWelfarepage")
            ))
            "closePage" -> BridgeResult(0, "ok", closeWindow(currentInstance))
            "logout" -> {
                webContainer.clearPendingGameUrl()
                tokenStore.token = null
                onLogout()
                BridgeResult(0, "ok", true)
            }
            else -> BridgeResult(9999, "不支持的 Bridge 方法", null)
        }
    } catch (exception: IllegalArgumentException) {
        BridgeResult(1001, exception.message ?: "参数错误", errorData(method))
    } catch (_: Exception) {
        BridgeResult(9999, "操作失败", errorData(method))
    }

    private fun errorData(method: String): Any? = when (method) {
        "getLocalStorage" -> ""
        "isAppInstalled", "openApp" -> false
        else -> null
    }

    private fun Map<String, Any?>.string(name: String): String = this[name] as? String
        ?: throw IllegalArgumentException("$name 参数无效")

    private fun Map<String, Any?>.intOrNull(name: String): Int? {
        val number = this[name] as? Number ?: return null
        val integer = number.toLong()
        if (integer !in Int.MIN_VALUE..Int.MAX_VALUE || number.toDouble() != integer.toDouble()) return null
        return integer.toInt()
    }

    private fun Map<String, Any?>.boolean(name: String): Boolean = this[name] as? Boolean
        ?: throw IllegalArgumentException("$name 参数无效")

    private fun Map<String, Any?>.optionalBoolean(name: String): Boolean = when (val value = this[name]) {
        null -> false
        is Boolean -> value
        else -> throw IllegalArgumentException("$name 参数无效")
    }

    private fun closeWindow(instance: String): Boolean {
        if (instance == "web_welfare") webContainer.clearPendingGameUrl()
        return webContainer.close(instance)
    }

}

internal fun welfareCloseLoadsPendingGameUrl(value: Any?): Boolean = when (value) {
    null -> false
    is Boolean -> value
    else -> throw IllegalArgumentException("loadPendingGameUrl 参数无效")
}

internal fun welfareInfoForBridge(currentInstance: String, info: WelfarePageInfo?): Map<String, String> {
    require(currentInstance == "web_welfare" && info != null) { "当前页面无法获取大厅信息" }
    return mapOf(
        "entryMode" to info.entryMode.value,
        "currentMode" to info.currentMode.value
    )
}

internal fun closeWelfarePage(
    mode: Any?,
    close: () -> Boolean,
    changeScreenMode: (Any?) -> Boolean
): Boolean {
    val closed = close()
    if (mode == "horizontal" || mode == "vertical") changeScreenMode(mode)
    return closed
}

internal fun shouldCancelPendingWelfareOpen(
    method: String,
    args: Map<String, Any?>,
    currentInstance: String
): Boolean = when (method) {
    "closeWelfarepage", "logout" -> true
    "closeMiniWindow" -> args["page"] == "web_welfare"
    "closePage" -> currentInstance == "web_welfare"
    "changeScreenmode" -> args["mode"] == "vertical"
    else -> false
}
