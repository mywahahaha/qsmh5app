package com.qishimeng.game.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class AuthSessionGateTest {
    @Test fun overlappingLaunchCannotTouchSdkAndOldReleaseCannotEndNewSession() {
        val gate = AuthSessionGate()
        val started = mutableListOf<String>()
        var rejected = 0
        lateinit var firstRelease: () -> Unit
        lateinit var nextRelease: () -> Unit
        gate.start({ rejected++ }) { release -> started.add("first"); firstRelease = release }
        gate.start({ rejected++ }) { started.add("overlap") }
        assertEquals(listOf("first"), started)
        assertEquals(1, rejected)
        firstRelease()
        gate.start({ rejected++ }) { release -> started.add("next"); nextRelease = release }
        firstRelease()
        gate.start({ rejected++ }) { started.add("stale-release-overlap") }
        assertEquals(listOf("first", "next"), started)
        assertEquals(2, rejected)
        nextRelease()
        gate.start({ rejected++ }) { release -> started.add("last"); release() }
        assertEquals(listOf("first", "next", "last"), started)
    }
}
