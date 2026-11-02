package com.qishimeng.game.floatball

import android.app.Activity
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.bumptech.glide.request.target.DrawableImageViewTarget
import com.bumptech.glide.request.transition.Transition
import com.qishimeng.game.config.FloatingBallConfig
import com.qishimeng.game.web.FullscreenViewport

class FloatingBallView(
    private val activity: Activity,
    private val host: FrameLayout,
    private val config: FloatingBallConfig,
    initiallyActive: Boolean = false,
    private val onClick: () -> Unit
) {
    private val image = ImageView(activity)
    private val requests = Glide.with(host)
    private var animationAllowed = true
    private val imageTarget = object : DrawableImageViewTarget(image) {
        override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
            (resource as? GifDrawable)?.setLoopCount(GifDrawable.LOOP_INTRINSIC)
            super.onResourceReady(resource, transition)
            updateAnimationVisibility()
        }

        override fun onStart() {
            animationAllowed = true
            super.onStart()
            updateAnimationVisibility()
        }

        override fun onStop() {
            animationAllowed = false
            super.onStop()
        }
    }
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
        val visibility = if (visible) View.VISIBLE else View.GONE
        if (image.visibility == visibility) return
        image.visibility = visibility
        updateAnimationVisibility()
    }

    fun setActive(isActive: Boolean) {
        if (active == isActive && currentImageUrl != null) return
        active = isActive
        loadImage(if (isActive) config.imageActive else config.imageDefault)
    }

    fun destroy() {
        requests.clear(imageTarget)
        host.removeOnLayoutChangeListener(hostLayoutListener)
        host.removeView(image)
    }

    private fun loadImage(url: String) {
        if (url.isBlank() || url == currentImageUrl) return
        currentImageUrl = url
        // 由图片内容识别 GIF；更换同一 target 的请求时，Glide 取消旧请求并释放旧动画。
        requests.load(url).timeout(5_000).disallowHardwareConfig().into(imageTarget)
    }

    private fun updateAnimationVisibility() {
        val animation = image.drawable as? Animatable ?: return
        val visible = animationAllowed && image.visibility == View.VISIBLE
        image.drawable.setVisible(visible, false)
        if (visible) {
            if (!animation.isRunning) animation.start()
        } else animation.stop()
    }

    private fun createLayoutParams(): FrameLayout.LayoutParams {
        val metrics = activity.resources.displayMetrics
        val viewport = FullscreenViewport.resolve(host.width, host.height, metrics.widthPixels, metrics.heightPixels)
        val screenWidthDp = (viewport.widthPx / metrics.density).toInt()
        val widthPx = FloatingBallLayout.toPixels(config.width, metrics.density).coerceIn(1, viewport.widthPx)
        val heightPx = FloatingBallLayout.toPixels(config.height, metrics.density).coerceIn(1, viewport.heightPx)
        val leftPx = config.left?.let { FloatingBallLayout.horizontalOffsetToPixels(it, screenWidthDp, metrics.density) }
            ?: (viewport.widthPx - FloatingBallLayout.horizontalOffsetToPixels(config.right ?: 0, screenWidthDp, metrics.density) - widthPx)
        val topPx = FloatingBallLayout.resolveTop(config.top, config.bottom, viewport.heightPx, heightPx, metrics.density)
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
