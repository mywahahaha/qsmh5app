package com.qishimeng.game

import android.content.Intent
import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class MainActivityExternalBrowserTest {
    @Test
    fun externalBrowserLaunchNeverChangesLandscapeGameOrientation() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        MainActivity::class.java.getDeclaredMethod("openExternalUrl", String::class.java)
            .apply { isAccessible = true }
            .invoke(activity, "https://www.baidu.com/path")

        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            activity.requestedOrientation
        )
        val browser = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, browser.action)
        assertEquals("https://www.baidu.com/path", browser.dataString)
        assertTrue(browser.categories?.contains(Intent.CATEGORY_BROWSABLE) == true)
        assertNull(browser.component)
    }
}
