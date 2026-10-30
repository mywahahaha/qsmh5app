package com.qishimeng.game.privacy

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest
import java.security.SecureRandom

interface DeviceIdentifiers {
    fun oaid(): String?
    fun androidId(): String?
}

interface DeviceCodePersistence {
    var deviceCode: String?
}

class DeviceCodeProvider(
    private val identifiers: DeviceIdentifiers,
    private val persistence: DeviceCodePersistence,
    private val diyValue: () -> String = { ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) } }
) {
    @Synchronized
    fun get(privacyAgreed: Boolean): String {
        if (!privacyAgreed) return ""
        persistence.deviceCode?.takeIf(String::isNotBlank)?.let { return it }
        val oaid = runCatching { identifiers.oaid() }.getOrNull()?.takeIf(String::isNotBlank)
        val androidId = if (oaid == null) runCatching { identifiers.androidId() }.getOrNull()?.takeIf(String::isNotBlank) else null
        val code = when {
            oaid != null -> md5(oaid).uppercase()
            androidId != null -> md5(androidId).uppercase()
            else -> md5(diyValue()).lowercase()
        }
        persistence.deviceCode = code
        return code
    }

    private fun md5(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

class AndroidDeviceIdentifiers(
    private val context: Context,
    private val oaidProvider: () -> String? = { null }
) : DeviceIdentifiers {
    override fun oaid(): String? = oaidProvider()

    override fun androidId(): String? = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ANDROID_ID
    )
}
