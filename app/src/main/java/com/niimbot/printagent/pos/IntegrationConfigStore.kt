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

        private const val PREFS_NAME = "niimbot_pos_integration"
        private const val PREF_BASE_URL = "base_url"
        private const val PREF_TOKEN_CIPHERTEXT = "access_token_ciphertext"
        private const val PREF_TOKEN_IV = "access_token_iv"
        private const val PREF_USERNAME = "username"
        private const val PREF_ROLE = "role"
        private const val KEY_ALIAS = "niimbot_pos_access_token"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        // Remove obsolete credentials left by integration-key versions.
        prefs.edit()
            .remove("key_ciphertext")
            .remove("key_iv")
            .remove("supplier_token_ciphertext")
            .remove("supplier_token_iv")
            .apply()
    }

    fun getBaseUrl(): String = PosProductRules.normalizeBaseUrl(
        prefs.getString(PREF_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
    )

    fun setBaseUrl(value: String) {
        prefs.edit().putString(PREF_BASE_URL, PosProductRules.normalizeBaseUrl(value)).apply()
    }

    fun hasAccessToken(): Boolean = prefs.contains(PREF_TOKEN_CIPHERTEXT) && getAccessToken() != null

    fun setAuthenticatedSession(accessToken: String, identity: PosIdentity) {
        val cleanToken = accessToken.trim()
        require(cleanToken.isNotEmpty()) { "Access token must not be blank" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        }
        val encrypted = cipher.doFinal(cleanToken.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(PREF_TOKEN_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(PREF_TOKEN_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(PREF_USERNAME, identity.username)
            .putString(PREF_ROLE, identity.role)
            .apply()
    }

    fun getAccessToken(): String? {
        val ciphertext = prefs.getString(PREF_TOKEN_CIPHERTEXT, null) ?: return null
        val iv = prefs.getString(PREF_TOKEN_IV, null) ?: return null
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

    fun getIdentity(): PosIdentity? {
        if (!hasAccessToken()) return null
        val username = prefs.getString(PREF_USERNAME, null) ?: return null
        val role = prefs.getString(PREF_ROLE, null) ?: return null
        return PosIdentity(username, role)
    }

    fun clearSession() {
        prefs.edit()
            .remove(PREF_TOKEN_CIPHERTEXT)
            .remove(PREF_TOKEN_IV)
            .remove(PREF_USERNAME)
            .remove(PREF_ROLE)
            .apply()
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
