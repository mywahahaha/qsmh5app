package com.qishimeng.game.core

import com.qishimeng.game.config.InitConfig

class StartupCoordinator {
    fun initialState(config: InitConfig?): StartupState =
        if (config == null) StartupState.Failed else StartupState.afterInitialization(true)
}
