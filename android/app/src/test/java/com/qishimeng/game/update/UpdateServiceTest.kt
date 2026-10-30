package com.qishimeng.game.update

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class UpdateServiceTest {
    private class Download : UpdateDownloadTask {
        var starts = 0
        var cancelled = false
        lateinit var progress: (Long, Long) -> Unit
        lateinit var complete: (Result<File>) -> Unit
        override fun start(url: String, onProgress: (Long, Long) -> Unit, onComplete: (Result<File>) -> Unit) {
            starts++; progress = onProgress; complete = onComplete
        }
        override fun cancel() { cancelled = true }
    }
    private val download = Download()
    private var continued = 0
    private var dismissed = 0
    private var installs = 0
    private var opened = ""
    private var error = ""
    private fun service(force: Boolean = false, popup: Boolean = false, install: (File) -> Unit = { installs++ }) = UpdateService(
        UpdateInfo("标题", "说明", "1.1", "https://example.com/app.apk", force, popup = popup),
        download, install, { opened = it }, {}, { dismissed++ }, { error = it }, { continued++ }
    )

    @Test fun internalDownloadReportsProgressAndAutomaticallyInstallsWithRetryButtonState() {
        val service = service(); service.start(); service.update(); service.update()
        assertEquals(1, download.starts)
        download.progress(48, 100)
        assertEquals(UpdateState.Downloading(48), service.state)
        val apk = File("ready.apk")
        download.complete(Result.success(apk))
        assertEquals(UpdateState.Ready(apk), service.state)
        assertEquals(1, installs)
        service.installReady(); assertEquals(2, installs)
        assertEquals(0, continued)
    }

    @Test fun unknownLengthHasNoFalsePercentageAndLateCallbacksAfterCloseAreIgnored() {
        val service = service(); service.start(); service.update()
        download.progress(48, -1)
        assertEquals(UpdateState.Downloading(null), service.state)
        service.close(); service.close()
        download.complete(Result.success(File("late.apk")))
        assertEquals(0, installs); assertEquals(1, continued); assertEquals(1, dismissed)
        assertTrue(download.cancelled)
    }

    @Test fun forcedCloseIsBlockedButDownloadFailureContinuesOnlyOnce() {
        val service = service(force = true); service.start(); service.close()
        assertEquals(0, continued)
        service.update(); download.complete(Result.failure(IllegalStateException("fixture")))
        download.complete(Result.failure(IllegalStateException("late")))
        assertEquals(1, continued); assertFalse(error.isBlank())
    }

    @Test fun externalBrowserModeUsesUrlWithoutDownloadAndForcedOfferRemains() {
        val optional = service(popup = true); optional.start(); optional.update()
        assertEquals("https://example.com/app.apk", opened)
        assertEquals(0, download.starts); assertEquals(1, continued)
        val forced = service(force = true, popup = true); forced.start(); forced.update()
        assertEquals(UpdateState.Offered, forced.state); assertEquals(1, continued)
    }

    @Test fun installExceptionContinuesAndDestroyNeverStartsLoginOrInstalls() {
        val service = service(force = true, install = { throw IllegalStateException() })
        service.start(); service.update(); download.complete(Result.success(File("ready.apk")))
        assertEquals(1, continued)
        val destroyed = service(); destroyed.start(); destroyed.update(); destroyed.destroy()
        download.complete(Result.success(File("late.apk")))
        assertEquals(1, continued); assertEquals(0, installs)
    }
}
