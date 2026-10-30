package com.qishimeng.game.update

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    private fun fields() = mapOf<String, Any?>(
        "open" to 1, "width" to "50", "height" to "auto", "popup" to 0, "force" to 1,
        "title" to "邀您体验新版本", "time" to "2026-10-28", "desc" to "发现新版本 V1.0.12",
        "version" to "1.0.12", "url" to "https://box.74meng.com/app.apk"
    )

    @Test fun disabledOrAbsentConfigIgnoresOtherFields() {
        assertEquals(UpdateDecision.Continue, UpdatePolicy.evaluate(emptyMap()))
        assertEquals(UpdateDecision.Continue, UpdatePolicy.evaluate(mapOf("open" to 0, "force" to "bad")))
    }

    @Test fun newContractEnablesUpdateWithAutoHeightAndBothModes() {
        val auto = (UpdatePolicy.evaluate(fields()) as UpdateDecision.Offer).info
        assertEquals(50f, auto.widthPercent, 0.001f)
        assertNull(auto.heightPercent)
        assertTrue(auto.force); assertFalse(auto.popup)
        assertEquals("2026-10-28", auto.time)
        val fixed = (UpdatePolicy.evaluate(fields() + mapOf("width" to 75, "height" to "65%", "popup" to 1)) as UpdateDecision.Offer).info
        assertEquals(75f, fixed.widthPercent, 0.001f)
        assertEquals(65f, fixed.heightPercent!!, 0.001f)
        assertTrue(fixed.popup)
        assertTrue(UpdatePolicy.evaluate(fields() + mapOf("title" to "", "desc" to "", "version" to "", "time" to "")) is UpdateDecision.Offer)
    }

    @Test fun externalUpdateCanUseAnHttpLandingPageWithFragment() {
        assertTrue(UpdatePolicy.evaluate(fields() + mapOf("popup" to 1, "url" to "https://example.com/#download")) is UpdateDecision.Offer)
    }

    @Test fun invalidFlagsPercentagesTypesAndUrlsAbortOnlyTheUpdate() {
        for (change in listOf(
            mapOf("open" to 1.5), mapOf("force" to "1"), mapOf("popup" to 2),
            mapOf("width" to "0"), mapOf("height" to 101), mapOf("height" to "NaN"),
            mapOf("title" to 123), mapOf("url" to "file:///app.apk"), mapOf("url" to "https://"),
            mapOf("url" to "https://user:password@example.com/app.apk")
        )) assertTrue("应拒绝 $change", UpdatePolicy.evaluate(fields() + change) is UpdateDecision.Abort)
    }
}
