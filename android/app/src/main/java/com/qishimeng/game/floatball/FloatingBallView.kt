package com.qishimeng.game.floatball

import android.app.Activity
import android.graphics.BitmapFactory
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.os.Handler
import android.os.Looper
import com.qishimeng.game.config.FloatingBallConfig
import com.qishimeng.game.web.FullscreenViewport
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class FloatingBallView(
    private val activity: Activity,
    private val host: FrameLayout,
    private val config: FloatingBallConfig,
    initiallyActive: Boolean = false,
    private val onClick: () -> Unit
) {
    private val image = ImageView(activity)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "qsm-floating-ball-image").apply { isDaemon = true } }
    private var currentImageUrl: String? = null
    private var active = false
    private val hostLayoutListener = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
            image.layoutParams = createLayoutParams()
        }
    }

    init {
        image.contentDescription = "游戏悬浮球"
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        image.setImageDrawable(null)
        image.setOnTouchListener(::onTouch)
        host.addView(image, createLayoutParams())
        host.addOnLayoutChangeListener(hostLayoutListener)
        show(config.show)
        setActive(initiallyActive)
    }

    fun show(visible: Boolean) {
        image.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun setActive(isActive: Boolean) {
        if (active == isActive && currentImageUrl != null) return
        active = isActive
        loadImage(if (isActive) config.imageActive else config.imageDefault)
    }

    fun destroy() {
        loader.shutdownNow()
        host.removeOnLayoutChangeListener(hostLayoutListener)
        host.removeView(image)
    }

    private fun loadImage(url: String) {
        if (url.isBlank() || url == currentImageUrl) return
        currentImageUrl = url
        loader.execute {
            val bitmap = runCatching {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                try {
                    connection.inputStream.use(BitmapFactory::decodeStream)
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
            if (bitmap != null) mainHandler.post {
                if (currentImageUrl == url) image.setImageBitmap(bitmap)
            }
        }
    }

    private fun createLayoutParams(): FrameLayout.LayoutParams {
        val metrics = activity.resources.displayMetrics
        val viewport = FullscreenViewport.resolve(host.width, host.height, metrics.widthPixels, metrics.heightPixels)
        val screenWidthDp = (viewport.widthPx / metrics.density).toInt()
        val widthPx = FloatingBallLayout.toPixels(config.width, screenWidthDp, metrics.density).coerceIn(1, viewport.widthPx)
        val heightPx = FloatingBallLayout.toPixels(config.height, screenWidthDp, metrics.density).coerceIn(1, viewport.heightPx)
        val leftPx = config.left?.let { FloatingBallLayout.toPixels(it, screenWidthDp, metrics.density) }
            ?: (viewport.widthPx - FloatingBallLayout.toPixels(config.right ?: 0, screenWidthDp, metrics.density) - widthPx)
        val topPx = config.bottom?.let { viewport.heightPx - FloatingBallLayout.toPixels(it, screenWidthDp, metrics.density) - heightPx }
            ?: FloatingBallLayout.toPixels(config.top ?: 0, screenWidthDp, metrics.density)
        return FrameLayout.LayoutParams(widthPx, heightPx).apply {
            leftMargin = leftPx.coerceIn(0, viewport.widthPx - widthPx)
            topMargin = topPx.coerceIn(0, viewport.heightPx - heightPx)
        }
    }

    private fun onTouch(view: View, event: MotionEvent): Boolean {
        val params = view.layoutParams as FrameLayout.LayoutParams
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                view.tag = floatArrayOf(event.rawY, params.topMargin.toFloat())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val start = view.tag as? FloatArray ?: return true
                params.topMargin = (start[1] + event.rawY - start[0]).toInt().coerceIn(0, (host.height - view.height).coerceAtLeast(0))
                view.layoutParams = params
                return true
            }
            MotionEvent.ACTION_UP -> {
                val start = view.tag as? FloatArray
                if (start != null && kotlin.math.abs(event.rawY - start[0]) < CLICK_SLOP_PX) onClick()
                return true
            }
        }
        return true
    }

    private companion object {
        const val CLICK_SLOP_PX = 8f
    }
}
