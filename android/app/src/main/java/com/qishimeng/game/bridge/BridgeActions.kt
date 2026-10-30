package com.qishimeng.game.bridge

import com.qishimeng.game.auth.AccountTokenStore
import com.qishimeng.game.auth.GameInfo
import com.qishimeng.game.auth.OneKeyLoginOutcome
import com.qishimeng.game.auth.OnekeyConfig
import com.qishimeng.game.auth.OnekeyLoginService
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
    private val onPhoneLoginFallback: () -> Unit
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
            "openMiniWindow" -> BridgeResult(0, "ok", webContainer.openInstance(args.string("page")) != null)
            "closeMiniWindow" -> BridgeResult(0, "ok", webContainer.close(args.string("page")))
            "changeGameUrl" -> BridgeResult(0, "ok", webContainer.changeGameUrl(args.string("url")))
            "closePage" -> BridgeResult(0, "ok", webContainer.close(currentInstance))
            "logout" -> {
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

}
