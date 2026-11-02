package com.qishimeng.game.update

import android.app.Activity
import android.content.Intent
import android.content.Context
import android.content.pm.PackageInfo
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.android.controller.ActivityController

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [InstallerFileProviderShadow::class])
class ApkInstallerLifecycleTest {
    private lateinit var controller: ActivityController<Activity>
    private lateinit var apk: File
    private lateinit var installer: ApkInstaller
    private val intents = mutableListOf<Intent>()

    @Before fun setUp() {
        controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        apk = File(activity.filesDir, "updates/installer-fixture.apk")
        apk.parentFile!!.mkdirs()
        apk.writeBytes(byteArrayOf(80, 75, 3, 4))
        shadowOf(activity.packageManager).setPackageArchiveInfo(apk.absolutePath, PackageInfo().apply {
            packageName = activity.packageName
        })
    }

    @After fun tearDown() {
        if (::installer.isInitialized) installer.close()
        controller.pause().stop().destroy()
        if (::apk.isInitialized) apk.delete()
    }

    @Test fun successfulInstallerLaunchUsesSeparateTaskThenFinishesGameOnlyOnce() {
        val activity = controller.get()
        installer = ApkInstaller(activity, { intent ->
            assertFalse("成功发起安装前不得退出游戏", activity.isFinishing)
            intents.add(intent)
        }, { true })
        installer.install(apk)
        assertTrue("成功唤起安装后应退出游戏任务", activity.isFinishing)
        val intent = intents.single()
        assertTrue("安装器不得留在游戏任务内", intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals("content", intent.data!!.scheme)
        installer.install(apk)
        installer.onResume()
        assertEquals("退出后不得重复打开安装器", 1, intents.size)
    }

    @Test fun permissionScreenAndDenialDoNotExitButPermissionReturnSuccessfulLaunchDoes() {
        val activity = controller.get()
        var allowed = false
        installer = ApkInstaller(activity, { intents.add(it) }, { allowed }, {})
        installer.install(apk)
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intents.single().action)
        assertFalse(activity.isFinishing)
        installer.onResume()
        assertFalse("拒绝授权不退出", activity.isFinishing)
        installer.install(apk)
        allowed = true
        installer.onResume()
        assertEquals(Intent.ACTION_VIEW, intents.last().action)
        assertTrue("授权返回并成功唤起安装后退出", activity.isFinishing)
    }

    @Test fun failedInstallerLaunchDoesNotExitAndCanBeRetried() {
        val activity = controller.get()
        var failLaunch = true
        installer = ApkInstaller(activity, {
            if (failLaunch) throw android.content.ActivityNotFoundException("fixture")
            intents.add(it)
        }, { true })
        assertTrue(runCatching { installer.install(apk) }.isFailure)
        assertFalse("启动异常不得退出游戏", activity.isFinishing)
        failLaunch = false
        installer.install(apk)
        assertTrue(activity.isFinishing)
    }
}

/** FileProvider 的路径包含判断使用 Unix 分隔符；Windows JVM 仅替换 URI 生成，真实读取由设备用例验证。 */
@Implements(FileProvider::class)
class InstallerFileProviderShadow {
    companion object {
        @JvmStatic @Implementation
        fun getUriForFile(context: Context, authority: String, file: File): Uri =
            Uri.parse("content://$authority/updates/${file.name}")
    }
}
