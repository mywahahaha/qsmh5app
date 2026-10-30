package com.qishimeng.game.bridge

import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.ToNumberPolicy
import com.google.gson.reflect.TypeToken

class BridgeJavascriptInterface(
    private val webView: WebView,
    private val actions: BridgeActions,
    private val isActive: () -> Boolean = { true }
) {
    @JavascriptInterface
    fun postMessage(payload: String) {
        // JavascriptInterface 在 WebView 的后台线程回调；窗口与认证 UI 操作必须切回主线程。
        webView.post { dispatchOnMainThread(payload) }
    }

    private fun dispatchOnMainThread(payload: String) {
        // 关闭实例后立即拒绝旧文档请求，包括淡出期间及已排队的消息。
        if (!isActive()) return
        runCatching {
            val request = JsonParser.parseString(payload).asJsonObject
            val id = request.get("id").asString
            val method = request.get("method").asString
            val arguments = when (method) {
                "saveLocalStorage" -> LOCAL_STORAGE_GSON.fromJson(request.get("args"), Any::class.java)
                // 保留无效输入的类型，由业务层按原请求 ID 回调 1001，避免 Map 解码失败丢失回调。
                "isAppInstalled", "openApp" -> Gson().fromJson(request.get("args"), Any::class.java)
                else -> Gson().fromJson<Map<String, Any?>>(request.get("args"), ARGUMENTS_TYPE).orEmpty()
            }
            actions.dispatch(method, arguments) { result, afterDelivery ->
                val serialized = Gson().toJson(result)
                webView.post {
                    webView.evaluateJavascript("window.__QSMH5APP_CALLBACK__(${Gson().toJson(id)},$serialized)") { afterDelivery() }
                }
            }
        }.onFailure {
            webView.post {
                webView.evaluateJavascript("window.__QSMH5APP_CALLBACK__(null,{\"code\":1001,\"msg\":\"参数错误\",\"data\":null})", null)
            }
        }
    }

    companion object {
        // 本地存储保留 H5 序列化的十进制数，避免 Double 再次转换改变大数末位。
        private val LOCAL_STORAGE_GSON = GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.BIG_DECIMAL).create()
        private val ARGUMENTS_TYPE = object : TypeToken<Map<String, Any?>>() {}.type
        const val INTERFACE_NAME = "QSMNative"
    }
}
