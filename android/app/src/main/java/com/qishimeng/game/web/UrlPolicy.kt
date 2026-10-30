package com.qishimeng.game.web

import java.net.URI
import java.util.Locale

object UrlPolicy {
    fun isAllowedExternalBrowserUrl(url: String, jumpDomains: Set<String>): Boolean {
        val uri = parseWebUrl(url) ?: return false
        return uri.host.lowercase(Locale.ROOT) in jumpDomains.map { it.lowercase(Locale.ROOT) }.toSet()
    }

    fun isAllowedGameUrl(url: String, initialGameUrl: String): Boolean {
        val target = parseWebUrl(url) ?: return false
        val initial = parseWebUrl(initialGameUrl) ?: return false
        return target.host.equals(initial.host, ignoreCase = true)
    }

    fun isAllowedPageUrl(url: String): Boolean = parseWebUrl(url) != null

    private fun parseWebUrl(url: String): URI? = runCatching { URI(url) }
        .getOrNull()
        ?.takeIf {
            it.scheme.equals("http", ignoreCase = true) || it.scheme.equals("https", ignoreCase = true)
        }
        ?.takeIf { it.host?.isNotBlank() == true && it.rawUserInfo == null }
}
