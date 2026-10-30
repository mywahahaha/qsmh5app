package com.qishimeng.game.auth

import com.qishimeng.game.network.ApiClient
import com.qishimeng.game.network.ApiResponse
import com.qishimeng.game.network.HttpTransport
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class TokenLoginServiceTest {
    @Test
    fun skipsWhenPrivacyIsNotAgreedOrTokenIsMissing() {
        val store = InMemoryAccountTokenStore("saved")
        assertEquals(TokenLoginOutcome.Skipped, service(store).loginSavedToken(false))
        assertEquals("saved", store.token)
        assertEquals(TokenLoginOutcome.Skipped, service(InMemoryAccountTokenStore(null)).loginSavedToken(true))
    }

    @Test
    fun savesReturnedTokenOnSuccessfulLogin() {
        val store = InMemoryAccountTokenStore("old")
        val service = service(store) { """{"code":0,"msg":"登录成功","data":{"token":"new"}}""" }

        assertEquals(TokenLoginOutcome.Success("new"), service.loginSavedToken(true))
        assertEquals("new", store.token)
    }

    @Test
    fun retainsTokenOnNetworkFailureButClearsAnyNonZeroBusinessCode() {
        val networkStore = InMemoryAccountTokenStore("keep")
        assertEquals(TokenLoginOutcome.Fallback, service(networkStore) { throw IOException("offline") }.loginSavedToken(true))
        assertEquals("keep", networkStore.token)

        val businessStore = InMemoryAccountTokenStore("keep")
        assertEquals(TokenLoginOutcome.Fallback, service(businessStore) { """{"code":9,"msg":"rejected"}""" }.loginSavedToken(true))
        assertEquals(null, businessStore.token)
    }

    @Test
    fun clearsTokenForAnyNonZeroBusinessCodeWithoutConfiguration() {
        for (code in listOf(-1, 1, 9, 40101, 500)) {
            val store = InMemoryAccountTokenStore("expired")
            val service = service(store) { """{"code":$code,"msg":"expired","data":{"token":"not-valid"}}""" }
            assertEquals(TokenLoginOutcome.Fallback, service.loginSavedToken(true))
            assertEquals(null, store.token)
            assertEquals("清理后再次调用不能重发 Token 登录", TokenLoginOutcome.Skipped, service.loginSavedToken(true))
        }
    }

    @Test
    fun doesNotReportSuccessOrOverwriteTokenWhenSuccessPayloadHasNoUsableToken() {
        for (body in listOf("""{"code":0}""", """{"code":0,"data":{"token":""}}""", """{"code":0,"data":{"token":123}}""")) {
            val store = InMemoryAccountTokenStore("old")
            assertEquals(TokenLoginOutcome.Fallback, service(store) { body }.loginSavedToken(true))
            assertEquals("old", store.token)
        }
    }

    private fun service(
        store: InMemoryAccountTokenStore,
        body: () -> String = { """{"code":8,"msg":"fail"}""" }
    ): TokenLoginService {
        val expectedToken = store.token
        return TokenLoginService(store, ApiClient(HttpTransport { request ->
            assertEquals("POST", request.method)
            assertEquals("/h5_app/login_by_token", java.net.URI(request.url).path)
            assertEquals(expectedToken, request.headers["Accountoken"])
            ApiResponse(200, body())
        }), GameInfo("", 1, "1", "", "android", ""))
    }
}
