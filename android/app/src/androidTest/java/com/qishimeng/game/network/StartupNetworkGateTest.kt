package com.qishimeng.game.network

import android.content.Intent
import android.provider.Settings
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import com.qishimeng.game.bridge.BridgeTestActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupNetworkGateTest {
    @get:Rule val activityRule = ActivityTestRule(BridgeTestActivity::class.java)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var gate: StartupNetworkGate
    private var connected = false
    private var requests = 0
    private var settingsIntent: Intent? = null

    private fun createGate() = onMain {
        gate = StartupNetworkGate(
            activityRule.activity,
            onReady = { requests++ },
            hasNetwork = { connected },
            openSettings = { settingsIntent = it }
        )
        gate.runWhenConnected()
    }

    @After fun tearDown() {
        if (::gate.isInitialized) onMain { gate.close() }
    }

    @Test fun offlineStartupPromptsAndRetryWaitsForConnection() {
        createGate()
        onView(withText("未连接网络")).inRoot(isDialog()).check(matches(isDisplayed()))
        assertEquals(0, requests)
        onView(withText("重试")).inRoot(isDialog()).perform(click())
        onView(withText("未连接网络")).inRoot(isDialog()).check(matches(isDisplayed()))
        assertEquals(0, requests)
        connected = true
        onView(withText("重试")).inRoot(isDialog()).perform(click())
        assertEquals(1, requests)
        onMain { gate.runWhenConnected() }
        assertEquals("初始化未结束时不得重复请求", 1, requests)
    }

    @Test fun opensNetworkSettingsAndRetriesWhenReturning() {
        createGate()
        onView(withText("网络设置")).inRoot(isDialog()).perform(click())
        assertEquals(Settings.ACTION_WIRELESS_SETTINGS, settingsIntent?.action)
        connected = true
        onMain { gate.onResume() }
        assertEquals(1, requests)
        onMain { gate.onResume() }
        assertEquals(1, requests)
    }

    @Test fun connectedButRequestFailedOffersAppNetworkSettings() {
        connected = true
        createGate()
        assertEquals(1, requests)
        onMain { gate.onConnectionFailure() }
        onView(withText("无法连接服务器")).inRoot(isDialog()).check(matches(isDisplayed()))
        onView(withText("应用联网设置")).inRoot(isDialog()).perform(click())
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, settingsIntent?.action)
        assertEquals("package:${activityRule.activity.packageName}", settingsIntent?.data.toString())
        onMain { gate.onResume() }
        assertEquals(2, requests)
    }

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
}
