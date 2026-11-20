package com.qishimeng.game.auth

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewOverlay
import android.widget.FrameLayout
import android.widget.TextView
import com.mobile.auth.gatewayauth.ResultCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class OnekeyPrivacyPromptTest {
    @Test
    fun uncheckedLoginShowsCenteredBlackTranslucentPrompt() {
        val root = createRoot()
        val prompt = OnekeyPrivacyPrompt(root)

        prompt.onUiClick(ResultCode.CODE_ERROR_USER_LOGIN_BTN, "{\"isChecked\":false}")

        assertEquals("提示不能成为 SDK 可枚举的普通子 View", 0, root.childCount)
        val message = overlayChildren(root).single() as TextView
        assertEquals("请阅读并同意用户注册协议及隐私政策", message.text.toString())
        assertEquals(Color.WHITE, message.currentTextColor)
        assertTrue(kotlin.math.abs(root.width / 2 - (message.left + message.width / 2)) <= 1)
        assertTrue(kotlin.math.abs(root.height / 2 - (message.top + message.height / 2)) <= 1)
        assertFalse(message.isClickable)
        assertFalse(message.isFocusable)
        assertEquals("提示只能包含一个文案控件，不应有关闭按钮", 1, overlayChildren(root).size)

        val backgroundColor = (message.background as GradientDrawable).color!!.defaultColor
        assertEquals(Color.BLACK, Color.rgb(Color.red(backgroundColor), Color.green(backgroundColor), Color.blue(backgroundColor)))
        assertTrue("背景必须为半透明", Color.alpha(backgroundColor) in 1..254)
    }

    @Test
    fun repeatedClicksDoNotStackOrExtendThePromptLifetime() {
        val root = createRoot()
        val prompt = OnekeyPrivacyPrompt(root)

        prompt.onUiClick(ResultCode.CODE_ERROR_USER_LOGIN_BTN, "{\"isChecked\":false}")
        val first = overlayChildren(root).single()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(900))
        prompt.onUiClick(ResultCode.CODE_ERROR_USER_LOGIN_BTN, "{\"isChecked\":false}")

        assertEquals(1, overlayChildren(root).size)
        assertSame(first, overlayChildren(root).single())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(99))
        assertEquals(1, overlayChildren(root).size)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals("首次显示满 1000ms 后必须自动消失", 0, overlayChildren(root).size)

        prompt.onUiClick(ResultCode.CODE_ERROR_USER_LOGIN_BTN, "{\"isChecked\":false}")
        assertEquals("前一次提示消失后，下一次点击仍应正常提示", 1, overlayChildren(root).size)
    }

    @Test
    fun checkedOrUnrelatedClicksDoNotShowPrompt() {
        val root = createRoot()
        val prompt = OnekeyPrivacyPrompt(root)

        prompt.onUiClick(ResultCode.CODE_ERROR_USER_LOGIN_BTN, "{\"isChecked\":true}")
        prompt.onUiClick(ResultCode.CODE_ERROR_USER_CHECKBOX, "{\"isChecked\":false}")
        prompt.onUiClick(ResultCode.CODE_ERROR_USER_LOGIN_BTN, "invalid")

        assertEquals(0, overlayChildren(root).size)
    }

    private fun createRoot(): FrameLayout {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        return FrameLayout(activity).also { root ->
            activity.setContentView(root)
            val metrics = activity.resources.displayMetrics
            root.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
    }

    private fun overlayChildren(root: ViewGroup): List<View> {
        val field = ViewOverlay::class.java.getDeclaredField("mOverlayViewGroup").apply { isAccessible = true }
        val overlayGroup = field.get(root.overlay) as ViewGroup
        return (0 until overlayGroup.childCount).map(overlayGroup::getChildAt)
    }
}
