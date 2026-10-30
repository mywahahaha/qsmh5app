package com.qishimeng.game.bridge

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/** 只按包名查询和启动，不接受 H5 传入的组件、URI 或 Intent 参数。 */
class ExternalApps(private val context: Context) {
    fun isInstalled(value: Any?): Boolean {
        val packageName = requirePackageName(value)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun open(value: Any?): Boolean {
        val packageName = requirePackageName(value)
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
            intent.setPackage(packageName)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            // 查询入口后应用可能刚好被卸载，不能回报启动成功。
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun requirePackageName(value: Any?): String {
        require(value is String && PACKAGE_NAME.matches(value)) { "packageName 必须是有效的应用包名" }
        return value
    }

    companion object {
        private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*")
    }
}
