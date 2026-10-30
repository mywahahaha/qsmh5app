package com.qishimeng.game.web

import com.qishimeng.game.config.PageSpec
import com.qishimeng.game.network.HeartbeatResponse

interface PopupWindowHost {
    fun openWindow(spec: PageSpec, operational: Boolean = false)
    fun closeWindow(instance: String)
    fun hasWindow(instance: String): Boolean
    fun windowNames(): List<String>
    fun isOperationalWindow(instance: String): Boolean
}

class PopupWindowCoordinator(
    private val windows: PopupWindowHost,
    private val functionalInstances: Set<String> = emptySet()
) {
    fun apply(response: HeartbeatResponse) {
        if (response.code != 0) return
        val popupPages = response.popupPages ?: return
        val retainedNames = popupPages.map { it.instance }.filter(String::isNotBlank).toSet()
        popupPages.forEach { spec ->
            if (spec.instance.isBlank() || spec.instance in functionalInstances) return@forEach
            if (windows.hasWindow(spec.instance)) return@forEach
            if (UrlPolicy.isAllowedPageUrl(spec.url)) windows.openWindow(spec, operational = true)
        }
        windows.windowNames().forEach { instance ->
            if (windows.isOperationalWindow(instance) && instance !in retainedNames) windows.closeWindow(instance)
        }
    }
}
