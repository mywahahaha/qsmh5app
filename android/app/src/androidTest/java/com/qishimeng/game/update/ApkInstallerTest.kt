package com.qishimeng.game.update

import android.content.Intent
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import com.qishimeng.game.bridge.BridgeTestActivity
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApkInstallerTest {
    @get:Rule val activityRule = ActivityTestRule(BridgeTestActivity::class.java)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var apk: File
    private var installer: ApkInstaller? = null
    @Before fun setUp() {
        val activity = activityRule.activity
        apk = File(activity.filesDir, "updates/installer-fixture.apk")
        apk.parentFile!!.mkdirs()
        File(activity.applicationInfo.sourceDir).copyTo(apk, overwrite = true)
    }
    @After fun tearDown() { onMain { installer?.close() }; apk.delete() }

    @Test fun privateApkIsSharedViaReadableContentUriAndCanBeInstalledAgain() {
        val intents = mutableListOf<Intent>()
        onMain {
            installer = ApkInstaller(activityRule.activity, { intents.add(it) }, { true })
            installer!!.install(apk)
            installer!!.onResume()
            assertEquals(1, intents.size)
            val intent = intents.single()
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("content", intent.data!!.scheme)
            assertEquals("application/vnd.android.package-archive", intent.type)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            activityRule.activity.contentResolver.openInputStream(intent.data!!)!!.use {
                assertEquals('P'.code, it.read()); assertEquals('K'.code, it.read())
            }
            installer!!.install(apk)
            assertEquals(2, intents.size)
        }
    }

    @Test fun unknownSourcesPermissionReturnInstallsOnceAndDeniedPermissionKeepsManualRetry() {
        val intents = mutableListOf<Intent>()
        var allowed = false
        var message = ""
        onMain {
            installer = ApkInstaller(activityRule.activity, { intents.add(it) }, { allowed }, { message = it })
            installer!!.install(apk)
            assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intents.single().action)
            assertEquals("package:${activityRule.activity.packageName}", intents.single().data.toString())
            installer!!.onResume()
            assertEquals(1, intents.size); assertFalse(message.isBlank())
            installer!!.install(apk)
            allowed = true
            installer!!.onResume(); installer!!.onResume()
            assertEquals(3, intents.size)
            assertEquals(Intent.ACTION_VIEW, intents.last().action)
        }
    }

    @Test fun invalidApkIsRejectedBeforeAnyInstallIntent() {
        apk.writeText("not an apk")
        var launches = 0
        onMain {
            installer = ApkInstaller(activityRule.activity, { launches++ }, { true })
            assertTrue(runCatching { installer!!.install(apk) }.isFailure)
            assertEquals(0, launches)
        }
    }
    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
}
