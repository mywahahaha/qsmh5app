package com.qishimeng.game.web

import com.qishimeng.game.config.LayoutValue
import com.qishimeng.game.config.PageSpec
import org.junit.Assert.*
import org.junit.Test

class WebBridgePolicyTest {
    private val game = page("web_game", "https://box.74meng.com/game")

    @Test
    fun configuredSameHostPageKeepsBridgeAfterChangingPath() {
        assertEquals(game.url, WebBridgePolicy.trustedUrl(game.copy(url = "https://box.74meng.com/new"), listOf(game), false))
    }

    @Test
    fun configuredInstanceCannotGainBridgeAtAnotherHost() {
        assertNull(WebBridgePolicy.trustedUrl(game.copy(url = "https://evil.example/game"), listOf(game), false))
    }

    @Test
    fun ordinaryDynamicWindowDoesNotGainBridgeEvenOnConfiguredHost() {
        assertNull(WebBridgePolicy.trustedUrl(page("mini", "https://box.74meng.com/mini"), listOf(game), false))
    }

    @Test
    fun serverIssuedOperationalWindowGetsBridgeBoundToItsInitialHost() {
        val popup = page("promo", "https://campaign.example/promo")
        assertEquals(popup.url, WebBridgePolicy.trustedUrl(popup, listOf(game), true))
        assertTrue(UrlPolicy.isAllowedGameUrl("https://campaign.example/next", popup.url))
        assertFalse(UrlPolicy.isAllowedGameUrl("https://other.example/next", popup.url))
    }

    @Test
    fun invalidOperationalUrlDoesNotGainBridge() {
        assertNull(WebBridgePolicy.trustedUrl(page("promo", "javascript:alert(1)"), listOf(game), true))
    }

    @Test
    fun operationalNativeMessagesRejectSubframesAndForeignHosts() {
        val initial = "https://campaign.example/promo"
        assertTrue(WebBridgePolicy.acceptsMessage("https://campaign.example", initial, true))
        assertFalse(WebBridgePolicy.acceptsMessage("https://campaign.example", initial, false))
        assertFalse(WebBridgePolicy.acceptsMessage("https://evil.example", initial, true))
        assertFalse(WebBridgePolicy.acceptsMessage("file:///campaign.example", initial, true))
    }

    private fun page(instance: String, url: String) = PageSpec(instance, url,
        LayoutValue.Percent(100f), LayoutValue.Percent(100f), LayoutValue.Pixels(0), LayoutValue.Pixels(0))
}
