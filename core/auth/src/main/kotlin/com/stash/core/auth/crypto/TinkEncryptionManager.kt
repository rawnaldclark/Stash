package com.stash.core.auth.crypto

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides AES-256-GCM authenticated encryption backed by Android Keystore.
 *
 * The master key is stored in Android Keystore and never leaves the secure hardware.
 * The Tink keyset (encrypted by the master key) is persisted in SharedPreferences so
 * it survives app restarts.
 */
@Singleton
class TinkEncryptionManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, KEYSET_PREFS)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(Aead::class.java)
    }

    /**
     * Encrypts [plaintext] using AES-256-GCM.
     *
     * @return The ciphertext including the authentication tag and IV.
     */
    fun encrypt(plaintext: ByteArray): ByteArray =
        aead.encrypt(plaintext, EMPTY_ASSOCIATED_DATA)

    /**
     * Decrypts [ciphertext] previously produced by [encrypt].
     *
     * @throws java.security.GeneralSecurityException if the ciphertext is tampered or the key is wrong.
     */
    fun decrypt(ciphertext: ByteArray): ByteArray =
        aead.decrypt(ciphertext, EMPTY_ASSOCIATED_DATA)

    private companion object {
        const val KEYSET_NAME = "stash_auth_keyset"
        const val KEYSET_PREFS = "stash_auth_keyset_prefs"
        const val MASTER_KEY_URI = "android-keystore://stash_master_key"
        val EMPTY_ASSOCIATED_DATA = ByteArray(0)
    }
}
