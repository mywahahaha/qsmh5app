package com.qishimeng.game.web

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Message
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.SslErrorHandler
import android.net.http.SslError
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.qishimeng.game.config.PageSpec
import com.qishimeng.game.bridge.BridgeScript

class WebContainer(
    private val activity: Activity,
    private val host: FrameLayout,
    pages: Map<String, PageSpec>,
    private val jumpDomains: Set<String>,
    private val onClosed: (String) -> Unit = {},
    private val onExternalNavigation: (String) -> Unit = {},
    private val bridgeInstaller: ((WebView, String) -> Unit)? = null,
    private val onPageVisible: (String) -> Unit = {},
    private val onOpened: (String) -> Unit = {},
    private val operationalBridgeInstaller: ((WebView, String, String) -> Unit)? = null
) : PopupWindowHost {
    private val registry = WebInstanceRegistry()
    private val pageSpecs = pages.toMutableMap()
    private val instances = linkedMapOf<String, WebInstance>()
    private val pendingPopups = mutableMapOf<WebView, MutableSet<WebView>>()

    @SuppressLint("SetJavaScriptEnabled")
    fun open(spec: PageSpec, operational: Boolean = false): WebInstance? {
        if (!UrlPolicy.isAllowedPageUrl(spec.url)) return null
        instances[spec.instance]?.let {
            bringToFront(spec.instance)
            onOpened(spec.instance)
            return it
        }

        val trustedUrl = WebBridgePolicy.trustedUrl(spec, pageSpecs.values, operational)
        // 不为运营页回退到无法识别 frame 来源的 JavascriptInterface。
        val bridgeTrusted = trustedUrl != null && (!operational ||
            (operationalBridgeInstaller != null && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)))
        val webView = WebView(activity)
        val pageView = WebPageView(activity, webView) { onPageVisible(spec.instance) }
        pageView.alpha = 0f
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.settings.javaScriptEnabled = true
        webView.settings.javaScriptCanOpenWindowsAutomatically = true
        webView.settings.domStorageEnabled = true
        webView.settings.setSupportMultipleWindows(true)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                val popup = WebView(activity)
                pendingPopups.getOrPut(view) { mutableSetOf() }.add(popup)
                fun dispose() {
                    if (pendingPopups[view]?.remove(popup) == true) popup.destroy()
                    if (pendingPopups[view]?.isEmpty() == true) pendingPopups.remove(view)
                }
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(popupView: WebView, request: WebResourceRequest): Boolean {
                        if (request.isForMainFrame && instances[spec.instance]?.webView === view &&
                            UrlPolicy.isAllowedExternalBrowserUrl(request.url.toString(), jumpDomains)) {
                            onExternalNavigation(request.url.toString())
                        }
                        popupView.post { dispose() }
                        return true
                    }
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = popup
                resultMsg.sendToTarget()
                popup.postDelayed({ dispose() }, 5_000L)
                return true
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                pageView.onPageStarted(url)
                injectBridgeFallback(view, url)
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                pageView.onPageCommitVisible(url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                injectBridgeFallback(view, url)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) pageView.onNetworkError(request.url.toString(), error.errorCode, error.description.toString())
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) pageView.onHttpError(request.url.toString(), response.statusCode)
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                pageView.onSslError(error.url)
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.isForMainFrame) pageView.onMainFrameRequest(request.url.toString(), request.method)
                return null
            }

            private fun injectBridgeFallback(view: WebView, url: String) {
                if (bridgeTrusted && UrlPolicy.isAllowedGameUrl(url, trustedUrl)) {
                    view.evaluateJavascript(BridgeScript.SOURCE, null)
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val target = request.url.toString()
                val initialHost = Uri.parse(spec.url).host
                val isCurrentDocumentReload = request.isForMainFrame &&
                    request.method.equals("GET", ignoreCase = true) &&
                    sameDocumentUrl(target, view.url)
                if (isCurrentDocumentReload) {
                    pageView.onNavigationRequested(target, request.method)
                    return false
                }
                if (request.isForMainFrame && UrlPolicy.isAllowedExternalBrowserUrl(target, jumpDomains)) {
                    onExternalNavigation(target)
                    return true
                }
                if (request.url.scheme in listOf("http", "https") && request.url.host.equals(initialHost, true)) {
                    if (request.isForMainFrame) pageView.onNavigationRequested(target, request.method)
                    return false
                }
                return request.isForMainFrame
            }
        }

        val instance = WebInstance(spec, webView, bridgeTrusted, pageView)
        if (bridgeTrusted) {
            if (operational) operationalBridgeInstaller?.invoke(webView, spec.instance, trustedUrl!!)
            else bridgeInstaller?.invoke(webView, spec.instance)
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                // 保持与原有同主机信任规则一致，且只在顶层页面运行。
                val trustedHost = com.google.gson.Gson().toJson(Uri.parse(trustedUrl!!).host)
                val guardedScript = """
                    if (window === window.top &&
                        (location.protocol === 'http:' || location.protocol === 'https:') &&
                        location.hostname.toLowerCase() === $trustedHost.toLowerCase()) {
                        ${BridgeScript.SOURCE}
                    }
                """.trimIndent()
                WebViewCompat.addDocumentStartJavaScript(webView, guardedScript, setOf("*"))
            }
        }
        if (operational && !bridgeTrusted) {
            android.util.Log.w("QSMHeartbeat", "当前 WebView 不支持安全运营页 Bridge，请更新 Android System WebView")
        }
        instances[spec.instance] = instance
        registry.open(spec, operational)
        host.addView(pageView, createLayoutParams(spec))
        pageView.load(spec.url)
        pageView.animate().alpha(1f).setDuration(PAGE_TRANSITION_MILLIS).start()
        bringToFront(spec.instance)
        onOpened(spec.instance)
        return instance
    }

    override fun openWindow(spec: PageSpec, operational: Boolean) {
        open(spec, operational)
    }

    fun openConfigured(key: String): WebInstance? = pageSpecs[key]?.let(::open)

    fun openInstance(instanceName: String): WebInstance? =
        pageSpecs.values.firstOrNull { it.instance == instanceName }?.let(::open)

    fun changeGameUrl(url: String, callback: (Boolean) -> Unit = {}): Boolean {
        val gameSpec = pageSpecs["game"] ?: return false
        if (!UrlPolicy.isAllowedGameUrl(url, gameSpec.url)) return false
        val existing = instances[gameSpec.instance]
        if (existing != null) {
            existing.pageView.load(url)
            bringToFront(gameSpec.instance)
            onOpened(gameSpec.instance)
            callback(true)
            return true
        }
        val created = open(gameSpec.copy(url = url)) ?: return false
        pageSpecs["game"] = gameSpec.copy(url = url)
        callback(created.webView.url == null || created.webView.url == url)
        return true
    }

    fun close(instanceName: String): Boolean {
        val instance = instances.remove(instanceName) ?: return false
        pendingPopups.remove(instance.webView)?.forEach { it.destroy() }
        instance.pageView.close()
        instance.pageView.animate().alpha(0f).setDuration(PAGE_TRANSITION_MILLIS).withEndAction {
            host.removeView(instance.pageView)
            instance.webView.destroy()
        }.start()
        registry.close(instanceName)
        onClosed(instanceName)
        return true
    }

    override fun closeWindow(instance: String) {
        close(instance)
    }

    override fun hasWindow(instance: String): Boolean = instance in instances

    override fun windowNames(): List<String> = registry.order()

    override fun isOperationalWindow(instance: String): Boolean = registry.isOperationalWindow(instance)

    fun bringToFront(instanceName: String): Boolean {
        val instance = instances[instanceName] ?: return false
        registry.bringToFront(instanceName)
        instance.pageView.bringToFront()
        return true
    }

    fun onResume() { instances.values.forEach { it.pageView.onResume() } }

    fun get(instanceName: String): WebInstance? = instances[instanceName]

    fun instances(): List<WebInstance> = registry.order().mapNotNull(instances::get)

    private fun createLayoutParams(spec: PageSpec): FrameLayout.LayoutParams {
        val density = activity.resources.displayMetrics.density
        val viewport = FullscreenViewport.resolve(
            host.width,
            host.height,
            activity.resources.displayMetrics.widthPixels,
            activity.resources.displayMetrics.heightPixels
        )
        val layout = WindowLayout.resolve(
            spec.width,
            spec.height,
            spec.left,
            spec.top,
            viewport.widthPx,
            viewport.heightPx,
            density
        )
        return FrameLayout.LayoutParams(layout.widthPx, layout.heightPx, Gravity.TOP or Gravity.LEFT).apply {
            leftMargin = layout.leftPx
            topMargin = layout.topPx
        }
    }

    private fun sameDocumentUrl(first: String, second: String?): Boolean {
        if (second.isNullOrBlank()) return false
        val left = Uri.parse(first)
        val right = Uri.parse(second)
        return left.scheme.equals(right.scheme, true) &&
            left.host.equals(right.host, true) &&
            left.port == right.port &&
            left.path == right.path &&
            left.query == right.query
    }

    companion object {
        private const val PAGE_TRANSITION_MILLIS = 200L
    }
}
