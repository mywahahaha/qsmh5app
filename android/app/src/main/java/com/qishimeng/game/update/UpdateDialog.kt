package com.qishimeng.game.update

import android.app.Activity
import android.app.Dialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.qishimeng.game.R
import kotlin.math.min
import kotlin.math.roundToInt

/** 全屏透明原生窗口，百分比相对真实可用窗口计算，内容超高时面板内部滚动。 */
class UpdateDialog(
    private val activity: Activity,
    private val info: UpdateInfo,
    private val onUpdate: () -> Unit,
    private val onInstall: () -> Unit,
    private val onClose: () -> Unit
) : Dialog(activity) {
    private val root = FrameLayout(activity)
    private val group = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
    private val panel = ScrollView(activity).apply {
        tag = "update_panel"
        isFillViewport = false
        background = rounded(Color.WHITE, dp(15f))
        clipToOutline = true
    }
    private val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val header = FrameLayout(activity).apply {
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0xffffcc1a.toInt(), 0xffffb81a.toInt()))
    }
    private val headerImage = ImageView(activity).apply {
        setImageResource(R.drawable.update_header); scaleType = ImageView.ScaleType.FIT_XY
    }
    private val title = label(info.title, Color.WHITE, "update_title")
    private val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
    private val description = label(info.description, Color.rgb(255, 89, 12), "update_desc")
    private val metadataVersion = label("最新版本：${info.version.trim().ifEmpty { "未提供" }}", Color.rgb(117, 117, 117), "update_metadata_version")
    private val metadataTime = label("更新时间：${info.time.trim()}", Color.rgb(117, 117, 117), "update_metadata_time")
    private val metadata = LinearLayout(activity).apply {
        tag = "update_metadata"
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        addView(metadataVersion, LinearLayout.LayoutParams(-2, -2))
        addView(metadataTime, LinearLayout.LayoutParams(-2, -2))
    }
    private val progress = UpdateProgressView(activity).apply { tag = "update_progress" }
    private val status = label("", Color.rgb(145, 145, 145), "update_status")
    private val action = label("立即更新", Color.WHITE, "update_action").apply {
        background = rounded(Color.rgb(65, 190, 86), dp(100f))
        isClickable = true; isFocusable = true
        setOnClickListener { if (state is UpdateState.Ready) onInstall() else onUpdate() }
    }
    private val close = object : View(activity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE }
        override fun onDraw(canvas: Canvas) {
            val size = min(width, height).toFloat()
            paint.strokeWidth = size / 28f
            val a = size * 0.30f; val b = size * 0.70f
            canvas.drawLine(a, a, b, b, paint); canvas.drawLine(a, b, b, a, paint)
        }
    }.apply {
        tag = "update_close"; contentDescription = "关闭更新"
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.BLACK)
        }
        isClickable = true; isFocusable = true
        visibility = if (info.force) View.GONE else View.VISIBLE
        setOnClickListener { onClose() }
    }
    private var state: UpdateState = UpdateState.Offered
    private var lastWidth = 0
    private var lastHeight = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)
        setCancelable(!info.force)
        setCanceledOnTouchOutside(false)
        root.isClickable = true
        root.addView(group, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        group.addView(panel)
        group.addView(close)
        panel.addView(content, ViewGroup.LayoutParams(-1, -2))
        header.addView(headerImage, FrameLayout.LayoutParams(-1, -1, Gravity.BOTTOM))
        header.addView(title, FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        content.addView(header)
        content.addView(body)
        body.addView(description); body.addView(metadata); body.addView(progress); body.addView(status); body.addView(action)
        setContentView(root)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.45f)
            setLayout(-1, -1)
            WindowCompat.setDecorFitsSystemWindows(this, false)
            WindowInsetsControllerCompat(this, decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        root.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val width = right - left; val height = bottom - top
            if (width > 0 && height > 0 && (width != lastWidth || height != lastHeight)) layoutFor(width, height)
        }
        render(state)
    }

    override fun onStart() { super.onStart(); window?.setLayout(-1, -1) }

    @Deprecated("Android Dialog 的返回键回调")
    override fun onBackPressed() { if (!info.force) onClose() }

    fun render(value: UpdateState) {
        state = value
        if (value == UpdateState.Closed) { dismiss(); return }
        val downloading = value is UpdateState.Downloading
        val ready = value is UpdateState.Ready
        progress.visibility = if (downloading || ready) View.VISIBLE else View.GONE
        status.visibility = progress.visibility
        metadata.visibility = if (!downloading && !ready && (info.version.isNotBlank() || info.time.isNotBlank())) View.VISIBLE else View.GONE
        metadataVersion.visibility = if (info.version.isNotBlank() || info.time.isNotBlank()) View.VISIBLE else View.GONE
        metadataTime.visibility = if (info.time.isNotBlank()) View.VISIBLE else View.GONE
        action.visibility = if (downloading) View.GONE else View.VISIBLE
        action.text = if (ready) "立即安装" else "立即更新"
        when (value) {
            is UpdateState.Downloading -> {
                progress.percent = value.percent
                status.text = "新版本正在更新中，请耐心等待..." + (value.percent?.let { " $it%" } ?: "")
            }
            is UpdateState.Ready -> { progress.percent = 100; status.text = "新版本下载完成 100%" }
            else -> Unit
        }
        if (lastWidth > 0 && lastHeight > 0) layoutFor(lastWidth, lastHeight)
    }

    private fun layoutFor(width: Int, height: Int) {
        lastWidth = width; lastHeight = height
        val density = activity.resources.displayMetrics.density
        val cardWidth = info.width.toPixels(width, density)
        val scale = cardWidth / 530f
        fun px(value: Float) = (value * scale).roundToInt().coerceAtLeast(1)
        val closeSize = (2 * maxOf(dp(22f).roundToInt(), px(34f)) * 0.8f).roundToInt()
        val gap = px(14f)
        val availableHeight = (height - dp(24f).roundToInt() - if (info.force) 0 else closeSize + gap).coerceAtLeast(1)
        (group.layoutParams as FrameLayout.LayoutParams).apply { this.width = cardWidth; group.layoutParams = this }
        (title.layoutParams as FrameLayout.LayoutParams).apply {
            topMargin = px(34f); leftMargin = px(22f); rightMargin = px(22f); title.layoutParams = this
        }
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, 40f * scale)
        title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
        title.visibility = if (info.title.isBlank()) View.GONE else View.VISIBLE
        title.measure(View.MeasureSpec.makeMeasureSpec((cardWidth - px(44f)).coerceAtLeast(1), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        // 标题正常缩短约 20%；长标题仍扩展背景以完整显示。
        val headerHeight = maxOf(px(158f), if (title.visibility == View.GONE) 0 else px(34f) + title.measuredHeight + px(80f))
        header.layoutParams = LinearLayout.LayoutParams(-1, headerHeight)
        headerImage.layoutParams = FrameLayout.LayoutParams(-1, headerHeight, Gravity.BOTTOM)
        body.layoutParams = LinearLayout.LayoutParams(-1, -2)
        body.setPadding(px(44f), px(0f), px(44f), px(28.5f))
        description.setTextSize(TypedValue.COMPLEX_UNIT_PX, 30f * scale)
        description.visibility = if (info.description.isBlank()) View.GONE else View.VISIBLE
        description.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(0f) }
        metadataVersion.setTextSize(TypedValue.COMPLEX_UNIT_PX, 18f * scale)
        metadataTime.setTextSize(TypedValue.COMPLEX_UNIT_PX, 18f * scale)
        val unrestricted = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        metadataVersion.measure(unrestricted, unrestricted)
        metadataTime.measure(unrestricted, unrestricted)
        val stackMetadata = metadataVersion.visibility == View.VISIBLE && metadataTime.visibility == View.VISIBLE &&
            metadataVersion.measuredWidth + metadataTime.measuredWidth + 15 > cardWidth - px(88f)
        metadata.orientation = if (stackMetadata) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        metadataVersion.layoutParams = LinearLayout.LayoutParams(if (stackMetadata) -1 else -2, -2)
        metadataTime.layoutParams = LinearLayout.LayoutParams(if (stackMetadata) -1 else -2, -2).apply {
            if (stackMetadata) topMargin = px(8f) else if (metadataVersion.visibility == View.VISIBLE) marginStart = 15
        }
        metadata.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(30f) }
        progress.layoutParams = LinearLayout.LayoutParams(-1, px(30f)).apply { topMargin = px(50f) }
        status.setTextSize(TypedValue.COMPLEX_UNIT_PX, 18f * scale)
        status.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(24f) }
        action.setTextSize(TypedValue.COMPLEX_UNIT_PX, 28f * scale)
        action.layoutParams = LinearLayout.LayoutParams(px(320f), maxOf(dp(25f).roundToInt(), px(44f)) + 10).apply { topMargin = px(30f) }
        content.measure(View.MeasureSpec.makeMeasureSpec(cardWidth, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val desiredHeight = info.height?.toPixels(height, density) ?: content.measuredHeight
        panel.layoutParams = LinearLayout.LayoutParams(-1, desiredHeight.coerceIn(1, availableHeight))
        close.layoutParams = LinearLayout.LayoutParams(closeSize, closeSize).apply { topMargin = gap }
    }

    private fun label(value: String, color: Int, viewTag: String) = TextView(activity).apply {
        text = value; gravity = Gravity.CENTER; setTextColor(color); tag = viewTag
        includeFontPadding = false
    }
    private fun dp(value: Float) = value * activity.resources.displayMetrics.density
    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }

}

internal fun UpdateDimension.toPixels(referencePx: Int, density: Float): Int = when (this) {
    is UpdateDimension.Dp -> (value * density).roundToInt()
    is UpdateDimension.Percent -> (referencePx * value / 100f).roundToInt()
}.coerceAtLeast(1)

private class UpdateProgressView(activity: Activity) : View(activity) {
    var percent: Int? = null
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        val radius = height / 2f
        paint.shader = null; paint.color = 0xffebebeb.toInt()
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, paint)
        val fill = width * ((percent ?: 28) / 100f)
        val start = if (percent == null) {
            (android.os.SystemClock.uptimeMillis() % 1600) / 1600f * (width + fill) - fill
        } else 0f
        paint.shader = LinearGradient(start, 0f, start + maxOf(fill, 1f), 0f,
            intArrayOf(0xff00adf5.toInt(), 0xff41be56.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(start, 0f, start + fill, height.toFloat(), radius, radius, paint)
        if (percent == null && isShown) postInvalidateOnAnimation()
    }
}
