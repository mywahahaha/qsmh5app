package com.qishimeng.game.auth

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.view.Gravity
import android.view.View
import com.mobile.auth.gatewayauth.AuthRegisterXmlConfig
import com.mobile.auth.gatewayauth.AuthUIConfig
import com.mobile.auth.gatewayauth.PhoneNumberAuthHelper
import com.mobile.auth.gatewayauth.ResultCode
import com.mobile.auth.gatewayauth.ui.AbstractPnsViewDelegate
import com.qishimeng.game.R
import com.qishimeng.game.web.FullscreenViewport
import kotlin.math.ceil

/** 保留 SDK 的认证与协议控件，仅配置位置、样式和品牌头部。所有 SDK 尺寸参数为 dp。 */
object OnekeyAuthUi {
    // 2.14.24 的内容区即使隐藏导航也从顶部 50dp 开始，偏移量相对此内容区。
    private const val SDK_BODY_TOP_DP = 50
    fun build(activity: Activity, config: OnekeyConfig): AuthUIConfig {
        val metrics = activity.resources.displayMetrics
        val viewport = FullscreenViewport.resolve(
            activity.window.decorView.width, activity.window.decorView.height,
            metrics.widthPixels, metrics.heightPixels
        )
        val shortDp = minOf(viewport.widthPx, viewport.heightPx) / metrics.density
        val longDp = maxOf(viewport.widthPx, viewport.heightPx) / metrics.density
        val landscape = config.screenCode == AliyunAuthAdapter.SCREEN_LANDSCAPE
        val width = ((if (landscape) longDp else shortDp) * config.dialogWidthPercent.coerceIn(1, 100) / 100f).toInt().coerceAtLeast(1)
        val height = ((if (landscape) shortDp else longDp) * config.dialogHeightPercent.coerceIn(1, 100) / 100f).toInt().coerceAtLeast(1)
        val contentHeight = (height - 38).coerceAtLeast(1)
        val compact = height < 220
        val scale = ((height - SDK_BODY_TOP_DP) / 130f).coerceIn(0.4f, 1f)
        val numberY = if (compact) SDK_BODY_TOP_DP else 38 + (contentHeight * 0.08f).toInt()
        val numberSize = if (compact) (20 * scale).toInt().coerceAtLeast(10) else (height * 0.10f).toInt().coerceIn(20, 30)
        val sloganSize = if (compact) (9 * scale).toInt().coerceAtLeast(7) else 11
        val switchSize = if (compact) (11 * scale).toInt().coerceAtLeast(7) else 13
        val privacySize = if (compact) (9 * scale).toInt().coerceAtLeast(6) else 10
        // SDK TextView 含系统字体上下留白，基线间距不能只按字号计算。
        val sloganY = numberY + ceil(numberSize * 1.4f).toInt() + if (compact) 0 else 4
        val buttonY = if (compact) sloganY + ceil(sloganSize * 1.5f).toInt() + 3
            else maxOf(sloganY + 20, 38 + (contentHeight * 0.39f).toInt())
        val buttonHeight = if (compact) (26 * scale).toInt().coerceAtLeast(16) else (height * 0.13f).toInt().coerceIn(30, 44)
        val switchY = buttonY + buttonHeight + if (compact) 3 else 12

        val builder = AuthUIConfig.Builder()
            .setDialogWidth(width)
            .setDialogHeight(height)
            .setDialogCenterm(true)
            .setDialogBottom(false)
            .setDialogAlpha(0.45f)
            .setScreenOrientation(if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            .setStatusBarHidden(true)
            .setNavHidden(true)
            .setNavText("")
            .setLogoHidden(true)
            .setPageBackgroundPath("onekey_dialog_background")
            .setAuthPageActIn("onekey_fade_in", "onekey_fade_out")
            .setAuthPageActOut("onekey_fade_in", "onekey_fade_out")
            .setNumberColor(Color.rgb(85, 85, 85))
            .setNumberSizeDp(numberSize)
            .setNumberLayoutGravity(Gravity.CENTER_HORIZONTAL)
            .setNumFieldOffsetY((numberY - SDK_BODY_TOP_DP).coerceAtLeast(0))
            .setSloganHidden(false)
            .setSloganTextColor(Color.rgb(153, 153, 153))
            .setSloganTextSizeDp(sloganSize)
            .setSloganOffsetY((sloganY - SDK_BODY_TOP_DP).coerceAtLeast(0))
            .setLogBtnText(config.loginText)
            .setLogBtnTextColor(Color.WHITE)
            .setLogBtnTextSizeDp(if (compact) (14 * scale).toInt().coerceAtLeast(9) else 16)
            .setLogBtnBackgroundPath("onekey_login_button")
            .setLogBtnLayoutGravity(Gravity.CENTER_HORIZONTAL)
            .setLogBtnWidth((width * 0.82f).toInt())
            .setLogBtnMarginLeftAndRight(0)
            .setLogBtnHeight(buttonHeight)
            .setLogBtnOffsetY((buttonY - SDK_BODY_TOP_DP).coerceAtLeast(0))
            .setSwitchAccHidden(false)
            .setSwitchAccText(config.otherLoginText)
            .setSwitchAccTextColor(Color.rgb(102, 102, 102))
            .setSwitchAccTextSizeDp(switchSize)
            .setSwitchOffsetY((switchY - SDK_BODY_TOP_DP).coerceAtLeast(0))
            .setPrivacyState(false)
            .setCheckboxHidden(false)
            .setCheckedImgPath("onekey_checkbox_checked")
            .setUncheckedImgPath("onekey_checkbox_unchecked")
            .setCheckBoxWidth(14)
            .setCheckBoxHeight(14)
            .setCheckBoxMarginTop(2)
            .setAppPrivacyColor(Color.rgb(102, 102, 102), Color.BLUE)
            .setPrivacyTextSizeDp(privacySize)
            .setPrivacyBefore("我已阅读并同意")
            .setPrivacyEnd("")
            .setVendorPrivacyPrefix("《")
            .setVendorPrivacySuffix("》")
            .setPrivacyMargin(maxOf(16, (width * 0.09f).toInt()))
            .setProtocolGravity(Gravity.LEFT)
            // 从底部定位，让 SDK 按完整协议文本测量高度，避免顶部定位压缩多行文字。
            .setPrivacyOffsetY_B(if (compact) 8 else 16)
            .setWebNavColor(Color.rgb(197, 45, 40))
            .setWebNavTextColor(Color.WHITE)
            .setWebNavReturnImgPath("onekey_back")
            .setWebViewStatusBarColor(Color.rgb(197, 45, 40))
        config.protocols.take(2).forEachIndexed { index, protocol ->
            if (index == 0) builder.setAppPrivacyOne(protocol.first, protocol.second)
            else builder.setAppPrivacyTwo(protocol.first, protocol.second)
        }
        return builder.create()
    }

    fun configureHeader(helper: PhoneNumberAuthHelper, onClose: () -> Unit) {
        helper.expandAuthPageCheckedScope(true)
        helper.removeAuthRegisterViewConfig()
        helper.removeAuthRegisterXmlConfig()
        // userControlAuthPageCancel 的系统返回事件只发往 UI 监听器。
        helper.setUIClickListener { code, _, _ ->
            if (code == ResultCode.CODE_ERROR_USER_CONTROL_CANCEL_BYKEY ||
                code == ResultCode.CODE_ERROR_USER_CONTROL_CANCEL_BYBTN ||
                code == ResultCode.CODE_ERROR_USER_SWITCH
            ) onClose()
        }
        helper.addAuthRegisterXmlConfig(AuthRegisterXmlConfig.Builder()
            .setLayout(R.layout.onekey_auth_header, object : AbstractPnsViewDelegate() {
                override fun onViewCreated(view: View) {
                    view.findViewById<View>(R.id.onekey_close).setOnClickListener { onClose() }
                }
            }).build())
    }
}
