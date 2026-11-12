package com.stash.data.download.lossless.addon

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.google.common.truth.Truth.assertThat
import com.stash.core.addons.UserAddonEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After

import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.GeneralSecurityException

/**
 * review-881 M11 / review-882 J2: a sealed list that fails to open once was read as "no add-ons",
 * and the next Add, Remove or switch sealed that empty list over the real one. The Keystore fails
 * for a moment now and then (`KeysetRecovery`), so that lost the user's add-ons (and, from phase 4,
 * their own Tidal server) for good.
 *
 * [EncryptedUserAddons] now tells "nothing stored" from "stored, and it did not open": the second
 * keeps what is stored, tries the read again on the next access, and refuses every write until it
 * opens. Only a list sealed with a key that is provably gone reads as empty.
 */
class EncryptedUserAddonsReadTest {

    private val dir: File = Files.createTempDirectory("user-addons").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private val a = UserAddonEntry("https://a.example/secret-path/manifest.json", token = "tok-a")
    private val b = UserAddonEntry("https://b.example/manifest.json")

    private fun newStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "u.preferences_pb") }

    /** A store whose earlier run added [a], with the working key. */
    private fun storeHoldingA(): DataStore<Preferences> = newStore().also { ds ->
        runBlocking { EncryptedUserAddons(ds, Key1).put(a) }
    }

    private fun urls(list: List<UserAddonEntry>) = list.map { it.manifestUrl }

    /** What [write] threw, or null. */
    private suspend fun thrownBy(write: suspend () -> Unit): Throwable? = try {
        write()
        null
    } catch (e: Exception) {
        e
    }

    /** What the next cold start reads, with the working key. */
    private fun storedNow(ds: DataStore<Preferences>): List<UserAddonEntry> = runBlocking { EncryptedUserAddons(ds, Key1).loadedEntries() }

    /** A reversible scramble standing in for the phone's key. */
    private object Key1 : EncryptedUserAddons.Sealer {
        override fun seal(plain: ByteArray) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun open(sealed: ByteArray) = seal(sealed)
    }

    /** [Key1], with the Keystore failing for the first [failures] opens. */
    private class Flaky(@Volatile var failures: Int) : EncryptedUserAddons.Sealer {
        override fun seal(plain: ByteArray) = Key1.seal(plain)
        override fun open(sealed: ByteArray): ByteArray {
            if (failures > 0) {
                failures--
                throw GeneralSecurityException("keystore busy")
            }
            return Key1.open(sealed)
        }
    }

    /** The Keystore is not answering at all, for seals as for opens. */
    private object Down : EncryptedUserAddons.Sealer {
        override fun seal(plain: ByteArray): ByteArray = throw GeneralSecurityException("keystore down")
        override fun open(sealed: ByteArray): ByteArray = throw GeneralSecurityException("keystore down")
    }

    /** A new key (the keyset was replaced): it seals and opens its own, and opens nothing sealed with [Key1]. */
    private object Key2 : EncryptedUserAddons.Sealer {
        private const val MARK: Byte = 0x02
        override fun seal(plain: ByteArray) = byteArrayOf(MARK) + plain.map { (it.toInt() xor 0x33).toByte() }
        override fun open(sealed: ByteArray): ByteArray {
            if (sealed.isEmpty() || sealed[0] != MARK) throw GeneralSecurityException("decryption failed")
            return sealed.drop(1).map { (it.toInt() xor 0x33).toByte() }.toByteArray()
        }
    }

    @Test fun `an open that throws once - a put after it keeps the earlier entries`() = runBlocking<Unit> {
        val ds = storeHoldingA()
        val users = EncryptedUserAddons(ds, Flaky(failures = 1))
        users.put(b)
        assertThat(urls(users.entries.value)).containsExactly(a.manifestUrl, b.manifestUrl).inOrder()
        assertThat(urls(storedNow(ds))).containsExactly(a.manifestUrl, b.manifestUrl).inOrder()
        assertThat(storedNow(ds).first().token).isEqualTo("tok-a")
    }

    @Test fun `a list that did not open at start is read again by the next write, and nothing is lost`() = runBlocking<Unit> {
        val ds = storeHoldingA()
        // Two failures: the stored list, then the key's own check. The first read gives up for now.
        val users = EncryptedUserAddons(ds, Flaky(failures = 2))
        assertThat(users.loadedEntries()).isEmpty()
        assertThat(users.unreadable).isTrue()

        users.setEnabled(a.manifestUrl, false)
        assertThat(users.unreadable).isFalse()
        assertThat(users.entries.value).containsExactly(a.copy(enabled = false))
        assertThat(storedNow(ds)).containsExactly(a.copy(enabled = false))
    }

    @Test fun `while it does not open every write is refused and the stored list is untouched`() = runBlocking<Unit> {
        val ds = storeHoldingA()
        val users = EncryptedUserAddons(ds, Down)
        assertThat(users.loadedEntries()).isEmpty()
        assertThat(users.unreadable).isTrue()

        assertThat(thrownBy { users.put(b) }).isInstanceOf(EncryptedUserAddons.UnreadableException::class.java)
        assertThat(thrownBy { users.remove(a.manifestUrl) }).isInstanceOf(EncryptedUserAddons.UnreadableException::class.java)
        assertThat(thrownBy { users.setEnabled(a.manifestUrl, false) }).isInstanceOf(EncryptedUserAddons.UnreadableException::class.java)
        assertThat(users.entries.value).isEmpty()

        // The next cold start, with the Keystore back, has everything.
        assertThat(storedNow(ds)).containsExactly(a)
    }

    @Test fun `the read is tried again on a later access, at most every few seconds`() = runBlocking<Unit> {
        val ds = storeHoldingA()
        var now = 1_000_000L
        val keystore = Flaky(failures = 2)
        val users = EncryptedUserAddons(ds, keystore) { now }
        assertThat(users.loadedEntries()).isEmpty()

        // Asked again at once: not tried (a lookup asks on every play).
        keystore.failures = 5
        assertThat(users.loadedEntries()).isEmpty()
        assertThat(keystore.failures).isEqualTo(5)

        // A few seconds on, with the Keystore answering again: the list is back, for every reader.
        keystore.failures = 0
        now += EncryptedUserAddons.RETRY_EVERY_MS
        assertThat(users.loadedEntries()).containsExactly(a)
        assertThat(users.entries.value).containsExactly(a)
        assertThat(users.unreadable).isFalse()
    }

    @Test fun `a list sealed with a key that is gone reads as empty, and the next write replaces it`() = runBlocking<Unit> {
        val ds = storeHoldingA()
        val users = EncryptedUserAddons(ds, Key2)
        assertThat(users.loadedEntries()).isEmpty()
        assertThat(users.unreadable).isFalse()

        users.put(b)
        assertThat(users.entries.value).containsExactly(b)
        val next = EncryptedUserAddons(ds, Key2)
        assertThat(next.loadedEntries()).containsExactly(b)
    }

    @Test fun `nothing stored is an empty list that can be written`() = runBlocking<Unit> {
        val ds = newStore()
        val users = EncryptedUserAddons(ds, Flaky(failures = 0))
        assertThat(users.loadedEntries()).isEmpty()
        assertThat(users.unreadable).isFalse()
        users.put(a)
        assertThat(storedNow(ds)).containsExactly(a)
    }

    @Test fun `a seal that fails leaves the list as it was, in memory and on disk`() = runBlocking<Unit> {
        val ds = storeHoldingA()
        val sealFails = object : EncryptedUserAddons.Sealer {
            override fun seal(plain: ByteArray): ByteArray =
                // The key's own check seals five bytes; a list is longer.
                if (plain.size > 5) throw GeneralSecurityException("keystore busy") else Key1.seal(plain)
            override fun open(sealed: ByteArray) = Key1.open(sealed)
        }
        val users = EncryptedUserAddons(ds, sealFails)
        withTimeout(5_000) { users.entries.first { it.size == 1 } }
        assertThat(thrownBy { users.put(b) }).isInstanceOf(GeneralSecurityException::class.java)
        assertThat(users.entries.value).containsExactly(a)
        assertThat(storedNow(ds)).containsExactly(a)
    }
}
