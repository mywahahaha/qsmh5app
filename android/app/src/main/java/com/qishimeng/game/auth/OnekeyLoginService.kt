package com.qishimeng.game.auth

import com.google.gson.Gson
import com.qishimeng.game.network.ApiClient
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class OnekeyConfig(
    val open: Boolean,
    val schemeSecret: String,
    val exchangeUrl: String,
    val screenCode: Int = 1,
    val dialogWidthPercent: Int = 60,
    val dialogHeightPercent: Int = 75,
    val title: String = "手机快捷登录",
    val loginText: String = "手机号一键登录",
    val otherLoginText: String = "使用其他方式登录",
    val primaryColor: String = "#CC0000",
    val protocols: List<Pair<String, String>> = emptyList(),
    val totalTimeoutMillis: Int = 5_000
)

sealed interface OnekeyAuthResult {
    data object Presented : OnekeyAuthResult
    data class Success(val authToken: String) : OnekeyAuthResult
    data object Cancelled : OnekeyAuthResult
    data object Failed : OnekeyAuthResult
}

fun interface OnekeyAuthGateway {
    fun authenticate(config: OnekeyConfig, completion: (OnekeyAuthResult) -> Unit)
}

sealed interface OneKeyLoginOutcome {
    data class Success(val accountToken: String) : OneKeyLoginOutcome
    data object Disabled : OneKeyLoginOutcome
    data object Fallback : OneKeyLoginOutcome
}

class OnekeyLoginService(
    private val tokenStore: AccountTokenStore,
    private val authGateway: OnekeyAuthGateway,
    private val apiClient: ApiClient,
    private val gameInfo: GameInfo,
    private val executor: Executor = Executors.newSingleThreadExecutor()
) {
    fun login(config: OnekeyConfig, privacyAgreed: Boolean, onPresented: () -> Unit = {}, completion: (OneKeyLoginOutcome) -> Unit) {
        if (!config.open) {
            completion(OneKeyLoginOutcome.Disabled)
            return
        }
        if (!privacyAgreed || config.schemeSecret.isBlank() || config.exchangeUrl.isBlank()) {
            completion(OneKeyLoginOutcome.Fallback)
            return
        }
        val presented = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        try {
            authGateway.authenticate(config) { auth ->
                when (auth) {
                    OnekeyAuthResult.Presented -> if (!finished.get() && presented.compareAndSet(false, true)) onPresented()
                    is OnekeyAuthResult.Success -> if (finished.compareAndSet(false, true)) executor.execute {
                        completion(exchange(auth.authToken, config.exchangeUrl))
                    }
                    OnekeyAuthResult.Cancelled, OnekeyAuthResult.Failed -> if (finished.compareAndSet(false, true)) completion(OneKeyLoginOutcome.Fallback)
                }
            }
        } catch (_: Exception) {
            if (finished.compareAndSet(false, true)) completion(OneKeyLoginOutcome.Fallback)
        }
    }

    private fun exchange(authToken: String, url: String): OneKeyLoginOutcome {
        return try {
            val body = Gson().toJson(mapOf("auth_token" to authToken))
            val response = apiClient.post(url, body, gameInfo.copy(accountToken = tokenStore.token.orEmpty()), true)
            val accountToken = response.data?.asJsonObject?.get("token")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString
                ?.takeIf(String::isNotBlank)
            if (response.code != 0 || accountToken == null) return OneKeyLoginOutcome.Fallback
            tokenStore.token = accountToken
            OneKeyLoginOutcome.Success(accountToken)
        } catch (_: Exception) {
            OneKeyLoginOutcome.Fallback
        }
    }
}
