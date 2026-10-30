package com.qishimeng.game.network

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.Settings

/** 启动联网检查与重试。所有入口由 Activity 主线程调用。 */
class StartupNetworkGate(
    private val activity: Activity,
    private val onReady: () -> Unit,
    private val hasNetwork: () -> Boolean = { isNetworkConnected(activity) },
    private val openSettings: (Intent) -> Unit = { activity.startActivity(it) },
    private val onUnavailable: () -> Unit = {}
) {
    private var dialog: AlertDialog? = null
    private var inFlight = false
    private var returningFromSettings = false
    private var closed = false

    fun runWhenConnected() {
        if (closed || inFlight || activity.isFinishing || activity.isDestroyed) return
        dialog?.dismiss()
        dialog = null
        if (!hasNetwork()) {
            showPrompt(connected = false)
            return
        }
        inFlight = true
        onReady()
    }

    fun onRequestFinished() {
        inFlight = false
    }

    fun onConnectionFailure() {
        inFlight = false
        if (closed || activity.isFinishing || activity.isDestroyed) return
        showPrompt(connected = hasNetwork())
    }

    fun onResume() {
        if (!returningFromSettings) return
        returningFromSettings = false
        runWhenConnected()
    }

    fun close() {
        closed = true
        returningFromSettings = false
        dialog?.dismiss()
        dialog = null
    }

    private fun showPrompt(connected: Boolean) {
        onUnavailable()
        dialog?.dismiss()
        dialog = AlertDialog.Builder(activity)
            .setTitle(if (connected) "无法连接服务器" else "未连接网络")
            .setMessage(if (connected)
                "请检查网络是否可用，并在系统设置中允许本游戏使用 WLAN 或移动数据。也可能是服务器暂时不可用，恢复后可重试。"
            else "游戏启动需要连接网络，请开启 WLAN 或移动数据后重试。")
            .setCancelable(false)
            .setPositiveButton(if (connected) "应用联网设置" else "网络设置") { _, _ ->
                val intent = if (connected) Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}"))
                    else Intent(Settings.ACTION_WIRELESS_SETTINGS)
                returningFromSettings = true
                runCatching { openSettings(intent) }.onFailure {
                    returningFromSettings = false
                    runWhenConnected()
                }
            }
            .setNeutralButton("重试") { _, _ -> runWhenConnected() }
            .setNegativeButton("退出") { _, _ -> activity.finish() }
            .show()
    }

    companion object {
        private fun isNetworkConnected(context: Context): Boolean {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = manager.activeNetwork ?: return false
            val capabilities = manager.getNetworkCapabilities(network) ?: return false
            // 不要求 VALIDATED：部分厂商网络验证不可靠，能连网时继续以实际请求结果为准。
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }
}
