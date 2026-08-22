package com.niimbot.printagent.pos

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class IntegrationConfigStore(context: Context) {
    companion object {
        const val DEFAULT_BASE_URL = "https://api.ijm.lithiaproject.site"
        const val MASKED_KEY = "••••••••"

        private const val PREFS_NAME = "niimbot_pos_integration"
        private const val PREF_BASE_URL = "base_url"
        private const val PREF_KEY_CIPHERTEXT = "key_ciphertext"
        private const val PREF_KEY_IV = "key_iv"
        private const val KEY_ALIAS = "niimbot_pos_integration_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getBaseUrl(): String = PosProductRules.normalizeBaseUrl(
        prefs.getString(PREF_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
    )

    fun setBaseUrl(value: String) {
        prefs.edit().putString(PREF_BASE_URL, PosProductRules.normalizeBaseUrl(value)).apply()
    }

    fun hasIntegrationKey(): Boolean = prefs.contains(PREF_KEY_CIPHERTEXT) && getIntegrationKey() != null

    fun setIntegrationKey(value: String) {
        val cleanValue = value.trim()
        if (cleanValue.isEmpty()) {
            clearIntegrationKey()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        }
        val encrypted = cipher.doFinal(cleanValue.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(PREF_KEY_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(PREF_KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun getIntegrationKey(): String? {
        val ciphertext = prefs.getString(PREF_KEY_CIPHERTEXT, null) ?: return null
        val iv = prefs.getString(PREF_KEY_IV, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    getOrCreateSecretKey(),
                    GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
                )
            }
            cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    fun clearIntegrationKey() {
        prefs.edit().remove(PREF_KEY_CIPHERTEXT).remove(PREF_KEY_IV).apply()
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }
}
