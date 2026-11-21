package com.stash.data.download.lossless.tidal

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Android key attestation for [TidalInstallPass]: a new EC P-256 key in the Android Keystore (the phone's TEE, or
 * StrongBox where the phone keeps it there) made with the add-on's challenge as its attestation challenge. Android
 * hands back the key's certificate chain: the leaf carries the attestation (the challenge, the app's package and
 * signing certificate, the phone's boot state), and the chain runs up to Google's attestation root. The key itself is
 * never used: it is deleted once its chain is read. Null when the phone can't attest (no hardware, or an error).
 */
@Singleton
class KeystoreAttestation @Inject constructor() : InstallAttestation {
    private val lock = Mutex()

    override suspend fun chain(challenge: ByteArray): List<String>? = lock.withLock {
        withContext(Dispatchers.IO) {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            try {
                runCatching { keyStore.deleteEntry(ALIAS) }
                val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setAttestationChallenge(challenge)
                    .build()
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).run {
                    initialize(spec)
                    generateKeyPair()
                }
                keyStore.getCertificateChain(ALIAS)
                    ?.takeIf { it.size >= 2 }
                    ?.map { Base64.getEncoder().encodeToString(it.encoded) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // GeneralSecurityException, ProviderException (no attestation on this phone), or a keystore fault.
                Log.i(TAG, "no attestation: ${e.javaClass.simpleName}")
                null
            } finally {
                runCatching { keyStore.deleteEntry(ALIAS) }
            }
        }
    }

    private companion object {
        const val TAG = "KeystoreAttestation"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "stash-tidal-register"
    }
}
