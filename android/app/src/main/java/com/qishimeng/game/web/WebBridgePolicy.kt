package com.qishimeng.game.web

import com.qishimeng.game.config.PageSpec

object WebBridgePolicy {
    fun acceptsMessage(sourceUrl: String, trustedUrl: String, isMainFrame: Boolean): Boolean =
        isMainFrame && UrlPolicy.isAllowedGameUrl(sourceUrl, trustedUrl)

    fun trustedUrl(spec: PageSpec, configured: Collection<PageSpec>, operational: Boolean): String? {
        if (!UrlPolicy.isAllowedPageUrl(spec.url)) return null
        val functional = configured.firstOrNull { it.instance == spec.instance }
        if (functional != null) {
            return functional.url.takeIf { UrlPolicy.isAllowedGameUrl(spec.url, it) }
        }
        // 仅心跳运营入口可授予动态页面 Bridge；信任绑定到服务端下发的初始 URL 主机。
        return spec.url.takeIf { operational }
    }
}
