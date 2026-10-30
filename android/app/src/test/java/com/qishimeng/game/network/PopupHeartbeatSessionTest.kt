package com.qishimeng.game.network

import org.junit.Assert.*
import org.junit.Test

class PopupHeartbeatSessionTest {
    @Test
    fun startsOnlyAfterReadyAndVisibleAndKeepsPollingWhenBallTemporarilyHidden() {
        val scheduler = ManualHeartbeatScheduler()
        val session = session(scheduler)
        session.update(false, true)
        session.update(true, false)
        assertTrue(scheduler.delays.isEmpty())
        session.update(true, true)
        session.update(true, true)
        session.update(true, false)
        assertEquals(listOf(30_000L), scheduler.delays)
        scheduler.runNext()
        assertEquals(listOf(30_000L, 30_000L), scheduler.delays)
    }

    @Test
    fun closingHallOrLogoutStopsAndNextVisibleSessionStartsWithFullInterval() {
        val scheduler = ManualHeartbeatScheduler()
        val session = session(scheduler)
        session.update(true, true)
        session.update(false, false)
        assertTrue(scheduler.cancelled)
        session.update(true, true)
        assertEquals(listOf(30_000L, 30_000L), scheduler.delays)
    }

    @Test
    fun queuedResponsesAfterStopAreDiscardedEvenAfterRestart() {
        val scheduler = ManualHeartbeatScheduler()
        val queued = mutableListOf<() -> Unit>()
        var applied = 0
        val session = PopupHeartbeatSession("https://box.74meng.com/popup", 30,
            { HeartbeatResponse(emptyList(), null) }, { queued += it }, { applied++ }, scheduler)
        session.update(true, true)
        scheduler.runNext()
        session.stop()
        session.update(true, true)
        queued.forEach { it() }
        assertEquals(0, applied)
    }

    @Test
    fun nonZeroCodeIsIgnoredAndPollingContinues() {
        val scheduler = ManualHeartbeatScheduler()
        var applied = 0
        val session = PopupHeartbeatSession("https://box.74meng.com/popup", 30,
            { HeartbeatResponse(emptyList(), FloatingBallHeartbeat(false, true), 100) }, { it() }, { applied++ }, scheduler)
        session.update(true, true)
        scheduler.runNext()
        assertEquals(0, applied)
        assertEquals(2, scheduler.delays.size)
    }

    @Test
    fun invalidConfigNeverStartsPolling() {
        listOf(null to 30, "" to 30, "javascript:alert(1)" to 30,
            "https://box.74meng.com/popup" to 0, "https://box.74meng.com/popup" to -1).forEach { (url, interval) ->
            val scheduler = ManualHeartbeatScheduler()
            val session = PopupHeartbeatSession(url, interval, { error("must not poll") }, { it() }, {}, scheduler)
            session.update(true, true)
            assertTrue(scheduler.delays.isEmpty())
        }
    }

    private fun session(scheduler: HeartbeatScheduler) = PopupHeartbeatSession(
        "https://box.74meng.com/popup", 30, { HeartbeatResponse(null, null) }, { it() }, {}, scheduler)
}
