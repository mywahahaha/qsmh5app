package com.qishimeng.game.auth

data class GameInfo(
    val packageCode: String,
    val gameId: Int,
    val gameVersion: String,
    val accountToken: String,
    val deviceType: String,
    val deviceCode: String
) {
    fun forBridge(privacyAgreed: Boolean): GameInfo =
        if (privacyAgreed) this else copy(deviceCode = "")
}
