package com.qishimeng.game.auth

import com.qishimeng.game.network.ApiClient

sealed interface TokenLoginOutcome {
    data class Success(val accountToken: String) : TokenLoginOutcome
    data object Skipped : TokenLoginOutcome
    data object Fallback : TokenLoginOutcome
}

class TokenLoginService(
    private val tokenStore: AccountTokenStore,
    private val apiClient: ApiClient,
    private val gameInfo: GameInfo
) {
    fun loginSavedToken(privacyAgreed: Boolean): TokenLoginOutcome {
        if (!privacyAgreed) return TokenLoginOutcome.Skipped
        val token = tokenStore.token?.takeIf(String::isNotBlank) ?: return TokenLoginOutcome.Skipped
        return try {
            val response = apiClient.post(
                LOGIN_BY_TOKEN_URL,
                "{}",
                gameInfo.copy(accountToken = token),
                privacyAgreed = true
            )
            if (response.code == 0) {
                val newToken = response.data?.asJsonObject?.get("token")
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                    ?.asString
                    ?.takeIf(String::isNotBlank)
                    ?: return TokenLoginOutcome.Fallback
                tokenStore.token = newToken
                TokenLoginOutcome.Success(newToken)
            } else {
                // Token 登录接口的所有非 0 业务码均表示本次 Token 被拒绝。
                // 先删除唯一持久化来源，再允许后续登录、Bridge 与请求重新读取。
                tokenStore.token = null
                TokenLoginOutcome.Fallback
            }
        } catch (_: Exception) {
            TokenLoginOutcome.Fallback
        }
    }

    companion object {
        const val LOGIN_BY_TOKEN_URL = "https://box.74meng.com/h5_app/login_by_token"
    }
}
