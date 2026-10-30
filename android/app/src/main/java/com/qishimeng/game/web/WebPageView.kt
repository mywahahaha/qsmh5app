package com.qishimeng.game.web

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** 原生面板覆盖整个页面区域，WebView 网络错误文档始终不可见。主线程使用。 */
class WebPageView(
    private val activity: Activity,
    val webView: WebView,
    private val onContentReady: () -> Unit
) : FrameLayout(activity) {
    private val recovery = PageLoadRecovery()
    private val handler = Handler(Looper.getMainLooper())
    private var targetUrl = ""
    private var awaitingStart = false
    private var returningFromSettings = false
    private val navigationUrls = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private var pendingGetUrl: String? = null
    private var pendingNavigation = false
    @Volatile private var replaySafe = true
    private val title = label(20f)
    private val detail = label(15f)
    private val retry = Button(activity).apply {
        text = "重试"
        setOnClickListener { load(targetUrl) }
    }
    private val settings = Button(activity).apply {
        text = "网络设置"
        setOnClickListener {
            runCatching { activity.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
                .onSuccess { returningFromSettings = true }
                .onFailure { Toast.makeText(activity, "无法打开设置，请手动检查网络。", Toast.LENGTH_SHORT).show() }
        }
    }
    private val panel = ScrollView(activity).apply {
        setBackgroundColor(Color.rgb(20, 24, 30))
        isFillViewport = true
        isClickable = true
        isFocusable = true
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            addView(title)
            addView(detail)
            addView(LinearLayout(activity).apply {
                gravity = Gravity.CENTER
                addView(retry)
                addView(settings)
            })
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }
    private val timeoutAction = Runnable { fail(PageLoadFailure.timeout()) }
    private val retryAction = Runnable {
        if (recovery.retry()) beginAttempt()
    }

    init {
        addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        panel.visibility = View.GONE
    }

    fun load(url: String) {
        if (!UrlPolicy.isAllowedPageUrl(url) || !recovery.start()) return
        cancelPending()
        returningFromSettings = false
        pendingGetUrl = null
        pendingNavigation = false
        replaySafe = true
        targetUrl = url
        beginAttempt()
    }

    private fun beginAttempt() {
        // 先盖住旧文档，再停止旧请求，避免停止时产生的完成回调露出错误文档。
        showLoading()
        awaitingStart = true
        webView.stopLoading()
        navigationUrls.clear()
        navigationUrls.add(targetUrl)
        handler.postDelayed(timeoutAction, PageLoadRecovery.ATTEMPT_TIMEOUT_MILLIS)
        webView.loadUrl(targetUrl)
    }

    fun onPageStarted(url: String) {
        if (!UrlPolicy.isAllowedPageUrl(url)) return
        if (recovery.phase == PageLoadPhase.READY) {
            val knownGet = pendingGetUrl == url
            recovery.start(allowAutomaticRetry = knownGet)
            // 无法确认请求方法时，手动恢复回到最近一个明确的 GET 页面，不重放表单正文。
            if (knownGet) targetUrl = url
            pendingGetUrl = null
            navigationUrls.clear()
            showLoading()
            handler.postDelayed(timeoutAction, PageLoadRecovery.ATTEMPT_TIMEOUT_MILLIS)
        }
        if (recovery.phase != PageLoadPhase.LOADING) return
        pendingNavigation = false
        navigationUrls.add(url)
        awaitingStart = false
    }

    /** shouldOverrideUrlLoading 已放行的主文档；重定向可能先报错，再发 onPageStarted。 */
    fun onNavigationRequested(url: String, method: String) {
        navigationUrls.add(url)
        if (recovery.phase == PageLoadPhase.READY) {
            pendingNavigation = true
            pendingGetUrl = url.takeIf { method.equals("GET", true) }
        }
        if (!method.equals("GET", true)) replaySafe = false
    }

    /** WebView 请求线程调用，只更新并发集合与易变标志，不操作 UI。POST 不经过 shouldOverrideUrlLoading。 */
    fun onMainFrameRequest(url: String, method: String) {
        navigationUrls.add(url)
        if (!method.equals("GET", true)) replaySafe = false
        handler.post {
            if (recovery.phase == PageLoadPhase.READY) pendingNavigation = true
        }
    }

    fun onPageCommitVisible(url: String) {
        if (awaitingStart || url !in navigationUrls || !recovery.succeed()) return
        cancelPending()
        pendingNavigation = false
        panel.visibility = View.GONE
        webView.visibility = View.VISIBLE
        onContentReady()
    }

    fun onNetworkError(url: String, code: Int, description: String) {
        if (url in navigationUrls) fail(PageLoadFailure.network(code, description))
    }

    fun onHttpError(url: String, status: Int) {
        if (url in navigationUrls && status >= 400) fail(PageLoadFailure.http(status))
    }

    fun onSslError(url: String) {
        if (url in navigationUrls) fail(PageLoadFailure.ssl())
    }

    private fun fail(error: PageLoadFailure) {
        // 某些导航在 onPageStarted 前就失败，此时也必须盖住系统错误页。
        if (recovery.phase == PageLoadPhase.READY) {
            if (!pendingNavigation) return
            recovery.start(allowAutomaticRetry = false)
        }
        if (!recovery.fail(error.copy(retryable = error.retryable && replaySafe))) return
        cancelPending()
        webView.visibility = View.INVISIBLE
        panel.visibility = View.VISIBLE
        // 立即停掉底层请求，不再等 Chromium 自己的代理/连接超时。
        webView.stopLoading()
        if (recovery.phase == PageLoadPhase.WAITING_RETRY) {
            // 仅保留空白遮罩以屏蔽系统错误文档，自动重试期间不显示提示。
            panel.getChildAt(0).visibility = View.GONE
            handler.post(retryAction)
        } else {
            title.text = "页面暂时无法加载"
            detail.text = "${error.message}\n错误码：${error.code}"
            retry.visibility = View.VISIBLE
            settings.visibility = View.VISIBLE
            panel.getChildAt(0).visibility = View.VISIBLE
        }
    }

    private fun showLoading() {
        // 内容只在最终失败时显示；手动重试也立即隐藏上次错误提示。
        panel.getChildAt(0).visibility = View.GONE
        panel.visibility = View.VISIBLE
        webView.visibility = View.VISIBLE
    }

    fun onResume() {
        if (!returningFromSettings || recovery.phase == PageLoadPhase.CLOSED) return
        returningFromSettings = false
        load(targetUrl)
    }

    fun close() {
        recovery.close()
        returningFromSettings = false
        cancelPending()
        webView.stopLoading()
    }

    override fun onDetachedFromWindow() {
        close()
        super.onDetachedFromWindow()
    }

    private fun cancelPending() {
        handler.removeCallbacks(timeoutAction)
        handler.removeCallbacks(retryAction)
    }

    private fun label(size: Float) = TextView(activity).apply {
        textSize = size
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(0, 12, 0, 12)
    }
}
