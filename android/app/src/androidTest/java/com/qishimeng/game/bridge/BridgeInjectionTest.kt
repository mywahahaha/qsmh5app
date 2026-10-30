package com.qishimeng.game.bridge

import android.content.ContextWrapper
import android.content.Intent
import android.webkit.WebView
import android.webkit.JavascriptInterface
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import com.google.gson.Gson
import com.qishimeng.game.auth.*
import com.qishimeng.game.config.LayoutValue
import com.qishimeng.game.config.PageSpec
import com.qishimeng.game.config.InitConfigParser
import com.qishimeng.game.config.ParseResult
import com.qishimeng.game.network.ApiClient
import com.qishimeng.game.network.ApiResponse
import com.qishimeng.game.network.HttpTransport
import com.qishimeng.game.privacy.PrivacyConsentStore
import com.qishimeng.game.privacy.PrivacyPreferences
import com.qishimeng.game.privacy.AndroidDeviceIdentifiers
import com.qishimeng.game.privacy.DeviceCodeProvider
import com.qishimeng.game.privacy.EncryptedDeviceCodeStore
import com.qishimeng.game.network.GameHeaders
import com.qishimeng.game.role.GameRole
import com.qishimeng.game.role.GameRoleStore
import com.qishimeng.game.role.RoleStorage
import com.qishimeng.game.web.WebContainer
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BridgeInjectionTest {
    @get:Rule val activityRule = ActivityTestRule(BridgeTestActivity::class.java)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var server: ServerSocket
    private lateinit var container: WebContainer
    private lateinit var tokens: AccountTokenStore
    private val preferences = object : PrivacyPreferences { override var agreed = false }
    private val privacy = PrivacyConsentStore(preferences)
    private lateinit var deviceCodeProvider: DeviceCodeProvider
    private var loginToken: String? = null
    @Volatile private var receivedDeviceHeader: String? = null
    @Volatile private var receivedAccountHeader: String? = null
    private val views = mutableListOf<WebView>()
    private var phoneGateway = OnekeyAuthGateway { _, done -> done(OnekeyAuthResult.Failed) }
    private var phoneConfig = OnekeyConfig(false, "", "")
    private lateinit var phoneActions: BridgeActions
    private var phoneApi = ApiClient()
    @Volatile private var launchedApp: Intent? = null

    @Before fun setUp() {
        server = ServerSocket(0)
        Thread {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                try {
                    socket.use {
                        val reader = it.getInputStream().bufferedReader()
                        val requestLine = reader.readLine().orEmpty()
                        val headerCheck = requestLine.contains("/device-header-check")
                        var line: String? = reader.readLine()
                        while (!line.isNullOrEmpty()) {
                            if (headerCheck && line.startsWith("Devicecode:", ignoreCase = true)) {
                                receivedDeviceHeader = line.substringAfter(':').trim()
                            }
                            if (headerCheck && line.startsWith("Accountoken:", ignoreCase = true)) {
                                receivedAccountHeader = line.substringAfter(':').trim()
                            }
                            line = reader.readLine()
                        }
                        val html = """<!doctype html><html><head><script>
                        window.firstScriptBridge = typeof window.QSMH5APP;
                        if (window.QSMH5APP) QSMH5APP.getGameInfo(function(r) { window.initialInfo = r; });
                        </script></head><body>Bridge 设备测试</body></html>""".toByteArray()
                        val body = if (headerCheck) """{"code":0,"msg":"ok","data":{}}""".toByteArray() else html
                        it.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: ${if (headerCheck) "application/json" else "text/html"}; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(body)
                            flush()
                        }
                    }
                } catch (_: SocketException) {
                    // 页面关闭/导航可提前断开连接；只丢弃本次响应，不让测试服务器线程终止整个进程。
                }
            }
        }.apply { isDaemon = true; start() }
        val fixturePages = listOf("agreement", "login", "game", "welfare").associateWith { name ->
            PageSpec("web_$name", url(name), LayoutValue.Percent(100f), LayoutValue.Percent(100f), LayoutValue.Pixels(0), LayoutValue.Pixels(0))
        }
        val pages = if (livePagesEnabled()) {
            val response = ApiClient().post(ApiClient.INIT_URL, "{}", GameInfo("", 92, "1.0.1", "", "android", ""), false)
            val parsed = InitConfigParser.parse(Gson().toJson(response))
            assertTrue("线上初始化配置获取失败", parsed is ParseResult.Success)
            (parsed as ParseResult.Success).value.pages
        } else fixturePages
        val roleStore = GameRoleStore(object : RoleStorage {
            private var roles = emptyList<GameRole>()
            override fun load() = roles
            override fun save(roles: List<GameRole>) { this.roles = roles }
        })
        onMain {
            val activity = activityRule.activity
            activity.getSharedPreferences("h5_local_storage", 0).edit().clear().commit()
            activity.getSharedPreferences("account_token", 0).edit().clear().commit()
            tokens = EncryptedAccountTokenStore(activity)
            activity.getSharedPreferences("device_code", 0).edit().clear().commit()
            deviceCodeProvider = DeviceCodeProvider(AndroidDeviceIdentifiers(activity), EncryptedDeviceCodeStore(activity))
            container = WebContainer(activity, activity.host, pages, emptySet(), bridgeInstaller = { view, name ->
                views.add(view)
                val info = { GameInfo("test-channel", 92, "test", tokens.token.orEmpty(), "android", deviceCodeProvider.get(privacy.isAgreed())) }
                val actions = BridgeActions(
                    privacy, tokens, info, roleStore, BridgeLocalStorage(SharedPreferencesLocalStorage(activity)),
                    ExternalApps(object : ContextWrapper(activity) {
                        // 保留真实包查询，仅记录系统启动边界，避免测试唤起业务首页及其网络流程。
                        override fun startActivity(intent: Intent) { launchedApp = intent }
                    }), container,
                    OnekeyLoginService(tokens, phoneGateway, phoneApi, info(), java.util.concurrent.Executor(Runnable::run)),
                    { phoneConfig }, { _, _ -> false }, name,
                    { loginToken = it }, {}, {}, {}, { activity.runOnUiThread { container.openConfigured("login") } }
                )
                if (name == "web_login") phoneActions = actions
                view.addJavascriptInterface(BridgeJavascriptInterface(view, actions), BridgeJavascriptInterface.INTERFACE_NAME)
            })
        }
    }

    @After fun tearDown() {
        onMain {
            activityRule.activity.host.removeAllViews()
            views.forEach { it.stopLoading(); it.destroy() }
            tokens.token = null
            activityRule.activity.getSharedPreferences("h5_local_storage", 0).edit().clear().commit()
        }
        server.close()
    }

    @Test fun phoneLaunchRepliesWhenPresentedAndLaterCancellationDoesNotReplyAgain() {
        lateinit var authEvent: (OnekeyAuthResult) -> Unit
        val invoked = CountDownLatch(1)
        onMain {
            privacy.agree()
            phoneConfig = OnekeyConfig(true, "test-secret", url("exchange"))
            phoneGateway = OnekeyAuthGateway { _, done -> authEvent = done; invoked.countDown() }
        }
        val view = open("login")
        assertEarlyBridge(view)
        val recorder = PhoneReplyRecorder()
        val previousDocument = evaluate(view, "performance.timeOrigin")
        onMain { view.addJavascriptInterface(recorder, "PhoneReplyRecorder") }
        onMain { view.reload() }
        await(view, "performance.timeOrigin !== $previousDocument")
        assertEarlyBridge(view)
        evaluate(view, "QSMH5APP.requestPhoneLogin({},function(r){PhoneReplyRecorder.record(JSON.stringify(r));});")
        assertTrue("请求必须进入原生认证", invoked.await(5, TimeUnit.SECONDS))
        onMain { authEvent(OnekeyAuthResult.Presented) }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (recorder.result == null && System.nanoTime() < deadline) Thread.sleep(25)
        assertNotNull("展示成功必须立即回调，不能等待取消或认证结果", recorder.result)
        val reply = com.google.gson.JsonParser.parseString(recorder.result).asJsonObject
        assertEquals(0, reply.get("code").asInt)
        assertTrue(reply.get("data").asBoolean)
        instrumentation.waitForIdleSync()
        onMain { assertNull("回调送达后必须关闭 H5 登录窗口", container.get("web_login")) }
        onMain { authEvent(OnekeyAuthResult.Cancelled) }
        instrumentation.waitForIdleSync()
        assertEquals(1, recorder.count)
        onMain {
            assertNotNull("取消后必须恢复 H5 登录窗口", container.get("web_login"))
            assertNotSame("取消后应创建新的 H5 登录窗口", view, container.get("web_login")?.webView)
        }
        assertNull(loginToken)
    }

    @Test fun phoneLaunchFailureKeepsLoginWindowAndReturnsOnlyOnce() {
        lateinit var event: (OnekeyAuthResult) -> Unit
        onMain {
            privacy.agree()
            phoneConfig = OnekeyConfig(true, "secret", url("exchange"))
            phoneGateway = OnekeyAuthGateway { _, done -> event = done }
        }
        val view = open("login")
        val replies = mutableListOf<BridgeResult>()
        onMain {
            phoneActions.dispatch("requestPhoneLogin", emptyMap<String, Any>()) { result, delivered -> replies.add(result); delivered() }
            event(OnekeyAuthResult.Failed)
            event(OnekeyAuthResult.Presented)
            event(OnekeyAuthResult.Cancelled)
            assertEquals(listOf(3001), replies.map { it.code })
            assertSame(view, container.get("web_login")!!.webView)
        }
    }

    @Test fun phoneCancellationBeforeJavascriptDeliveryWaitsForCloseThenRestoresLogin() {
        lateinit var event: (OnekeyAuthResult) -> Unit
        onMain {
            privacy.agree()
            phoneConfig = OnekeyConfig(true, "secret", url("exchange"))
            phoneGateway = OnekeyAuthGateway { _, done -> event = done }
        }
        val view = open("login")
        val replies = mutableListOf<BridgeResult>()
        lateinit var delivered: () -> Unit
        onMain {
            phoneActions.dispatch("requestPhoneLogin", null) { result, after -> replies.add(result); delivered = after }
            event(OnekeyAuthResult.Presented)
            event(OnekeyAuthResult.Presented)
            event(OnekeyAuthResult.Cancelled)
            event(OnekeyAuthResult.Failed)
            assertSame("成功回调送达前不能销毁 H5", view, container.get("web_login")!!.webView)
            assertEquals(listOf(0), replies.map { it.code })
            delivered()
            assertNotNull(container.get("web_login"))
            assertNotSame(view, container.get("web_login")!!.webView)
            val restored = container.get("web_login")
            delivered()
            assertSame("重复交付不能关闭新窗口", restored, container.get("web_login"))
        }
    }

    @Test fun phoneAuthenticationAfterLaunchStoresTokenAndEntersGameWithoutAnotherBridgeReply() {
        lateinit var event: (OnekeyAuthResult) -> Unit
        onMain {
            privacy.agree()
            phoneConfig = OnekeyConfig(true, "secret", url("exchange"))
            phoneGateway = OnekeyAuthGateway { _, done -> event = done }
            phoneApi = ApiClient(HttpTransport { ApiResponse(200, """{"code":0,"data":{"token":"phone-account-token"}}""") })
        }
        open("login")
        val replies = mutableListOf<BridgeResult>()
        onMain {
            phoneActions.dispatch("requestPhoneLogin", null) { result, delivered -> replies.add(result); delivered() }
            event(OnekeyAuthResult.Presented)
            assertNull("展示成功不等于游戏登录成功", loginToken)
            event(OnekeyAuthResult.Success("auth-token"))
            event(OnekeyAuthResult.Failed)
            assertEquals("phone-account-token", tokens.token)
            assertEquals("phone-account-token", loginToken)
            assertEquals(listOf(0), replies.map { it.code })
        }
    }

    @Test fun privacyPageCanCallBridgeFromItsFirstScript() {
        val view = open("agreement")
        assertEarlyBridge(view)
        evaluate(view, "QSMH5APP.agreePrivacyPolicy(function(r){window.agreedResult=r;});")
        await(view, "window.agreedResult && window.agreedResult.code === 0")
        assertTrue(privacy.isAgreed())
    }

    @Test fun appMethodsReturnBooleanResultsAndRejectInvalidPackageNames() {
        val view = open("game")
        assertEarlyBridge(view)
        val ownPackage = activityRule.activity.packageName
        evaluate(view, "QSMH5APP.isAppInstalled({packageName:'$ownPackage'},function(r){window.installedApp=r;});")
        await(view, "window.installedApp != null")
        assertEquals("0", evaluate(view, "window.installedApp.code"))
        assertEquals("true", evaluate(view, "window.installedApp.data"))
        evaluate(view, "QSMH5APP.openApp({packageName:'$ownPackage'},function(r){window.openedApp=r;});")
        await(view, "window.openedApp != null")
        assertEquals("0", evaluate(view, "window.openedApp.code"))
        assertEquals("true", evaluate(view, "window.openedApp.data"))
        assertEquals(ownPackage, launchedApp!!.component!!.packageName)
        evaluate(view, "QSMH5APP.openApp({packageName:'android'},function(r){window.nonLaunchableApp=r;});")
        await(view, "window.nonLaunchableApp != null")
        assertEquals("0", evaluate(view, "window.nonLaunchableApp.code"))
        assertEquals("false", evaluate(view, "window.nonLaunchableApp.data"))
        for (method in listOf("isAppInstalled", "openApp")) {
            evaluate(view, "window.missingApp=null;QSMH5APP.$method({packageName:'com.qishimeng.definitely_missing'},function(r){window.missingApp=r;});")
            await(view, "window.missingApp != null")
            assertEquals("0", evaluate(view, "window.missingApp.code"))
            assertEquals("false", evaluate(view, "window.missingApp.data"))
            for (args in listOf("{}", "{packageName:123}", "{packageName:null}", "{packageName:''}", "{packageName:'   '}", "{packageName:'com.bad name'}", "{packageName:'intent://bad'}")) {
                evaluate(view, "window.invalidApp=null;QSMH5APP.$method($args,function(r){window.invalidApp=r;});")
                await(view, "window.invalidApp != null")
                assertEquals("1001", evaluate(view, "window.invalidApp.code"))
                assertEquals("false", evaluate(view, "window.invalidApp.data"))
            }
        }
    }

    @Test fun appMethodsRejectNonObjectArgumentsWithoutLosingCallback() {
        val view = open("game")
        assertEarlyBridge(view)
        for (method in listOf("isAppInstalled", "openApp")) {
            for (args in listOf("'com.me74.qsmhz'", "[]", "123", "true")) {
                evaluate(view, "window.invalidAppShape=null;QSMH5APP.$method($args,function(r){window.invalidAppShape=r;});")
                await(view, "window.invalidAppShape != null")
                assertEquals("1001", evaluate(view, "window.invalidAppShape.code"))
                assertEquals("false", evaluate(view, "window.invalidAppShape.data"))
            }
        }
    }

    @Test fun allTrustedPagesShareLocalStorageWithoutLoginOrPrivacyConsent() {
        val writer = open("agreement")
        assertEarlyBridge(writer)
        evaluate(writer, "QSMH5APP.saveLocalStorage([{key:'abc',value:123},{key:'def',value:456},{key:'flag',value:true},{key:'object',value:{a:1}},{key:'array',value:[1]},{key:'nil',value:null},{key:'absent',value:undefined}],function(r){window.savedLocal=r;});")
        await(writer, "window.savedLocal != null")
        assertEquals("0", evaluate(writer, "window.savedLocal.code"))
        assertEquals("true", evaluate(writer, "window.savedLocal.data"))
        for (page in listOf("agreement", "login", "game", "welfare")) {
            val view = open(page)
            assertEarlyBridge(view)
            evaluate(view, "QSMH5APP.getLocalStorage({key:'abc'},function(r){window.loadedLocal=r;});")
            await(view, "window.loadedLocal != null")
            assertEquals("0", evaluate(view, "window.loadedLocal.code"))
            assertEquals("\"123\"", evaluate(view, "window.loadedLocal.data"))
            evaluate(view, "QSMH5APP.saveLocalStorage({key:'writer',value:'$page'},function(r){window.pageSavedLocal=r;});")
            await(view, "window.pageSavedLocal && window.pageSavedLocal.data === true")
        }
        for (key in listOf("flag", "object", "array", "nil", "absent", "missing")) {
            evaluate(writer, "window.emptyLocal=null;QSMH5APP.getLocalStorage({key:'$key'},function(r){window.emptyLocal=r;});")
            await(writer, "window.emptyLocal != null")
            assertEquals("\"\"", evaluate(writer, "window.emptyLocal.data"))
        }
        assertFalse(privacy.isAgreed())
        assertNull(tokens.token)
        assertTrue("undefined 属性也必须写入 key", activityRule.activity.getSharedPreferences("h5_local_storage", 0).contains("absent"))
    }

    @Test fun localStorageOverwritesAndSurvivesLogout() {
        val view = open("login")
        assertEarlyBridge(view)
        evaluate(view, "QSMH5APP.saveLocalStorage([{key:'abc',value:'old'},{key:'def',value:456}],function(){QSMH5APP.saveLocalStorage([{key:'abc',value:'first'},{key:'abc',value:123}],function(){QSMH5APP.logout(function(){QSMH5APP.getLocalStorage({key:'abc'},function(r){window.afterLogoutLocal=r;});});});});")
        await(view, "window.afterLogoutLocal != null")
        assertEquals("\"123\"", evaluate(view, "window.afterLogoutLocal.data"))
        evaluate(view, "QSMH5APP.getLocalStorage({key:'def'},function(r){window.keptLocal=r;});")
        await(view, "window.keptLocal != null")
        assertEquals("\"456\"", evaluate(view, "window.keptLocal.data"))
        val restored = BridgeLocalStorage(SharedPreferencesLocalStorage(activityRule.activity))
        assertEquals("123", restored.get("abc"))
        assertEquals("456", restored.get("def"))
        val file = java.io.File(activityRule.activity.applicationInfo.dataDir, "shared_prefs/h5_local_storage.xml")
        val xml = file.readText()
        assertTrue("成功回调前数据须已落盘", xml.contains(">123</string>"))
    }

    @Test fun localStorageRejectsNonStringKeysWithStringData() {
        val view = open("login")
        assertEarlyBridge(view)
        for (args in listOf("{}", "{key:123}", "{key:null}", "{key:true}", "{key:[]}")) {
            evaluate(view, "window.invalidLocal=null;QSMH5APP.getLocalStorage($args,function(r){window.invalidLocal=r;});")
            await(view, "window.invalidLocal != null")
            assertEquals("1001", evaluate(view, "window.invalidLocal.code"))
            assertEquals("\"\"", evaluate(view, "window.invalidLocal.data"))
        }
    }

    @Test fun localStoragePreservesLargeJsonNumbersWithoutDoubleRounding() {
        val view = open("game")
        assertEarlyBridge(view)
        evaluate(view, "QSMH5APP.saveLocalStorage([{key:'large',value:1000000000000000100},{key:'exponent',value:1e23},{key:'tiny',value:0.0000001}],function(r){window.savedNumbers=r;});")
        await(view, "window.savedNumbers && window.savedNumbers.data === true")
        for ((key, expected) in mapOf("large" to "1000000000000000100", "exponent" to "100000000000000000000000", "tiny" to "0.0000001")) {
            evaluate(view, "window.loadedNumber=null;QSMH5APP.getLocalStorage({key:'$key'},function(r){window.loadedNumber=r;});")
            await(view, "window.loadedNumber != null")
            assertEquals("\"$expected\"", evaluate(view, "window.loadedNumber.data"))
        }
    }

    @Test fun consentMakesDeviceCodeAvailableToH5StorageAndRequestHeaders() {
        val view = open("agreement")
        assertEarlyBridge(view)
        assertEquals("\"\"", evaluate(view, "window.initialInfo.data.deviceCode"))
        onMain { assertNull(EncryptedDeviceCodeStore(activityRule.activity).deviceCode) }
        evaluate(view, "QSMH5APP.agreePrivacyPolicy(function(r){window.agreedResult=r;});")
        await(view, "window.agreedResult && window.agreedResult.code === 0")
        onMain { assertNotNull("同意回调返回前应已保存设备码", EncryptedDeviceCodeStore(activityRule.activity).deviceCode) }
        evaluate(view, "QSMH5APP.getGameInfo(function(info){window.afterConsentInfo=info;});")
        await(view, "window.afterConsentInfo != null")
        val code = Gson().fromJson(evaluate(view, "window.afterConsentInfo.data.deviceCode"), String::class.java)
        assertTrue("同意后 H5 应收到 32 位设备码", code.matches(Regex("[0-9A-Fa-f]{32}")))
        onMain {
            val store = EncryptedDeviceCodeStore(activityRule.activity)
            assertEquals(code, store.deviceCode)
            val restored = DeviceCodeProvider(AndroidDeviceIdentifiers(activityRule.activity), EncryptedDeviceCodeStore(activityRule.activity)).get(true)
            assertEquals(code, restored)
            assertEquals(code, GameHeaders.toMap(GameInfo("", 92, "test", "", "android", restored), true)["Devicecode"])
        }
        val info = GameInfo("", 92, "test", "", "android", code)
        ApiClient().post(url("device-header-check"), "{}", info, true)
        assertEquals("实际 HTTP 请求须携带同一个设备码", code, receivedDeviceHeader)
        evaluate(view, "QSMH5APP.rejectPrivacyPolicy(function(r){QSMH5APP.getGameInfo(function(info){window.revokedInfo=info;});});")
        await(view, "window.revokedInfo != null")
        assertEquals("\"\"", evaluate(view, "window.revokedInfo.data.deviceCode"))
        ApiClient().post(url("device-header-check"), "{}", info, false)
        assertEquals("撤回隐私同意后的实际 HTTP 请求不得携带设备码", "", receivedDeviceHeader)
    }

    @Test fun loginPageCanCallBridgeFromItsFirstScriptAndNotifyLogin() {
        val view = open("login")
        assertEarlyBridge(view)
        evaluate(view, "QSMH5APP.notifyLoginSuccess({accountToken:'device-test-token'},function(r){window.loginResult=r;});")
        await(view, "window.loginResult && window.loginResult.code === 0")
        assertEquals("device-test-token", tokens.token)
        assertEquals("device-test-token", loginToken)
    }

    @Test fun rejectedSavedTokenClearsBridgeHeadersAndPersistentStorageAndSkipsNextLogin() {
        onMain { privacy.agree(); tokens.token = "expired-device-token" }
        val view = open("login")
        assertEarlyBridge(view)
        assertEquals("\"expired-device-token\"", evaluate(view, "window.initialInfo.data.accountToken"))
        val snapshot = GameInfo("", 92, "test", "expired-device-token", "android", "")
        val loginApi = ApiClient(HttpTransport { request ->
            assertEquals("POST", request.method)
            assertEquals(TokenLoginService.LOGIN_BY_TOKEN_URL, request.url)
            assertEquals("expired-device-token", request.headers["Accountoken"])
            ApiResponse(200, """{"code":9,"msg":"rejected","data":{"token":"must-not-save"}}""")
        })
        assertEquals(TokenLoginOutcome.Fallback, TokenLoginService(tokens, loginApi, snapshot).loginSavedToken(true))
        assertNull("业务非 0 后须先清本地 Token", tokens.token)
        evaluate(view, "QSMH5APP.getGameInfo(function(r){window.afterTokenRejection=r;});QSMH5APP.getAccountToken(function(r){window.afterTokenValue=r;});")
        await(view, "window.afterTokenRejection && window.afterTokenValue")
        assertEquals("\"\"", evaluate(view, "window.afterTokenRejection.data.accountToken"))
        assertEquals("\"\"", evaluate(view, "window.afterTokenValue.data.accountToken"))

        // 已创建的一键登录 service 持有旧 GameInfo 快照，兑换时也必须使用存储中的最新 Token。
        val exchangeApi = ApiClient(HttpTransport { request ->
            assertEquals("", request.headers["Accountoken"])
            ApiResponse(200, """{"code":9,"msg":"rejected"}""")
        })
        val onekey = OnekeyLoginService(tokens, OnekeyAuthGateway { _, done -> done(OnekeyAuthResult.Success("test-auth-token")) },
            exchangeApi, snapshot, java.util.concurrent.Executor(Runnable::run))
        var onekeyOutcome: OneKeyLoginOutcome? = null
        onekey.login(OnekeyConfig(true, "test-secret", url("exchange")), true) { onekeyOutcome = it }
        assertEquals(OneKeyLoginOutcome.Fallback, onekeyOutcome)

        val restored = EncryptedAccountTokenStore(activityRule.activity)
        assertNull("重建存储后不可恢复失效 Token", restored.token)
        val freshInfo = snapshot.copy(accountToken = restored.token.orEmpty())
        ApiClient().post(url("device-header-check"), "{}", freshInfo, true)
        assertEquals("清理后的真实 HTTP Header 应为空", "", receivedAccountHeader)
        val noRequest = ApiClient(HttpTransport { error("清空后不得再次请求 Token 登录") })
        assertEquals(TokenLoginOutcome.Skipped, TokenLoginService(restored, noRequest, freshInfo).loginSavedToken(true))

        // 不额外补刷盘，检查生产删除路径写出的实际 XML。
        val persistedFile = java.io.File(activityRule.activity.applicationInfo.dataDir, "shared_prefs/account_token.xml")
        assertFalse("磁盘中不得残留 Token 密文", persistedFile.readText().contains("name=\"encrypted\""))
    }

    @Test fun successfulSavedTokenRefreshUpdatesPersistentStorageBridgeAndRequestHeaders() {
        onMain { privacy.agree(); tokens.token = "old-device-token" }
        val snapshot = GameInfo("", 92, "test", "old-device-token", "android", "")
        val loginApi = ApiClient(HttpTransport { request ->
            assertEquals(TokenLoginService.LOGIN_BY_TOKEN_URL, request.url)
            assertEquals("old-device-token", request.headers["Accountoken"])
            ApiResponse(200, """{"code":0,"msg":"ok","data":{"token":"refreshed-device-token"}}""")
        })
        assertEquals(TokenLoginOutcome.Success("refreshed-device-token"), TokenLoginService(tokens, loginApi, snapshot).loginSavedToken(true))
        val restored = EncryptedAccountTokenStore(activityRule.activity)
        assertEquals("refreshed-device-token", restored.token)
        val view = open("game")
        assertEarlyBridge(view)
        assertEquals("\"refreshed-device-token\"", evaluate(view, "window.initialInfo.data.accountToken"))
        ApiClient().post(url("device-header-check"), "{}", snapshot.copy(accountToken = restored.token.orEmpty()), true)
        assertEquals("refreshed-device-token", receivedAccountHeader)
    }

    @Test fun gamePageHasBridgeAfterReloadAndChangeGameUrl() {
        tokens.token = "device-test-token"
        val view = open("game")
        assertEarlyBridge(view)
        evaluate(view, "QSMH5APP.getAccountToken(function(r){window.tokenResult=r;});")
        await(view, "window.tokenResult && window.tokenResult.data.accountToken === 'device-test-token'")
        val previousDocument = evaluate(view, "performance.timeOrigin")
        onMain { view.reload() }
        await(view, "performance.timeOrigin !== $previousDocument")
        assertEarlyBridge(view)
        onMain { assertTrue(container.changeGameUrl(url("game-next"))) }
        await(view, "location.pathname === '/game-next' && window.firstScriptBridge === 'object'")
        await(view, "window.initialInfo && window.initialInfo.code === 0")
    }

    @Test fun gamePageCanOpenConfiguredWindowThroughNativeBridge() {
        val view = open("game")
        await(view, "typeof window.QSMH5APP === 'object'")
        evaluate(view, "QSMH5APP.openMiniWindow({page:'web_login'},function(r){window.openResult=r;});")
        await(view, "window.openResult != null")
        assertEquals("0", evaluate(view, "window.openResult.code"))
        onMain { assertNotNull(container.get("web_login")) }
    }

    @Test fun unconfiguredPageDoesNotReceiveBridge() {
        lateinit var view: WebView
        onMain {
            val spec = PageSpec("external", url("outside"), LayoutValue.Percent(100f), LayoutValue.Percent(100f), LayoutValue.Pixels(0), LayoutValue.Pixels(0))
            view = container.open(spec)!!.webView
            views.add(view)
        }
        await(view, "document.readyState === 'complete' && typeof window.firstScriptBridge === 'string'")
        assertEquals("\"undefined\"", evaluate(view, "typeof window.QSMH5APP"))
        assertEquals("\"undefined\"", evaluate(view, "typeof window.QSMNative"))
    }

    /** 显式传入 bridgeLivePages=true 时才访问线上页面；只读取信息，不执行登录或同意操作。 */
    @Test fun configuredLivePagesCanCallBridge() {
        assumeTrue(livePagesEnabled())
        for (key in listOf("agreement", "login", "game")) {
            val view = open(key)
            await(view, "document.readyState === 'complete' && document.getElementById('app') != null", 30)
            await(view, "typeof QSMH5APP === 'object' && typeof QSMNative.postMessage === 'function'")
            evaluate(view, "QSMH5APP.getGameInfo(function(r){window.liveInfo=r;});")
            await(view, "window.liveInfo && window.liveInfo.code === 0 && window.liveInfo.data.gameId === 92")
            if (key != "game") await(view, "window.Bridge && Bridge.bridgeOver === true")
            Log.i("BridgeLiveTest", "$key: QSMH5APP getGameInfo callback OK; QsmSDK=" + evaluate(view, "typeof window.QsmSDK"))
        }
    }

    private fun livePagesEnabled() = InstrumentationRegistry.getArguments().getString("bridgeLivePages") == "true"

    class PhoneReplyRecorder {
        @Volatile var result: String? = null
        @Volatile var count = 0
        @JavascriptInterface fun record(value: String) { count++; result = value }
    }

    private fun url(path: String) = "http://127.0.0.1:${server.localPort}/$path"

    private fun open(key: String): WebView {
        lateinit var view: WebView
        onMain { view = container.openConfigured(key)!!.webView }
        return view
    }

    private fun assertEarlyBridge(view: WebView) {
        await(view, "typeof window.firstScriptBridge === 'string'")
        assertEquals("页面首段脚本执行时 Bridge 必须已就绪", "\"object\"", evaluate(view, "window.firstScriptBridge"))
        await(view, "window.initialInfo && window.initialInfo.code === 0 && window.initialInfo.data.gameId === 92")
    }

    private fun await(view: WebView, expression: String, timeoutSeconds: Long = 10) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (System.nanoTime() < deadline) {
            if (evaluate(view, "Boolean($expression)") == "true") return
            Thread.sleep(50)
        }
        fail("等待页面条件超时：$expression")
    }

    private fun evaluate(view: WebView, script: String): String {
        val done = CountDownLatch(1)
        var result = "null"
        onMain { view.evaluateJavascript(script) { result = it; done.countDown() } }
        assertTrue("JavaScript 未返回", done.await(5, TimeUnit.SECONDS))
        return result
    }

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
}
