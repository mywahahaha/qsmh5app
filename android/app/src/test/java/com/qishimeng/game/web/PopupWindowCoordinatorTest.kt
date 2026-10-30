package com.qishimeng.game.web

import com.qishimeng.game.config.LayoutValue
import com.qishimeng.game.config.PageSpec
import com.qishimeng.game.network.HeartbeatResponse
import org.junit.Assert.assertEquals
import org.junit.Test

class PopupWindowCoordinatorTest {
    @Test
    fun retainsExistingAndReservedWindowsAndRemovesAbsentDynamicInstances() {
        val registry = WebInstanceRegistry()
        val coordinator = PopupWindowCoordinator(registry)
        val dynamic = page("promo")
        registry.open(page("old"))
        registry.open(page("web_game"))
        coordinator.apply(HeartbeatResponse(listOf(dynamic), null))

        assertEquals(listOf("old", "web_game", "promo"), registry.order())
        assertEquals(true, registry.isOperationalWindow("promo"))
    }

    @Test
    fun missingListDoesNotSyncAndEmptyListClosesAllDynamicWindows() {
        val registry = WebInstanceRegistry()
        val coordinator = PopupWindowCoordinator(registry)
        registry.openWindow(page("promo"), operational = true)
        coordinator.apply(HeartbeatResponse(null, null))
        assertEquals(listOf("promo"), registry.order())
        coordinator.apply(HeartbeatResponse(emptyList(), null))
        assertEquals(emptyList<String>(), registry.order())
    }

    @Test
    fun existingOperationalInstanceIsNotReopenedOrChanged() {
        val registry = WebInstanceRegistry()
        val original = page("promo")
        registry.openWindow(original, operational = true)
        registry.open(page("other"))

        coordinator(registry).apply(HeartbeatResponse(listOf(original.copy(url = "https://box.74meng.com/new")), null))

        assertEquals(listOf("promo", "other"), registry.order())
        assertEquals(original.url, registry.get("promo")?.url)
    }

    private fun coordinator(registry: WebInstanceRegistry) = PopupWindowCoordinator(registry)

    @Test
    fun configuredFunctionalInstanceCannotBecomeOperationalEvenWhenNotOpen() {
        val registry = WebInstanceRegistry()
        val coordinator = PopupWindowCoordinator(registry, setOf("custom_function"))
        coordinator.apply(HeartbeatResponse(listOf(page("custom_function"), page("promo")), null))
        assertEquals(listOf("promo"), registry.order())
        registry.open(page("custom_function"))
        coordinator.apply(HeartbeatResponse(emptyList(), null))
        assertEquals(listOf("custom_function"), registry.order())
    }

    private fun page(instance: String) = PageSpec(instance, "https://box.74meng.com/$instance", LayoutValue.Pixels(300), LayoutValue.Pixels(400), LayoutValue.Pixels(0), LayoutValue.Pixels(0))
}
