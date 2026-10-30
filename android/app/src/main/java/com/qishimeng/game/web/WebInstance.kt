package com.qishimeng.game.web

import android.webkit.WebView
import com.qishimeng.game.config.PageSpec

data class WebInstance(
    val spec: PageSpec,
    val webView: WebView,
    val bridgeTrusted: Boolean,
    val pageView: WebPageView
)

class WebInstanceRegistry : PopupWindowHost {
    private val instances = linkedMapOf<String, PageSpec>()
    private val zOrder = mutableListOf<String>()
    private val operationalInstances = mutableSetOf<String>()

    fun open(spec: PageSpec, operational: Boolean = false): PageSpec {
        val existing = instances[spec.instance]
        if (existing == null) {
            instances[spec.instance] = spec
            if (operational) operationalInstances += spec.instance
        }
        bringToFront(spec.instance)
        return existing ?: spec
    }

    override fun openWindow(spec: PageSpec, operational: Boolean) {
        open(spec, operational)
    }

    fun get(instance: String): PageSpec? = instances[instance]

    fun contains(instance: String): Boolean = instance in instances

    override fun hasWindow(instance: String): Boolean = contains(instance)

    override fun windowNames(): List<String> = order()

    override fun isOperationalWindow(instance: String): Boolean = instance in operationalInstances

    fun bringToFront(instance: String): Boolean {
        if (instance !in instances) return false
        zOrder.remove(instance)
        zOrder.add(instance)
        return true
    }

    fun close(instance: String): PageSpec? {
        zOrder.remove(instance)
        operationalInstances.remove(instance)
        return instances.remove(instance)
    }

    override fun closeWindow(instance: String) {
        close(instance)
    }

    fun order(): List<String> = zOrder.toList()
}
