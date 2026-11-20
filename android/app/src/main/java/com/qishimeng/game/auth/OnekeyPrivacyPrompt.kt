package com.qishimeng.game.auth

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.mobile.auth.gatewayauth.ResultCode
import org.json.JSONObject

/** 一键登录页未勾选协议时的居中短提示。 */
internal class OnekeyPrivacyPrompt(private val root: FrameLayout) {
    private var visibleMessage: View? = null

    fun onUiClick(code: String?, data: String?) {
        if (code != ResultCode.CODE_ERROR_USER_LOGIN_BTN || isChecked(data)) return
        show()
    }

    private fun show() {
        if (visibleMessage != null) return
        val density = root.resources.displayMetrics.density
        val horizontalPadding = (16 * density).toInt()
        val verticalPadding = (10 * density).toInt()
        val message = TextView(root.context).apply {
            text = PROMPT_TEXT
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.argb(204, 0, 0, 0))
                cornerRadius = 8 * density
            }
            isClickable = false
            isFocusable = false
        }
        val layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        message.layoutParams = layoutParams
        val hostWidth = root.width.takeIf { it > 0 } ?: root.resources.displayMetrics.widthPixels
        val hostHeight = root.height.takeIf { it > 0 } ?: root.resources.displayMetrics.heightPixels
        val horizontalMargin = (24 * density).toInt()
        message.measure(
            View.MeasureSpec.makeMeasureSpec((hostWidth - horizontalMargin * 2).coerceAtLeast(1), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(hostHeight.coerceAtLeast(1), View.MeasureSpec.AT_MOST)
        )
        val left = (hostWidth - message.measuredWidth) / 2
        val top = (hostHeight - message.measuredHeight) / 2
        message.layout(left, top, left + message.measuredWidth, top + message.measuredHeight)
        visibleMessage = message
        root.overlay.add(message)
        root.postDelayed({
            if (visibleMessage === message) {
                root.overlay.remove(message)
                visibleMessage = null
            }
        }, DISPLAY_DURATION_MS)
    }

    private fun isChecked(data: String?): Boolean = runCatching {
        JSONObject(data.orEmpty()).optBoolean("isChecked", true)
    }.getOrDefault(true)

    private companion object {
        const val PROMPT_TEXT = "请阅读并同意用户注册协议及隐私政策"
        const val DISPLAY_DURATION_MS = 1_000L
    }
}
