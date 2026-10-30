package com.qishimeng.game.auth

import android.util.Log
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import com.google.gson.Gson
import com.mobile.auth.gatewayauth.PhoneNumberAuthHelper
import com.mobile.auth.gatewayauth.TokenResultListener
import com.mobile.auth.gatewayauth.model.TokenRet
import com.qishimeng.game.bridge.BridgeTestActivity
import com.qishimeng.game.config.InitConfigParser
import com.qishimeng.game.config.ParseResult
import com.qishimeng.game.network.ApiClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 显式启用才读取线上方案并检查 SDK 环境；不唤起授权页，不取手机号或执行登录兑换。 */
@RunWith(AndroidJUnit4::class)
class AliyunEnvironmentTest {
    @get:Rule val activityRule = ActivityTestRule(BridgeTestActivity::class.java)

    @Test fun sdkDependenciesAndAuthorizationActivitiesArePackaged() {
        for (name in listOf("com.nirvana.tools.core.ExecutorManager", "com.nirvana.tools.logger.UaidTracker")) {
            assertNotNull("SDK 缺少运行时依赖：$name", Class.forName(name))
        }
        val activity = activityRule.activity
        for (name in listOf("com.mobile.auth.gatewayauth.LoginAuthActivity", "com.mobile.auth.gatewayauth.PrivacyDialogActivity", "com.mobile.auth.gatewayauth.activity.AuthWebVeiwActivity")) {
            val info = activity.packageManager.getActivityInfo(ComponentName(activity.packageName, name), PackageManager.GET_META_DATA)
            assertFalse("SDK 内部页面不得导出", info.exported)
        }
    }

    @Test fun configuredSdkReportsEnvironmentResult() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("onekeyLiveEnv") == "true")
        val response = ApiClient().post(ApiClient.INIT_URL, "{}", GameInfo("", 92, "1.0.1", "", "android", ""), false)
        val parsed = InitConfigParser.parse(Gson().toJson(response))
        assertTrue(parsed is ParseResult.Success)
        val config = (parsed as ParseResult.Success).value
        assertTrue("初始化未开启一键登录", config.aliyunOnekeyOpen)
        assertFalse("初始化缺少方案密钥", config.aliyunSchemeSecret.isNullOrBlank())
        val done = CountDownLatch(1)
        var resultCode: String? = null
        var successfulCallback = false
        var helper: PhoneNumberAuthHelper? = null
        fun record(result: String, success: Boolean) {
            resultCode = runCatching { TokenRet.fromJson(result).code }.getOrNull()
            successfulCallback = success
            done.countDown()
        }
        try {
            instrumentation.runOnMainSync {
                helper = PhoneNumberAuthHelper.getInstance(activityRule.activity, object : TokenResultListener {
                    override fun onTokenSuccess(result: String) = record(result, true)
                    override fun onTokenFailed(result: String) = record(result, false)
                })
                assertNotNull("SDK 初始化失败，无法执行环境检查", helper)
                helper!!.apply {
                    reporter.setLoggerEnable(false)
                    setAuthSDKInfo(config.aliyunSchemeSecret)
                    checkEnvAvailable(PhoneNumberAuthHelper.SERVICE_TYPE_LOGIN)
                }
            }
            assertTrue("SDK 环境检查未在十秒内回调", done.await(10, TimeUnit.SECONDS))
            assertNotNull("SDK 未返回可解析的结果码", resultCode)
            // 只记录结果码与阶段，不输出 SDK 原文、手机号、密钥或 Token。
            Log.i("QsmOnekeyEnvironment", "checkEnvAvailable success=$successfulCallback code=$resultCode")
        } finally {
            instrumentation.runOnMainSync { helper?.setAuthListener(null) }
        }
    }
}
