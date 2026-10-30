package com.qishimeng.game.privacy

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class EncryptedDeviceCodeStore(context: Context) : DeviceCodePersistence {
    private val storage = EncryptedStringPreferences(context, "device_code", "qishimeng_device_code")

    override var deviceCode: String?
        get() = storage.value
        set(value) {
            storage.value = value
        }
}

class EncryptedStringPreferences(context: Context, name: String, private val keyAlias: String) {
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    var value: String?
        get() {
            val encoded = preferences.getString("encrypted", null) ?: return null
            return runCatching {
                val payload = Base64.decode(encoded, Base64.NO_WRAP)
                val iv = payload.copyOfRange(0, IV_LENGTH)
                val ciphertext = payload.copyOfRange(IV_LENGTH, payload.size)
                Cipher.getInstance(TRANSFORMATION).run {
                    init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
                    String(doFinal(ciphertext), Charsets.UTF_8)
                }
            }.getOrNull()
        }
        set(value) {
            if (value == null) {
                // 删除返回前完成落盘，避免退出进程后恢复已经失效的 Token。
                check(preferences.edit().remove("encrypted").commit()) { "加密存储删除失败" }
                return
            }
            val encrypted = Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.ENCRYPT_MODE, getKey())
                iv + doFinal(value.toByteArray(Charsets.UTF_8))
            }
            preferences.edit().putString("encrypted", Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
        }

    private fun getKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_LENGTH_BITS = 128
    }
}
