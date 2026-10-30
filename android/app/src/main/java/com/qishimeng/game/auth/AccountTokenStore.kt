package com.qishimeng.game.auth

import android.content.Context
import com.qishimeng.game.privacy.EncryptedStringPreferences

interface AccountTokenStore {
    var token: String?
}

class EncryptedAccountTokenStore(context: Context) : AccountTokenStore {
    private val storage = EncryptedStringPreferences(context, "account_token", "qishimeng_account_token")

    override var token: String?
        get() = storage.value
        set(value) {
            storage.value = value
        }
}
