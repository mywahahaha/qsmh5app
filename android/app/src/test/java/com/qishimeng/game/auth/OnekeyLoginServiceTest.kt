package com.qishimeng.game.auth

import com.qishimeng.game.network.ApiClient
import com.qishimeng.game.network.ApiResponse
import com.qishimeng.game.network.HttpTransport
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.Executor

class OnekeyLoginServiceTest {
    @Test
    fun sdkSecretCanAuthenticateExchangeAndStoreAccountToken() {
        val store = InMemoryAccountTokenStore(null)
        var capturedUrl = ""
        var capturedBody = ""
        val service = OnekeyLoginService(
            store,
            OnekeyAuthGateway { _, completion -> completion(OnekeyAuthResult.Success("auth-secret")) },
            ApiClient(HttpTransport { request ->
                capturedUrl = request.url
                capturedBody = request.body
                assertEquals("", request.headers.getValue("Accountoken"))
                ApiResponse(200, """{"code":0,"msg":"登录成功","data":{"token":"account-token"}}""")
            }),
            GameInfo("", 266, "1.0", "", "android", ""),
            Executor(Runnable::run)
        )

        var outcome: OneKeyLoginOutcome? = null
        service.login(OnekeyConfig(true, "secret", "https://box.74meng.com/exchange"), true) { outcome = it }

        assertEquals(OneKeyLoginOutcome.Success("account-token"), outcome)
        assertEquals("https://box.74meng.com/exchange", capturedUrl)
        assertEquals("auth-secret", com.google.gson.JsonParser.parseString(capturedBody).asJsonObject.get("auth_token").asString)
        assertEquals("account-token", store.token)
    }

    @Test
    fun disabledCancelledAndFailedExchangeFallBackWithoutBlocking() {
        val store = InMemoryAccountTokenStore("keep")
        val service = OnekeyLoginService(store, OnekeyAuthGateway { _, completion -> completion(OnekeyAuthResult.Cancelled) }, ApiClient(HttpTransport { error("must not run") }), GameInfo("", 1, "1", "", "android", ""), Executor(Runnable::run))
        assertEquals(OneKeyLoginOutcome.Disabled, service.loginResult(OnekeyConfig(false, "", ""), true))
        assertEquals(OneKeyLoginOutcome.Fallback, service.loginResult(OnekeyConfig(true, "secret", "https://box.74meng.com/exchange"), false))
        assertEquals(OneKeyLoginOutcome.Fallback, service.loginResult(OnekeyConfig(true, "secret", "https://box.74meng.com/exchange"), true))
        assertEquals("keep", store.token)
    }

    private fun OnekeyLoginService.loginResult(config: OnekeyConfig, privacyAgreed: Boolean): OneKeyLoginOutcome? {
        var result: OneKeyLoginOutcome? = null
        login(config, privacyAgreed) { result = it }
        return result
    }
}
