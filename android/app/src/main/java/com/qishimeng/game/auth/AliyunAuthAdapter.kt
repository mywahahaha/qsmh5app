package com.qishimeng.game.auth

import android.app.Activity
import com.mobile.auth.gatewayauth.AuthUIConfig
import com.mobile.auth.gatewayauth.PhoneNumberAuthHelper
import com.mobile.auth.gatewayauth.ResultCode
import com.mobile.auth.gatewayauth.TokenResultListener
import com.mobile.auth.gatewayauth.model.TokenRet
import java.util.concurrent.atomic.AtomicBoolean

class AliyunAuthAdapter(
    private val activityProvider: () -> Activity?
) : OnekeyAuthGateway {
    override fun authenticate(config: OnekeyConfig, completion: (OnekeyAuthResult) -> Unit) {
        val activity = activityProvider()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            completion(OnekeyAuthResult.Failed)
            return
        }
        SESSION.start(onBusy = { completion(OnekeyAuthResult.Failed) }) { release ->
            authenticate(activity, config, release, completion)
        }
    }

    private fun authenticate(activity: Activity, config: OnekeyConfig, release: () -> Unit, completion: (OnekeyAuthResult) -> Unit) {
        var helper: PhoneNumberAuthHelper? = null
        val completed = AtomicBoolean(false)
        val presented = AtomicBoolean(false)
        fun finish(result: OnekeyAuthResult) {
            activity.runOnUiThread {
                // 与展示事件一起按主线程队列处理，避免后台终态抢先吞掉已排队的展示通知。
                if (!completed.compareAndSet(false, true)) return@runOnUiThread
                try {
                    helper?.setAuthListener(null)
                    helper?.setUIClickListener(null)
                    helper?.quitLoginPage()
                    helper?.removeAuthRegisterXmlConfig()
                } finally {
                    release()
                }
                completion(result)
            }
        }
        try {
            val listener = object : TokenResultListener {
                override fun onTokenSuccess(result: String) {
                    val token = runCatching { TokenRet.fromJson(result) }.getOrNull()
                    if (token?.code == ResultCode.CODE_START_AUTHPAGE_SUCCESS) {
                        activity.runOnUiThread {
                            if (!completed.get() && presented.compareAndSet(false, true)) completion(OnekeyAuthResult.Presented)
                        }
                    } else if (token?.code == ResultCode.CODE_GET_TOKEN_SUCCESS && !token.token.isNullOrBlank()) {
                        finish(OnekeyAuthResult.Success(token.token))
                    } else if (token?.code == ResultCode.CODE_ERROR_USER_CANCEL ||
                        token?.code == ResultCode.CODE_ERROR_USER_CONTROL_CANCEL_BYBTN ||
                        token?.code == ResultCode.CODE_ERROR_USER_CONTROL_CANCEL_BYKEY ||
                        token?.code == ResultCode.CODE_ERROR_USER_SWITCH
                    ) {
                        finish(OnekeyAuthResult.Cancelled)
                    }
                }

                override fun onTokenFailed(result: String) {
                    val token = runCatching { TokenRet.fromJson(result) }.getOrNull()
                    val outcome = if (token?.code == ResultCode.CODE_ERROR_USER_CANCEL ||
                        token?.code == ResultCode.CODE_ERROR_USER_CONTROL_CANCEL_BYBTN ||
                        token?.code == ResultCode.CODE_ERROR_USER_CONTROL_CANCEL_BYKEY ||
                        token?.code == ResultCode.CODE_ERROR_USER_SWITCH
                    ) OnekeyAuthResult.Cancelled else OnekeyAuthResult.Failed
                    finish(outcome)
                }
            }

            val authHelper = PhoneNumberAuthHelper.getInstance(activity, listener)
            helper = authHelper
            authHelper.setAuthSDKInfo(config.schemeSecret)
            authHelper.userControlAuthPageCancel()
            authHelper.keepAuthPageLandscapeFullSreen(config.screenCode == SCREEN_LANDSCAPE)
            OnekeyAuthUi.configureHeader(authHelper) { finish(OnekeyAuthResult.Cancelled) }
            authHelper.setAuthUIConfig(buildUiConfig(activity, config))
            authHelper.checkEnvAvailable(PhoneNumberAuthHelper.SERVICE_TYPE_LOGIN)
            authHelper.getLoginToken(activity, config.totalTimeoutMillis)
        } catch (_: Exception) {
            finish(OnekeyAuthResult.Failed)
        }
    }

    private fun buildUiConfig(activity: Activity, config: OnekeyConfig): AuthUIConfig = OnekeyAuthUi.build(activity, config)

    companion object {
        private val SESSION = AuthSessionGate()
        const val SCREEN_LANDSCAPE = 1
        const val SCREEN_PORTRAIT = 2
    }
}
