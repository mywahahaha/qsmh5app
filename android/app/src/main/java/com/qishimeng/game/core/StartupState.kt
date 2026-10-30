package com.qishimeng.game.core

enum class StartupState {
    Initializing,
    Privacy,
    Update,
    TokenLogin,
    AliyunLogin,
    H5Login,
    Game,
    Failed;

    companion object {
        val loginOrder = listOf(Privacy, Update, TokenLogin, AliyunLogin, H5Login, Game)

        fun afterInitialization(succeeded: Boolean): StartupState =
            if (succeeded) Privacy else Failed
    }
}
