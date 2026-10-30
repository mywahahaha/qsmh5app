package com.qishimeng.game

import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewCompat
import com.google.gson.Gson
import com.qishimeng.game.auth.AccountTokenStore
import com.qishimeng.game.auth.AliyunAuthAdapter
import com.qishimeng.game.auth.EncryptedAccountTokenStore
import com.qishimeng.game.auth.GameInfo
import com.qishimeng.game.auth.OnekeyConfig
import com.qishimeng.game.auth.OnekeyAuthGateway
import com.qishimeng.game.auth.OnekeyLoginService
import com.qishimeng.game.auth.TokenLoginOutcome
import com.qishimeng.game.auth.TokenLoginService
import com.qishimeng.game.bridge.BridgeActions
import com.qishimeng.game.bridge.BridgeLocalStorage
import com.qishimeng.game.bridge.ExternalApps
import com.qishimeng.game.bridge.SharedPreferencesLocalStorage
import com.qishimeng.game.bridge.BridgeJavascriptInterface
import com.qishimeng.game.channel.ChannelCodeReader
import com.qishimeng.game.config.InitConfig
import com.qishimeng.game.config.InitConfigParser
import com.qishimeng.game.config.ParseResult
import com.qishimeng.game.floatball.FloatingBallController
import com.qishimeng.game.floatball.FloatingBallView
import com.qishimeng.game.network.ApiClient
import com.qishimeng.game.network.HeartbeatResponse
import com.qishimeng.game.network.PopupHeartbeatSession
import com.qishimeng.game.network.StartupNetworkGate
import com.qishimeng.game.privacy.AndroidDeviceIdentifiers
import com.qishimeng.game.privacy.DeviceCodeProvider
import com.qishimeng.game.privacy.EncryptedDeviceCodeStore
import com.qishimeng.game.privacy.PrivacyConsentStore
import com.qishimeng.game.privacy.SharedPreferencesPrivacyStore
import com.qishimeng.game.role.GameRoleStore
import com.qishimeng.game.role.SharedPreferencesRoleStorage
import com.qishimeng.game.splash.StartupSplashView
import com.qishimeng.game.update.AndroidUpdateDownloadTask
import com.qishimeng.game.update.ApkInstaller
import com.qishimeng.game.update.UpdateDecision
import com.qishimeng.game.update.UpdateDialog
import com.qishimeng.game.update.UpdatePolicy
import com.qishimeng.game.update.UpdateService
import com.qishimeng.game.web.WebContainer
import com.qishimeng.game.web.PopupWindowCoordinator
import com.qishimeng.game.web.WebBridgePolicy
import com.qishimeng.game.web.StartupBackgroundLoader
import java.util.concurrent.Executors
import java.io.IOException

