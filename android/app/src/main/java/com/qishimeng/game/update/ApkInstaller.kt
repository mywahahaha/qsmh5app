package com.qishimeng.game.update

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/** 下载文件只经 content URI 和临时读权限交给系统安装器；不进行静默安装。 */
class ApkInstaller(
    private val activity: Activity,
    private val launchIntent: (Intent) -> Unit = { activity.startActivity(it) },
    private val canInstall: () -> Boolean = {
        Build.VERSION.SDK_INT < 26 || activity.packageManager.canRequestPackageInstalls()
    },
    private val showMessage: (String) -> Unit = { Toast.makeText(activity, it, Toast.LENGTH_LONG).show() },
    private val onError: (String) -> Unit = {}
) {
    private var pending: File? = null
    private var awaitingPermission = false
    private var closed = false

    fun install(apk: File) {
        if (closed) return
        validate(apk)
        pending = apk
        if (!canInstall() && Build.VERSION.SDK_INT >= 26) {
            if (awaitingPermission) return
            awaitingPermission = true
            try {
                launchIntent(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            } catch (error: Exception) {
                awaitingPermission = false
                throw error
            }
        } else launchInstaller(apk)
    }

    fun onResume() {
        if (closed || !awaitingPermission) return
        awaitingPermission = false
        try {
            if (canInstall()) pending?.let(::launchInstaller)
            else showMessage("请允许本游戏安装应用后，点击立即安装")
        } catch (_: Exception) {
            onError("无法启动安装，请稍后重试")
        }
    }

    fun close() { closed = true; pending = null; awaitingPermission = false }

    private fun validate(apk: File) {
        if (!apk.isFile || apk.length() == 0L) throw IOException("安装包不存在")
        @Suppress("DEPRECATION")
        val archive = activity.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
        if (archive?.packageName != activity.packageName) throw IOException("更新安装包无效或包名不匹配")
        val directory = File(activity.filesDir, "updates").canonicalFile
        if (apk.canonicalFile.parentFile != directory) throw IOException("更新安装包目录无效")
    }

    private fun launchInstaller(apk: File) {
        validate(apk)
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("更新安装包", uri)
        }
        launchIntent(intent)
    }
}
