package com.qishimeng.game.privacy

import android.content.Context

interface PrivacyPreferences {
    var agreed: Boolean
}

class PrivacyConsentStore(private val preferences: PrivacyPreferences) {
    fun isAgreed(): Boolean = preferences.agreed

    fun shouldShowAgreement(privacyAuto: Boolean): Boolean {
        if (preferences.agreed) return false
        if (!privacyAuto) {
            preferences.agreed = true
            return false
        }
        return true
    }

    fun agree() {
        preferences.agreed = true
    }

    fun reject() {
        preferences.agreed = false
    }
}

class SharedPreferencesPrivacyStore(context: Context) : PrivacyPreferences {
    private val preferences = context.getSharedPreferences("privacy_state", Context.MODE_PRIVATE)

    override var agreed: Boolean
        get() = preferences.getBoolean("agreed", false)
        set(value) {
            preferences.edit().putBoolean("agreed", value).apply()
        }
}
