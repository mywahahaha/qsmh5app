package com.qishimeng.game.auth

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.qishimeng.game.network.ApiClient
import com.qishimeng.game.network.ApiResponse
import com.qishimeng.game.network.GameHeaders
import com.qishimeng.game.network.HttpTransport
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 两次独立 instrumentation 间 force-stop，验证真实进程重启后的存储。默认跳过。 */
@RunWith(AndroidJUnit4::class)
class AccountTokenRestartTest {
    @Test fun rejectedTokenRemainsAbsentInNewProcess() {
        val phase = InstrumentationRegistry.getArguments().getString("tokenRestartPhase")
        assumeTrue(phase == "prepare" || phase == "verify")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val markers = context.getSharedPreferences("token_restart_test", 0)
        val store = EncryptedAccountTokenStore(context)
        val info = { GameInfo("", 92, "test", store.token.orEmpty(), "android", "") }
        if (phase == "prepare") {
            store.token = "restart-test-expired-token"
            // 确认旧值已真实落盘，后续失败响应的删除必须自行保证持久化。
            assertTrue(context.getSharedPreferences("account_token", 0).edit().commit())
            val api = ApiClient(HttpTransport { request ->
                assertEquals("POST", request.method)
                assertEquals(TokenLoginService.LOGIN_BY_TOKEN_URL, request.url)
                assertEquals("restart-test-expired-token", request.headers["Accountoken"])
                ApiResponse(200, """{"code":9,"msg":"rejected"}""")
            })
            assertEquals(TokenLoginOutcome.Fallback, TokenLoginService(store, api, info()).loginSavedToken(true))
            assertNull(store.token)
            val persistedFile = java.io.File(context.applicationInfo.dataDir, "shared_prefs/account_token.xml")
            assertFalse("返回失败结果前须完成 Token 磁盘删除", persistedFile.readText().contains("name=\"encrypted\""))
            assertTrue(markers.edit().putInt("prepared_pid", Process.myPid()).commit())
        } else {
            val preparedPid = markers.getInt("prepared_pid", -1)
            assertTrue("须先完成 prepare 阶段", preparedPid > 0)
            assertNotEquals("须在不同进程执行 verify", preparedPid, Process.myPid())
            assertNull("重启后不得恢复已被拒绝的 Token", store.token)
            assertEquals("", info().accountToken)
            assertEquals("", GameHeaders.toMap(info(), true)["Accountoken"])
            val noRequest = ApiClient(HttpTransport { error("重启后不得再次请求 Token 登录") })
            assertEquals(TokenLoginOutcome.Skipped, TokenLoginService(store, noRequest, info()).loginSavedToken(true))
            assertTrue(markers.edit().clear().commit())
        }
    }
}
