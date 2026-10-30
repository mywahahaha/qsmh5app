package com.qishimeng.game.web

import com.qishimeng.game.config.LayoutValue
import com.qishimeng.game.config.PageSpec
import org.junit.Assert.assertEquals
import org.junit.Test

class WebInstanceRegistryTest {
    @Test
    fun keepsNamedInstancesAndMovesOpenedWindowToFrontWithoutReloading() {
        val registry = WebInstanceRegistry()
        val first = PageSpec("web_game", "https://box.74meng.com/h5", LayoutValue.Percent(100f), LayoutValue.Percent(100f), LayoutValue.Pixels(0), LayoutValue.Pixels(0))
        val second = first.copy(instance = "web_welfare", url = "https://box.74meng.com/h5_app/welfare")
        registry.open(first)
        registry.open(second)
        registry.open(first.copy(url = "https://box.74meng.com/changed"))

        assertEquals(listOf("web_welfare", "web_game"), registry.order())
        assertEquals("https://box.74meng.com/h5", registry.get("web_game")?.url)
    }
}
