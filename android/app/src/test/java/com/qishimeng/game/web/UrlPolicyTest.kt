package com.qishimeng.game.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlPolicyTest {
    @Test
    fun externalBrowserAllowsHttpAndHttpsOnlyForExactHosts() {
        val hosts = setOf("ipay.74meng.com", "box.74meng.com")
        assertTrue(UrlPolicy.isAllowedExternalBrowserUrl("http://ipay.74meng.com/path", hosts))
        assertTrue(UrlPolicy.isAllowedExternalBrowserUrl("https://box.74meng.com", hosts))
        assertFalse(UrlPolicy.isAllowedExternalBrowserUrl("https://sub.box.74meng.com", hosts))
        assertFalse(UrlPolicy.isAllowedExternalBrowserUrl("javascript:alert(1)", hosts))
    }

    @Test
    fun gameUrlMustStayOnTheInitialGameHost() {
        val initial = "https://box.74meng.com/h5?gid=266"
        assertTrue(UrlPolicy.isAllowedGameUrl("http://box.74meng.com/h5?gid=1", initial))
        assertFalse(UrlPolicy.isAllowedGameUrl("https://sub.box.74meng.com/h5", initial))
        assertFalse(UrlPolicy.isAllowedGameUrl("file:///android_asset/index.html", initial))
    }
}