class MainActivity : AppCompatActivity() {
    private val background = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "qsm-startup").apply { isDaemon = true }
    }
    private val imageLoader = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "qsm-background-image").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val apiClient = ApiClient()
    private val onekeyAuthGateway: OnekeyAuthGateway = AliyunAuthAdapter { this }
    private lateinit var host: FrameLayout
    private lateinit var floatingBallLayer: FrameLayout
    private lateinit var startupBackground: ImageView
    private lateinit var startupSplash: StartupSplashView
    private lateinit var tokenStore: AccountTokenStore
    private lateinit var roleStore: GameRoleStore
    private lateinit var privacyStore: PrivacyConsentStore
    private lateinit var deviceCodeProvider: DeviceCodeProvider
    private lateinit var networkGate: StartupNetworkGate
    private var initConfig: InitConfig? = null
    private var webContainer: WebContainer? = null
    private var popupWindowCoordinator: PopupWindowCoordinator? = null
    private var heartbeatSession: PopupHeartbeatSession? = null
    private var floatingBallController: FloatingBallController? = null
    private var floatingBall: FloatingBallView? = null
    private var gamePageReady = false
    private var loginStarted = false
    private var updateChecked = false
    private var updateService: UpdateService? = null
    private var updateInstaller: ApkInstaller? = null
    private var externalBrowserOrientation: Int? = null
    private var pausedForExternalBrowser = false
    private var externalBrowserLaunched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterImmersiveFullscreen()
        host = FrameLayout(this)
        val root = FrameLayout(this)
        val fullscreen = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        root.addView(host, fullscreen)
        // 独立于 WebView 的叠放顺序，避免游戏刷新或窗口置顶盖住悬浮球。
        floatingBallLayer = FrameLayout(this)
        root.addView(floatingBallLayer, FrameLayout.LayoutParams(fullscreen))
        startupSplash = StartupSplashView(this)
        root.addView(startupSplash, FrameLayout.LayoutParams(fullscreen))
        setContentView(root)
        startupBackground = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        host.addView(
            startupBackground,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        tokenStore = EncryptedAccountTokenStore(this)
        roleStore = GameRoleStore(SharedPreferencesRoleStorage(this))
        privacyStore = PrivacyConsentStore(SharedPreferencesPrivacyStore(this))
        deviceCodeProvider = DeviceCodeProvider(
            AndroidDeviceIdentifiers(this),
            EncryptedDeviceCodeStore(this)
        )
        networkGate = StartupNetworkGate(
            this,
            onReady = {
                showMessage("正在初始化游戏…")
                initializeGame()
            },
            onUnavailable = {
                startupSplash.dismiss()
                showMessage("请连接网络，并允许本游戏联网后重试。")
            }
        )
        networkGate.runWhenConnected()
    }

    override fun onResume() {
        super.onResume()
        if (pausedForExternalBrowser) restoreExternalBrowserOrientation()
        if (::networkGate.isInitialized) networkGate.onResume()
        updateInstaller?.onResume()
        webContainer?.onResume()
    }

    override fun onPause() {
        if (externalBrowserLaunched) pausedForExternalBrowser = true
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveFullscreen()
    }

    override fun onDestroy() {
        heartbeatSession?.close()
        heartbeatSession = null
        if (::networkGate.isInitialized) networkGate.close()
        updateInstaller?.close()
        updateService?.destroy()
        gamePageReady = false
        floatingBallController = null
        floatingBall?.destroy()
        floatingBall = null
        webContainer?.instances()?.forEach { instance -> webContainer?.close(instance.spec.instance) }
        background.shutdownNow()
        imageLoader.shutdownNow()
        super.onDestroy()
    }

    private fun initializeGame() {
        background.execute {
            val result = runCatching {
                val response = apiClient.post(
                    ApiClient.INIT_URL,
                    "{}",
                    gameInfo(),
                    privacyStore.isAgreed()
                )
                InitConfigParser.parse(Gson().toJson(response))
            }
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                networkGate.onRequestFinished()
                result.fold(onSuccess = { parsed ->
                    when (parsed) {
                        is ParseResult.Success -> onInitialized(parsed.value)
                        is ParseResult.Failure -> {
                            startupSplash.dismiss()
                            showMessage("游戏初始化失败：${parsed.reason}")
                        }
                    }
                }, onFailure = { error ->
                    if (error is IOException || error is SecurityException) {
                        networkGate.onConnectionFailure()
                    } else {
                        startupSplash.dismiss()
                        showMessage("初始化失败：${error.message ?: "请重新启动游戏"}")
                    }
                })
            }
        }
    }

    private fun onInitialized(config: InitConfig) {
        heartbeatSession?.close()
        heartbeatSession = null
        initConfig = config
        gamePageReady = false
        floatingBall?.destroy()
        floatingBall = null
        floatingBallController = config.floatingBall?.let { FloatingBallController(it.show) }
        clearForegroundViews()
        loadStartupBackground(config.bgImage)
        webContainer = WebContainer(
            activity = this,
            host = host,
            pages = config.pages,
            jumpDomains = config.jumpDomains,
            onClosed = { instance ->
                if (instance == "web_game") {
                    gamePageReady = false
                    stopPopupHeartbeat()
                    floatingBallController?.resetSession()
                }
                refreshFloatingBall()
            },
            onExternalNavigation = ::openExternalUrl,
            bridgeInstaller = { view, instance -> installBridge(view, instance) },
            operationalBridgeInstaller = { view, instance, trustedUrl -> installBridge(view, instance, trustedUrl) },
            onPageVisible = { instance ->
                startupSplash.onContentReady()
                if (instance == "web_game") {
                    // 每次游戏主文档成功提交后清空所有游戏角色，等待本次页面重新上报。
                    roleStore.clear()
                    gamePageReady = true
                }
                refreshFloatingBall()
            },
            onOpened = { refreshFloatingBall() }
        )
        popupWindowCoordinator = webContainer?.let { container ->
            PopupWindowCoordinator(container, config.pages.values.map { it.instance }.toSet())
        }
        heartbeatSession = PopupHeartbeatSession(
            config.heartbeatUrl,
            config.heartbeatIntervalSeconds,
            poll = { url -> apiClient.postHeartbeat(url, gameInfo(), privacyStore.isAgreed()) },
            dispatch = { action -> mainHandler.post { action() } },
            onResponse = ::applyHeartbeatResponse
        )
        if (privacyStore.shouldShowAgreement(config.privacyAuto)) {
            if (webContainer?.openConfigured("agreement") == null) {
                showMessage("隐私协议页面配置缺失，无法继续启动。")
            }
        } else {
            beginUpdateFlow()
        }
    }

    private fun beginUpdateFlow() {
        if (updateChecked || isFinishing || isDestroyed) return
        updateChecked = true
        when (val decision = UpdatePolicy.evaluate(initConfig?.updateInfo.orEmpty())) {
            UpdateDecision.Continue -> beginLoginFlow()
            is UpdateDecision.Abort -> {
                Toast.makeText(this, decision.message, Toast.LENGTH_LONG).show()
                beginLoginFlow()
            }
            is UpdateDecision.Offer -> {
                startupSplash.dismiss()
                lateinit var service: UpdateService
                val installer = ApkInstaller(this, onError = { service.fail(it) })
                val dialog = UpdateDialog(this, decision.info,
                    onUpdate = { service.update() },
                    onInstall = { service.installReady() },
                    onClose = { service.close() }
                )
                service = UpdateService(
                    decision.info,
                    AndroidUpdateDownloadTask(this),
                    installer::install,
                    { url -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)) },
                    dialog::render,
                    { installer.close(); dialog.dismiss() },
                    { message -> Toast.makeText(this, message, Toast.LENGTH_LONG).show() },
                    ::beginLoginFlow
                )
                updateInstaller = installer
                updateService = service
                try {
                    dialog.show()
                    service.start()
                } catch (_: Exception) {
                    service.fail("更新窗口无法显示，请稍后重试")
                }
            }
        }
    }

    private fun beginLoginFlow() {
        if (loginStarted || isFinishing || isDestroyed) return
        loginStarted = true
        background.execute {
            val outcome = TokenLoginService(tokenStore, apiClient, gameInfo()).loginSavedToken(privacyStore.isAgreed())
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                if (outcome is TokenLoginOutcome.Success) {
                    openGamePage()
                } else {
                    beginOnekeyOrH5Login()
                }
            }
        }
    }

    private fun beginOnekeyOrH5Login() {
        val config = initConfig ?: return
        if (!config.aliyunOnekeyOpen) {
            openH5LoginPage()
            return
        }
        val service = createOnekeyLoginService()
        service.login(createOnekeyConfig(config), privacyStore.isAgreed()) { outcome ->
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                if (outcome is com.qishimeng.game.auth.OneKeyLoginOutcome.Success) openGamePage()
                else openH5LoginPage()
            }
        }
    }

    private fun openH5LoginPage() {
        if (webContainer?.openConfigured("login") == null) {
            showMessage("H5 登录页面配置缺失，无法继续启动。")
        }
    }

    private fun openGamePage() {
        if (webContainer?.openConfigured("game") == null) {
            showMessage("游戏页面配置缺失，登录已完成。")
            return
        }
        webContainer?.close("web_login")
    }

    private fun refreshFloatingBall() {
        val controller = floatingBallController ?: return
        val container = webContainer ?: return
        val sessionReady = gamePageReady && container.hasWindow("web_game") && !tokenStore.token.isNullOrBlank() &&
            !isFinishing && !isDestroyed
        controller.setSessionReady(sessionReady)
        // 大厅可以保留在游戏后方；只在大厅位于游戏上方时临时隐藏球。
        val windows = container.windowNames()
        controller.setWelfareOpen(windows.indexOf("web_welfare") > windows.indexOf("web_game"))
        val state = controller.state()
        if (state.visible && floatingBall == null) {
            val config = initConfig?.floatingBall ?: return
            floatingBall = FloatingBallView(this, floatingBallLayer, config, state.active) {
                webContainer?.openConfigured("welfare")
            }
        }
        floatingBall?.setActive(state.active)
        floatingBall?.show(state.visible)
        heartbeatSession?.update(sessionReady, state.visible && floatingBall != null)
    }

    private fun stopPopupHeartbeat() {
        heartbeatSession?.stop()
    }

    private fun applyHeartbeatResponse(response: HeartbeatResponse) {
        if (response.code != 0) return
        response.floatingBall?.let { state ->
            floatingBallController?.setRuntimeState(state.show, state.active)
        }
        popupWindowCoordinator?.apply(response)
        refreshFloatingBall()
    }

    private fun setFloatingBall(show: Boolean, active: Boolean): Boolean {
        val controller = floatingBallController ?: return false
        controller.setRuntimeState(show, active)
        refreshFloatingBall()
        return true
    }

    private fun installBridge(webView: WebView, instance: String, operationalTrustedUrl: String? = null) {
        val actions = BridgeActions(
            privacyStore = privacyStore,
            tokenStore = tokenStore,
            gameInfo = ::gameInfo,
            roleStore = roleStore,
            localStorage = BridgeLocalStorage(SharedPreferencesLocalStorage(this)),
            externalApps = ExternalApps(this),
            webContainer = webContainer ?: return,
            onekeyLoginService = createOnekeyLoginService(),
            onekeyConfig = { initConfig?.let(::createOnekeyConfig) ?: OnekeyConfig(false, "", "") },
            setFloatingBall = ::setFloatingBall,
            currentInstance = instance,
            onLoginSuccess = { mainHandler.post(::openGamePage) },
            onPrivacyAgreed = {
                mainHandler.post {
                    webContainer?.close("web_agreement")
                    beginUpdateFlow()
                }
            },
            onLogout = {
                mainHandler.post {
                    stopPopupHeartbeat()
                    floatingBallController?.resetSession()
                    webContainer?.instances()?.forEach { webContainer?.close(it.spec.instance) }
                    gamePageReady = false
                    refreshFloatingBall()
                    loginStarted = true
                    // 退出后从认证分支重新开始，不再尝试旧 Token 自动登录。
                    beginOnekeyOrH5Login()
                }
            },
            onPrivacyRejected = { mainHandler.post { finishAffinity() } },
            onPhoneLoginFallback = { mainHandler.post(::openH5LoginPage) }
        )
        val bridge = BridgeJavascriptInterface(webView, actions) {
            webContainer?.get(instance)?.webView === webView
        }
        if (operationalTrustedUrl == null) {
            webView.addJavascriptInterface(bridge, BridgeJavascriptInterface.INTERFACE_NAME)
        } else {
            WebViewCompat.addWebMessageListener(webView, BridgeJavascriptInterface.INTERFACE_NAME, setOf("*")) {
                    _, message, sourceOrigin, isMainFrame, _ ->
                val payload = message.data
                if (payload != null && WebBridgePolicy.acceptsMessage(sourceOrigin.toString(), operationalTrustedUrl, isMainFrame)) {
                    bridge.postMessage(payload)
                }
            }
        }
    }

    private fun createOnekeyLoginService(): OnekeyLoginService = OnekeyLoginService(
        tokenStore = tokenStore,
        authGateway = onekeyAuthGateway,
        apiClient = apiClient,
        gameInfo = gameInfo()
    )

    private fun createOnekeyConfig(config: InitConfig): OnekeyConfig = OnekeyConfig(
        open = config.aliyunOnekeyOpen,
        schemeSecret = config.aliyunSchemeSecret.orEmpty(),
        exchangeUrl = config.aliyunExchangeUrl.orEmpty(),
        screenCode = config.aliyunScreenCode,
        dialogWidthPercent = config.aliyunDialogWidthPercent,
        dialogHeightPercent = config.aliyunDialogHeightPercent,
        title = config.aliyunTitle,
        loginText = config.aliyunLoginText,
        otherLoginText = config.aliyunOtherLoginText,
        primaryColor = config.aliyunPrimaryColor,
        protocols = config.aliyunProtocols,
        totalTimeoutMillis = config.aliyunTotalTimeoutMillis
    )

    private fun gameInfo(): GameInfo {
        val agreed = privacyStore.isAgreed()
        return GameInfo(
            packageCode = ChannelCodeReader.read(applicationInfo.sourceDir),
            gameId = BuildConfig.GAME_ID,
            gameVersion = BuildConfig.GAME_VERSION,
            accountToken = tokenStore.token.orEmpty(),
            deviceType = "android",
            deviceCode = deviceCodeProvider.get(agreed)
        )
    }

    private fun enterImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    private fun loadStartupBackground(url: String?) {
        startupBackground.setImageDrawable(null)
        startupBackground.visibility = View.GONE
        if (url.isNullOrBlank()) return

        val metrics = resources.displayMetrics
        imageLoader.execute {
            val bitmap = runCatching {
                StartupBackgroundLoader.load(url, metrics.widthPixels, metrics.heightPixels)
            }.getOrNull() ?: return@execute
            mainHandler.post {
                if (isFinishing || isDestroyed) {
                    bitmap.recycle()
                    return@post
                }
                startupBackground.setImageBitmap(bitmap)
                startupBackground.visibility = View.VISIBLE
            }
        }
    }

    private fun clearForegroundViews() {
        if (host.childCount > 1) host.removeViews(1, host.childCount - 1)
    }

    private fun openExternalUrl(url: String) {
        if (externalBrowserOrientation != null) return
        externalBrowserOrientation = requestedOrientation
        pausedForExternalBrowser = false
        externalBrowserLaunched = true
        runCatching {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            startActivity(createExternalBrowserIntent(url))
        }.onFailure {
            restoreExternalBrowserOrientation()
            Toast.makeText(this, "无法使用浏览器打开链接", Toast.LENGTH_SHORT).show()
        }
    }

    private fun restoreExternalBrowserOrientation() {
        val previous = externalBrowserOrientation ?: return
        externalBrowserOrientation = null
        pausedForExternalBrowser = false
        externalBrowserLaunched = false
        requestedOrientation = previous
    }

    private fun showMessage(message: String) {
        if (initConfig != null) startupSplash.dismiss()
        clearForegroundViews()
        host.addView(TextView(this).apply {
            text = message
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
            gravity = Gravity.CENTER
            textSize = 16f
            setPadding(32, 32, 32, 32)
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }
}

internal fun createExternalBrowserIntent(url: String): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
