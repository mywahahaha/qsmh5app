package com.qishimeng.game.splash

import android.content.Context
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import com.qishimeng.game.R

class StartupSplashView(context: Context) : ImageView(context) {
    private var shownAt = 0L
    private val dismissAction = Runnable { dismiss() }

    init {
        val images = resources.obtainTypedArray(R.array.qsm_splash_images)
        val assets = try {
            (0 until images.length()).mapNotNull { index ->
                val id = images.getResourceId(index, 0)
                if (id == 0) null else SplashAsset.fromResourceName(id, resources.getResourceEntryName(id))
            }
        } finally {
            images.recycle()
        }
        val metrics = resources.displayMetrics
        val selected = SplashSelector.select(assets, metrics.widthPixels, metrics.heightPixels)
        if (selected == null) {
            visibility = View.GONE
        } else {
            setBackgroundColor(Color.BLACK)
            scaleType = ScaleType.CENTER_CROP
            setImageResource(selected.resourceId)
            isClickable = true
            isFocusable = true
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility != View.VISIBLE) return
        shownAt = SystemClock.uptimeMillis()
        postDelayed(dismissAction, SplashDisplayPolicy.delayUntilDismiss(0L, contentReady = false))
    }

    fun onContentReady() {
        if (visibility != View.VISIBLE) return
        // A cached page can commit before the first window attachment.
        if (shownAt == 0L) {
            post { onContentReady() }
            return
        }
        removeCallbacks(dismissAction)
        postDelayed(dismissAction, SplashDisplayPolicy.delayUntilDismiss(
            SystemClock.uptimeMillis() - shownAt, contentReady = true
        ))
    }

    fun dismiss() {
        removeCallbacks(dismissAction)
        visibility = View.GONE
        setImageDrawable(null)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(dismissAction)
        super.onDetachedFromWindow()
    }
}
