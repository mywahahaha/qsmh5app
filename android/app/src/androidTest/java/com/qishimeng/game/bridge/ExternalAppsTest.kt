package com.qishimeng.game.bridge

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class ExternalAppsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun detectsInstalledPackageAndReturnsFalseForMissingPackage() {
        val apps = ExternalApps(context)
        assertTrue(apps.isInstalled(context.packageName))
        assertFalse(apps.isInstalled("com.qishimeng.definitely_missing"))
    }

    @Test fun launchIntentTargetsRequestedPackageAndSupportsNonActivityContext() {
        val recorder = LaunchContext(context)
        assertTrue(ExternalApps(recorder).open(context.packageName))
        val intent = recorder.launched!!
        assertEquals(context.packageName, intent.component!!.packageName)
        assertEquals(context.packageName, intent.`package`)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun missingOrNonLaunchableAppsDoNotStartAnActivity() {
        val recorder = LaunchContext(context)
        val apps = ExternalApps(recorder)
        assertFalse(apps.open("com.qishimeng.definitely_missing"))
        // Android 框架包存在但不提供应用启动入口。
        assertTrue(apps.isInstalled("android"))
        assertFalse(apps.open("android"))
        assertNull(recorder.launched)
    }

    @Test fun systemDenialAndUninstallRaceReturnFalse() {
        for (failure in listOf(SecurityException("denied"), ActivityNotFoundException("uninstalled"))) {
            val recorder = LaunchContext(context, failure)
            assertFalse(ExternalApps(recorder).open(context.packageName))
        }
    }

    @Test fun invalidPackageNamesAreRejectedBeforeAnyLaunch() {
        val recorder = LaunchContext(context)
        val apps = ExternalApps(recorder)
        for (value in listOf(null, 123, "", " ", "com.bad name", " com.me74.qsmhz", "com.me74.qsmhz ", "com..bad", "com._bad", "_com.bad", "intent://bad", "com.bad/Activity")) {
            assertThrows(IllegalArgumentException::class.java) { apps.isInstalled(value) }
            assertThrows(IllegalArgumentException::class.java) { apps.open(value) }
        }
        assertNull(recorder.launched)
    }
}

private class LaunchContext(base: Context, private val failure: RuntimeException? = null) : ContextWrapper(base) {
    var launched: Intent? = null
    override fun startActivity(intent: Intent) {
        failure?.let { throw it }
        launched = intent
    }
}
