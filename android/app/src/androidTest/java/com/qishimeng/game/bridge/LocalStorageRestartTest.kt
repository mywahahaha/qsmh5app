package com.qishimeng.game.bridge

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 两次独立 instrumentation 之间结束应用进程，验证重启后的真实持久化。 */
@RunWith(AndroidJUnit4::class)
class LocalStorageRestartTest {
    @Test fun savedValuesSurviveProcessRestart() {
        val phase = InstrumentationRegistry.getArguments().getString("localStorageRestartPhase")
        assumeTrue(phase == "prepare" || phase == "verify")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val markers = context.getSharedPreferences("local_storage_restart_test", 0)
        val store = BridgeLocalStorage(SharedPreferencesLocalStorage(context))
        if (phase == "prepare") {
            assertTrue(store.save(listOf(
                mapOf("key" to "restart_abc", "value" to 123),
                mapOf("key" to "restart_text", "value" to "重启保留"),
                mapOf("key" to "restart_nil", "value" to null)
            )))
            assertTrue(markers.edit().putInt("prepared_pid", Process.myPid()).commit())
        } else {
            val preparedPid = markers.getInt("prepared_pid", -1)
            assertTrue("须先执行 prepare 阶段", preparedPid > 0)
            assertNotEquals("验证必须运行在新进程", preparedPid, Process.myPid())
            assertEquals("123", store.get("restart_abc"))
            assertEquals("重启保留", store.get("restart_text"))
            assertEquals("", store.get("restart_nil"))
            assertEquals("", store.get("restart_missing"))
            assertTrue(context.getSharedPreferences("h5_local_storage", 0).edit()
                .remove("restart_abc").remove("restart_text").remove("restart_nil").commit())
            assertTrue(markers.edit().clear().commit())
        }
    }
}
