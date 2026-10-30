package com.qishimeng.game.auth

sealed interface LoginOutcome {
    data class Game(val accountToken: String) : LoginOutcome
    data object ShowH5Login : LoginOutcome
}

class LoginCoordinator(
    private val tokenLogin: () -> TokenLoginOutcome,
    private val onekeyLogin: (Boolean) -> OneKeyLoginOutcome
) {
    fun login(onekeyEnabled: Boolean): LoginOutcome {
        when (val token = tokenLogin()) {
            is TokenLoginOutcome.Success -> return LoginOutcome.Game(token.accountToken)
            TokenLoginOutcome.Fallback, TokenLoginOutcome.Skipped -> Unit
        }
        if (onekeyEnabled) {
            when (val onekey = onekeyLogin(true)) {
                is OneKeyLoginOutcome.Success -> return LoginOutcome.Game(onekey.accountToken)
                OneKeyLoginOutcome.Fallback, OneKeyLoginOutcome.Disabled -> Unit
            }
        }
        return LoginOutcome.ShowH5Login
    }
}
