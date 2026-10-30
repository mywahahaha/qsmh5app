package com.qishimeng.game.auth

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.CheckBox
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import com.mobile.auth.gatewayauth.AuthUIConfig
import com.mobile.auth.gatewayauth.LoginAuthActivity
import com.mobile.auth.gatewayauth.PhoneNumberAuthHelper
import com.mobile.auth.gatewayauth.TokenResultListener
import com.qishimeng.game.R
import com.qishimeng.game.bridge.BridgeTestActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OnekeyAuthUiTest {
    @get:Rule val activityRule = ActivityTestRule(BridgeTestActivity::class.java)

    @Test fun dialogUsesOpaqueRoundedSurfaceMaskAndBrandedProtocolBar() = onMain {
        val ui = buildConfig(OnekeyConfig(true, "", "", title = "不应显示的标题", loginText = "本机号码一键登录"))
        assertTrue("授权页必须使用弹窗模式", ui.isDialog)
        assertTrue("默认标题栏应隐藏，改用品牌头部", ui.isNavHidden)
        assertEquals("", ui.navText)
        assertEquals(0.45f, ui.dialogAlpha, 0.001f)
        assertEquals("本机号码一键登录", ui.logBtnText)
        assertEquals(Color.WHITE, ui.logBtnTextColor)
        assertEquals(Color.rgb(197, 45, 40), ui.webNavColor)
        assertEquals(Color.WHITE, ui.webNavTextColor)
        assertFalse("协议仍需由用户勾选", ui.isPrivacyState)
        assertFalse(ui.isCheckboxHidden)

        val resource = activityRule.activity.resources.getIdentifier(ui.pageBackgroundPath, "drawable", activityRule.activity.packageName)
        assertTrue("弹窗背景资源必须有效", resource != 0)
        val drawable = activityRule.activity.getDrawable(resource)!!
        val bitmap = Bitmap.createBitmap(300, 240, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, 300, 240)
        drawable.draw(Canvas(bitmap))
        assertEquals("弹窗不能再透出黑色底层", Color.rgb(238, 238, 238), bitmap.getPixel(150, 120))
        assertEquals("圆角外应透明", 0, Color.alpha(bitmap.getPixel(0, 0)))
        bitmap.recycle()
    }

    @Test fun phoneButtonSwitchAndPrivacyHaveSeparateVerticalSpace() = onMain {
        val ui = buildConfig(OnekeyConfig(true, "", ""))
        assertTrue("手机号应位于品牌头部下方", ui.numFieldOffsetY + 50 >= 40)
        assertTrue("运营商说明与手机号应分开", ui.sloganOffsetY >= ui.numFieldOffsetY + 25)
        assertTrue("登录按钮应位于运营商说明下方", ui.logBtnOffsetY >= ui.sloganOffsetY + 18)
        assertTrue("切换方式不能与登录按钮重叠", ui.switchOffsetY >= ui.logBtnOffsetY + ui.logBtnHeight + 5)
        assertTrue("协议应从底部定位并保留间距", ui.privacyOffsetY_B >= 8)
        assertEquals(Color.rgb(85, 85, 85), ui.numberColor)
        assertTrue(ui.isLogoHidden)
    }

    @Test fun interfacePercentagesControlBothDialogDimensionsInEitherOrientation() = onMain {
        val activity = activityRule.activity
        val metrics = activity.resources.displayMetrics
        val widthPx = activity.window.decorView.width.takeIf { it > 0 } ?: metrics.widthPixels
        val heightPx = activity.window.decorView.height.takeIf { it > 0 } ?: metrics.heightPixels
        val longDp = maxOf(widthPx, heightPx) / metrics.density
        val shortDp = minOf(widthPx, heightPx) / metrics.density
        val landscape = buildConfig(OnekeyConfig(true, "", "", screenCode = 1, dialogWidthPercent = 63, dialogHeightPercent = 81))
        val portrait = buildConfig(OnekeyConfig(true, "", "", screenCode = 2, dialogWidthPercent = 82, dialogHeightPercent = 57))
        assertEquals((longDp * 0.63f).toInt(), landscape.dialogWidth)
        assertEquals((shortDp * 0.81f).toInt(), landscape.dialogHeight)
        assertEquals((shortDp * 0.82f).toInt(), portrait.dialogWidth)
        assertEquals((longDp * 0.57f).toInt(), portrait.dialogHeight)
    }

    /** 离线假号码直接送入 SDK 自身 Activity；不请求认证 Token、不执行业务登录。 */
    @Test fun sdkActivityRendersDialogWithoutOverlapsAndCloseWorks() = renderDialog()

    @Test fun shortDialogKeepsPrivacyReadableWithinConfiguredHeight() = renderDialog(heightPercent = 50)

    @Test fun systemBackClosesUserControlledSdkPage() = renderDialog(closeWithBack = true)

    private fun renderDialog(heightPercent: Int = 75, closeWithBack: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = activityRule.activity
        lateinit var helper: PhoneNumberAuthHelper
        lateinit var ui: AuthUIConfig
        var closeCount = 0
        var managerId = 0
        lateinit var manager: Any
        onMain {
            activity.host.setBackgroundColor(Color.WHITE)
            helper = PhoneNumberAuthHelper.getInstance(activity, object : TokenResultListener {
                override fun onTokenSuccess(result: String) = Unit
                override fun onTokenFailed(result: String) = Unit
            })
            ui = buildConfig(OnekeyConfig(true, "", "", dialogHeightPercent = heightPercent, loginText = "本机号码一键登录", protocols = listOf(
                "用户注册协议与隐私政策" to "http://127.0.0.1:1/privacy"
            )))
            OnekeyAuthUi.configureHeader(helper) { closeCount++; helper.quitLoginPage() }
            helper.userControlAuthPageCancel()
            helper.setAuthUIConfig(ui)
            val managerField = PhoneNumberAuthHelper::class.java.getDeclaredField("c").apply { isAccessible = true }
            manager = requireNotNull(managerField.get(helper))
            val idField = manager.javaClass.getDeclaredField("o").apply { isAccessible = true }
            managerId = idField.getInt(manager)
            // 正常取号后 SDK 才登记此映射；离线渲染仅补上页面所需的管理器映射。
            val registryField = manager.javaClass.getDeclaredField("b").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val registry = registryField.get(null) as MutableMap<Int, Any>
            registry[managerId] = manager
        }
        val intent = Intent(activity, LoginAuthActivity::class.java)
            .putExtra("number", "136****8688")
            .putExtra("vendor", "cm_zyhl")
            .putExtra("access_code", "offline-ui-test")
            .putExtra("ui_manager_id", managerId)
            .putExtra("startTime", System.currentTimeMillis())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val auth = instrumentation.startActivitySync(intent)
        try {
            instrumentation.waitForIdleSync()
            SystemClock.sleep(700)
            onMain {
                manager.javaClass.getMethod("a", Activity::class.java).invoke(manager, auth)
                assertFalse("SDK 授权页不能提前退出", auth.isFinishing)
                val root = auth.window.decorView
                val header = root.findViewById<View>(R.id.onekey_header)
                assertNotNull("真实 SDK 页面必须包含品牌头部", header)
                val texts = descendants(root).filterIsInstance<TextView>()
                val number = texts.single { it.text.toString() == "136****8688" }
                val button = texts.single { it.text.toString() == "本机号码一键登录" }
                val slogan = texts.single { it.text.toString().contains("提供认证服务") }
                val switch = texts.single { it.text.toString() == "使用其他方式登录" }
                val protocol = texts.single { it.text.toString().contains("用户注册协议与隐私政策") }
                val checkbox = descendants(root).filterIsInstance<CheckBox>().single()
                assertFalse(checkbox.isChecked)
                val x = protocol.paddingLeft + protocol.layout.getPrimaryHorizontal(2)
                val y = protocol.paddingTop + (protocol.layout.getLineTop(0) + protocol.layout.getLineBottom(0)) / 2f
                fun tapText() {
                    val now = SystemClock.uptimeMillis()
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val event = MotionEvent.obtain(now, now + 20, action, x, y, 0)
                        protocol.dispatchTouchEvent(event)
                        event.recycle()
                    }
                }
                tapText()
                assertTrue("点击阅读同意文字必须勾选", checkbox.isChecked)
                tapText()
                assertFalse("再次点击阅读同意文字必须取消勾选", checkbox.isChecked)
                val ordered = listOf(header, number, slogan, button, switch, protocol)
                val rectangles = ordered.map { view -> Rect().also { assertTrue(view.getGlobalVisibleRect(it)) } }
                rectangles.zipWithNext().forEach { (above, below) ->
                    assertTrue("授权控件不能重叠：$above / $below", above.bottom <= below.top)
                }
                val dialogBottom = rectangles.first().top + (ui.dialogHeight * activity.resources.displayMetrics.density).toInt()
                assertTrue("完整协议文字必须处于弹窗内部", rectangles.last().top + protocol.height <= dialogBottom)
                assertEquals("协议不能被底部裁切", protocol.height, rectangles.last().height())
                assertTrue("协议必须容纳全部文字行，不能被 SDK 压缩成几像素", protocol.height >= protocol.layout.height)
                assertEquals("协议不能省略尾部文字", protocol.text.length, protocol.layout.getLineEnd(protocol.lineCount - 1))
                assertEquals("SDK 实际宽度应使用接口比例", ui.dialogWidth * activity.resources.displayMetrics.density, header.width.toFloat(), 3f)
                assertEquals("实际窗口遮罩必须为 45%", 0.45f, auth.window.attributes.dimAmount, 0.001f)
            }
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            val file = File("/sdcard/Download", "qsm-onekey-dialog-$heightPercent.png")
            file.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()

            val monitor = instrumentation.addMonitor("com.mobile.auth.gatewayauth.activity.AuthWebVeiwActivity", null, false)
            onMain {
                val protocol = descendants(auth.window.decorView).filterIsInstance<TextView>()
                    .single { it.text.toString().contains("用户注册协议与隐私政策") }
                val text = protocol.text as Spanned
                val link = text.getSpans(0, text.length, ClickableSpan::class.java).single {
                    text.subSequence(text.getSpanStart(it), text.getSpanEnd(it)).contains("用户注册协议与隐私政策")
                }
                val offset = text.getSpanStart(link) + 2
                val line = protocol.layout.getLineForOffset(offset)
                val x = protocol.paddingLeft + protocol.layout.getPrimaryHorizontal(offset)
                val y = protocol.paddingTop + (protocol.layout.getLineTop(line) + protocol.layout.getLineBottom(line)) / 2f
                val now = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(now, now + 20, action, x, y, 0)
                    protocol.dispatchTouchEvent(event)
                    event.recycle()
                }
                assertFalse("点击协议链接不能改变勾选状态", descendants(auth.window.decorView).filterIsInstance<CheckBox>().single().isChecked)
            }
            val web = instrumentation.waitForMonitorWithTimeout(monitor, 5_000)
            instrumentation.removeMonitor(monitor)
            assertNotNull("协议链接应打开 SDK 协议详情页", web)
            try {
                instrumentation.waitForIdleSync()
                SystemClock.sleep(300)
                val webShot = instrumentation.uiAutomation.takeScreenshot()
                File("/sdcard/Download", "qsm-onekey-protocol.png").outputStream().use {
                    webShot.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                webShot.recycle()
            } finally { onMain { web!!.finish() } }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(500) // 等待协议页的退出动画及授权页重新取得输入焦点。
            if (closeWithBack) instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            else onMain { auth.findViewById<View>(R.id.onekey_close).performClick() }
            instrumentation.waitForIdleSync()
            val closeDeadline = SystemClock.uptimeMillis() + 2_000
            while (closeCount == 0 && SystemClock.uptimeMillis() < closeDeadline) SystemClock.sleep(25)
            assertEquals(1, closeCount)
            assertTrue("关闭应退出 SDK 授权页", auth.isFinishing)
        } finally {
            onMain {
                auth.finish()
                helper.setAuthListener(null)
                helper.setUIClickListener(null)
                helper.removeAuthRegisterXmlConfig()
            }
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun buildConfig(config: OnekeyConfig): AuthUIConfig {
        val method = AliyunAuthAdapter::class.java.getDeclaredMethod("buildUiConfig", Activity::class.java, OnekeyConfig::class.java)
        method.isAccessible = true
        return method.invoke(AliyunAuthAdapter { activityRule.activity }, activityRule.activity, config) as AuthUIConfig
    }

    private fun onMain(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
}
