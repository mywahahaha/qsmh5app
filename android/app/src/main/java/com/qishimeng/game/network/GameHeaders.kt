package com.qishimeng.game.network

import com.qishimeng.game.auth.GameInfo

object GameHeaders {
    fun toMap(info: GameInfo, privacyAgreed: Boolean): Map<String, String> = linkedMapOf(
        "Gameid" to info.gameId.toString(),
        "Devicecode" to if (privacyAgreed) info.deviceCode else "",
        "Packagecode" to info.packageCode,
        "Devicetype" to info.deviceType,
        "Accountoken" to if (privacyAgreed) info.accountToken else "",
        "Gameversion" to info.gameVersion
    )
}
