package com.stash.core.data.weblink.store

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.stash.core.auth.crypto.TinkEncryptionManager
import com.stash.core.data.weblink.SyncCrypto
import com.stash.core.data.weblink.WebLinkIds
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.PrivateKey
import java.security.UnrecoverableKeyException
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [WebLinkStore] in `stash_sync.db`. Secrets (the token, the space keys, a software device key) are sealed with the app's Tink
 * keyset, whose master key is in the Android Keystore. The long-term device key is an Android Keystore `PURPOSE_AGREE_KEY`
 * key on API 31+ (ECDH inside the Keystore; the private key never leaves it), otherwise a software P-256 key kept sealed
 * (spec §5, sync-v1 §1).
 */
@Singleton
class AndroidWebLinkStore @Inject constructor(
    private val db: SyncDatabase,
    private val tink: TinkEncryptionManager,
) : WebLinkStore {
    private val dao get() = db.dao()
    private val lock = Mutex()

    override suspend fun identity(): LinkIdentity? = lock.withLock {
        val row = dao.space() ?: return null
        val priv = readKey { row.deviceKeyWrapped?.let { softwareKey(tink.decrypt(it)) } ?: keystoreKey() } ?: return null
        val token = readKey { tink.decrypt(row.tokenSealed) } ?: return null
        LinkIdentity(row.deviceId, token, row.devicePub, priv, row.deviceName)
    }

    override suspend fun deviceToken(): Pair<String, ByteArray>? = lock.withLock {
        val row = dao.space() ?: return null
        runCatching { row.deviceId to tink.decrypt(row.tokenSealed) }.getOrNull()
    }

    /**
     * Tells a key that is definitely gone (null: no Keystore entry, an unrecoverable entry, a seal that no longer opens, such as
     * after a device-to-device transfer) from a passing Keystore or provider failure ([LinkStoreUnavailable]): only the first
     * unlinks this phone.
     */
    private inline fun <T> readKey(read: () -> T?): T? = try {
        read()
    } catch (e: UnrecoverableKeyException) {
        Log.w(TAG, "device key gone (${e.javaClass.simpleName})")
        null
    } catch (e: GeneralSecurityException) {
        if (e is KeyStoreException) throw LinkStoreUnavailable(e)
        Log.w(TAG, "sealed link state no longer opens (${e.javaClass.simpleName})")
        null
    } catch (e: Exception) {
        Log.w(TAG, "link state unreadable for now (${e.javaClass.simpleName})")
        throw LinkStoreUnavailable(e)
    }

    override suspend fun createIdentity(name: String): LinkIdentity = lock.withLock {
        val deviceId = WebLinkIds.newDeviceId()
        val token = SyncCrypto.randomBytes(32)
        val (pub, priv, wrapped) = newDeviceKey()
        dao.wipe()
        dao.putSpace(
            SyncSpaceEntity(
                deviceId = deviceId,
                tokenSealed = tink.encrypt(token),
                devicePub = pub,
                deviceKeyWrapped = wrapped?.let(tink::encrypt),
                deviceName = name,
            ),
        )
        LinkIdentity(deviceId, token, pub, priv, name)
    }

    override suspend fun setName(name: String): Unit = lock.withLock {
        dao.space()?.let { dao.putSpace(it.copy(deviceName = name)) }
        Unit
    }

    override suspend fun space(): LinkedSpace? = lock.withLock {
        val row = dao.space() ?: return null
        val id = row.spaceId ?: return null
        val k = row.keySealed?.let { readKey { tink.decrypt(it) } } ?: return null
        LinkedSpace(id, row.epoch, k, row.prevEpoch, row.prevKeySealed?.let { readKey { tink.decrypt(it) } })
    }

    override suspend fun saveSpace(space: LinkedSpace): Unit = lock.withLock {
        val row = dao.space() ?: error("no identity")
        dao.putSpace(
            row.copy(
                spaceId = space.spaceId,
                epoch = space.epoch,
                keySealed = tink.encrypt(space.k),
                prevEpoch = space.prevEpoch,
                prevKeySealed = space.prevK?.let(tink::encrypt),
                linkedAt = if (row.spaceId == space.spaceId) row.linkedAt else System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun roster(): List<RosterEntry> = lock.withLock {
        dao.roster().map { RosterEntry(it.deviceId, it.type, it.labelName, it.pub, it.nickname) }
    }

    override suspend fun saveRoster(entries: List<RosterEntry>): Unit = lock.withLock {
        dao.replaceRoster(entries.map { SyncRosterEntity(it.deviceId, it.type, it.labelName, it.pub, it.nickname) })
    }

    override suspend fun wipe(): Unit = lock.withLock {
        dao.wipe()
        try {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        } catch (e: Exception) {
            Log.w(TAG, "couldn't delete the device key (${e.javaClass.simpleName})")
        }
    }

    // -------------------------------------------------------------------------------------------- device key

    private fun newDeviceKey(): Triple<ByteArray, PrivateKey, ByteArray?> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
                gen.initialize(
                    KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_AGREE_KEY)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .build(),
                )
                val pair = gen.generateKeyPair()
                return Triple(SyncCrypto.rawPublicKey(pair.public as ECPublicKey), pair.private, null)
            } catch (e: Exception) {
                // Some Keystores refuse agreement keys; a sealed software key is the spec's pre-31 path anyway.
                Log.w(TAG, "Keystore agreement key unavailable (${e.javaClass.simpleName}); using a sealed software key")
            }
        }
        val pair = SyncCrypto.newKeyPair()
        return Triple(SyncCrypto.rawPublicKey(pair.public as ECPublicKey), pair.private, pair.private.encoded)
    }

    private fun keystoreKey(): PrivateKey? =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.getKey(KEY_ALIAS, null) as? PrivateKey

    private fun softwareKey(pkcs8: ByteArray): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))

    private companion object {
        const val TAG = "WebLinkStore"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "stash_sync_device_key"
    }
}
