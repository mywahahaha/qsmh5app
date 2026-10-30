package com.qishimeng.game.auth

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.widget.FrameLayout

/** 顶部圆角和下沿浅弧，不使用拉伸位图，保证不同宽度下的弧线平滑。 */
class OnekeyHeaderView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(197, 45, 40) }
    private val path = Path()

    init { setWillNotDraw(false) }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = minOf(14 * resources.displayMetrics.density, w / 2, h / 2)
        path.reset()
        path.moveTo(radius, 0f)
        path.lineTo(w - radius, 0f)
        path.quadTo(w, 0f, w, radius)
        path.lineTo(w, h)
        path.quadTo(w / 2, h * 0.62f, 0f, h)
        path.lineTo(0f, radius)
        path.quadTo(0f, 0f, radius, 0f)
        path.close()
        canvas.drawPath(path, paint)
    }
}
