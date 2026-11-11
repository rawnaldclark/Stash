package com.stash.data.download.lossless.tidal

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.addons.UserAddonEntry
import com.stash.core.addons.UserAddonKind
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.addon.EncryptedUserAddons
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

/**
 * The Custom Tidal server's move into the sealed add-on store (add-ons plan phase 4 §2.10): it
 * moves and the plain keys go, a second run changes nothing, a Keystore failure keeps the keys
 * (and the server keeps answering from them), and Remove takes it out of both places.
 */
@RunWith(RobolectricTestRunner::class)
class OwnTidalServerMigrationTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = LosslessSourcePreferences(ctx, mockk<DownloadQueueDao>(relaxed = true))
    private val dir: File = Files.createTempDirectory("own-tidal").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ds = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "user_addons.preferences_pb") }

    /** A reversible scramble standing in for Tink, so the file never holds the plaintext. */
    private object Scramble : EncryptedUserAddons.Sealer {
        override fun seal(plain: ByteArray) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun open(sealed: ByteArray) = seal(sealed)
    }

    /** The Keystore refusing: every seal fails. */
    private object KeystoreDown : EncryptedUserAddons.Sealer {
        override fun seal(plain: ByteArray): ByteArray = throw java.security.GeneralSecurityException("keystore unavailable")
        override fun open(sealed: ByteArray) = Scramble.open(sealed)
    }

    private val base = "https://tidal.mine.example/p4-test-path"
    private val token = "p4-test-token"

    @Before fun clean() = runBlocking { prefs.clearBitchordServer() }

    @After fun tearDown() {
        runBlocking { prefs.clearBitchordServer() }
        scope.cancel()
        dir.deleteRecursively()
    }

    private suspend fun setOldField(endpoint: String = base, accessToken: String? = token) = prefs.writeRawForTest {
        it[stringPreferencesKey("bitchord_endpoint")] = endpoint
        if (accessToken != null) it[stringPreferencesKey("bitchord_access_token")] = accessToken
    }

    private fun store(sealer: EncryptedUserAddons.Sealer = Scramble) = EncryptedUserAddons(ds, sealer)

    private fun migration(users: EncryptedUserAddons) = OwnTidalServerMigration(prefs) { users.put(it) }

    @Test fun `the server moves into the sealed store and the plain keys go`() = runBlocking<Unit> {
        setOldField()
        val users = store()
        users.put(UserAddonEntry("https://other.example/manifest.json"))

        assertThat(migration(users).run()).isEqualTo(OwnTidalServerMigration.Outcome.MOVED)

        val own = users.loadedEntries().single { it.kind == UserAddonKind.OWN_TIDAL_SERVER }
        assertThat(own.manifestUrl).isEqualTo(base)
        assertThat(own.token).isEqualTo(token)
        assertThat(prefs.bitchordEndpointNow()).isNull()
        assertThat(prefs.bitchordAccessTokenNow()).isNull()
        // The link add-on is untouched, and a later start reads the server back.
        assertThat(store().loadedEntries().map { it.kind }).containsExactly(UserAddonKind.MANIFEST, UserAddonKind.OWN_TIDAL_SERVER)
        assertThat(OwnTidalServer(store(), prefs).now()).isEqualTo(OwnTidalServer.Server(base, token))
        // Sealed: neither the path nor the token is on disk in plain text.
        val onDisk = File(dir, "user_addons.preferences_pb").readBytes().toString(Charsets.ISO_8859_1)
        assertThat(onDisk).doesNotContain("p4-test-path")
        assertThat(onDisk).doesNotContain(token)
    }

    @Test fun `running it twice changes nothing`() = runBlocking<Unit> {
        setOldField()
        val users = store()
        migration(users).run()
        val after = users.loadedEntries()
        assertThat(migration(users).run()).isEqualTo(OwnTidalServerMigration.Outcome.NOTHING_TO_MOVE)
        assertThat(users.loadedEntries()).isEqualTo(after)
    }

    @Test fun `nothing set, nothing moves`() = runBlocking<Unit> {
        val users = store()
        assertThat(migration(users).run()).isEqualTo(OwnTidalServerMigration.Outcome.NOTHING_TO_MOVE)
        assertThat(users.loadedEntries()).isEmpty()
        assertThat(OwnTidalServer(users, prefs).now()).isNull()
    }

    @Test fun `a Keystore failure keeps the keys, the server keeps answering, and the next start moves it`() = runBlocking<Unit> {
        setOldField()
        val down = store(KeystoreDown)
        assertThat(migration(down).run()).isEqualTo(OwnTidalServerMigration.Outcome.KEPT_FOR_NEXT_START)
        assertThat(prefs.bitchordEndpointNow()).isEqualTo(base)
        assertThat(prefs.bitchordAccessTokenNow()).isEqualTo(token)
        assertThat(OwnTidalServer(down, prefs).now()).isEqualTo(OwnTidalServer.Server(base, token))

        val up = store()
        assertThat(migration(up).run()).isEqualTo(OwnTidalServerMigration.Outcome.MOVED)
        assertThat(prefs.bitchordEndpointNow()).isNull()
        assertThat(OwnTidalServer(up, prefs).now()).isEqualTo(OwnTidalServer.Server(base, token))
    }

    @Test fun `a token a request can't carry moves without it`() = runBlocking<Unit> {
        setOldField(accessToken = "bad\ntoken")
        val users = store()
        assertThat(migration(users).run()).isEqualTo(OwnTidalServerMigration.Outcome.MOVED)
        assertThat(OwnTidalServer(users, prefs).now()).isEqualTo(OwnTidalServer.Server(base, null))
    }

    @Test fun `an open server with no token moves as it is`() = runBlocking<Unit> {
        setOldField(accessToken = null)
        val users = store()
        migration(users).run()
        assertThat(OwnTidalServer(users, prefs).now()).isEqualTo(OwnTidalServer.Server(base, null))
    }

    @Test fun `Remove takes the server and its token out of both places`() = runBlocking<Unit> {
        setOldField()
        val users = store()
        migration(users).run()
        setOldField() // as if a move had not finished
        val own = OwnTidalServer(users, prefs) { users.removeKind(UserAddonKind.OWN_TIDAL_SERVER) }
        own.remove()
        assertThat(own.now()).isNull()
        assertThat(prefs.bitchordEndpointNow()).isNull()
    }

    @Test fun `only one own server is kept, and a link add-on at the same address is separate`() = runBlocking<Unit> {
        val users = store()
        users.put(UserAddonEntry(base, token, kind = UserAddonKind.OWN_TIDAL_SERVER))
        users.put(UserAddonEntry("https://second.example", null, kind = UserAddonKind.OWN_TIDAL_SERVER))
        users.put(UserAddonEntry(base))
        assertThat(users.loadedEntries().map { it.kind to it.manifestUrl }).containsExactly(
            UserAddonKind.OWN_TIDAL_SERVER to "https://second.example",
            UserAddonKind.MANIFEST to base,
        )
        users.remove(base)
        assertThat(users.loadedEntries().map { it.kind }).containsExactly(UserAddonKind.OWN_TIDAL_SERVER)
    }

    @Test fun `a list sealed before phase 4 reads as link add-ons, and a kind from a newer build survives a write`() = runBlocking<Unit> {
        fun seal(json: String) = java.util.Base64.getEncoder().encodeToString(Scramble.seal(json.toByteArray()))
        ds.edit { it[stringPreferencesKey("sealed")] = seal("""[{"manifest":"https://old.example/manifest.json","token":null,"enabled":true},""" +
            """{"manifest":"https://next.example","kind":"SOMETHING_NEWER"}]""") }
        val users = store()
        assertThat(users.loadedEntries().map { it.kind to it.manifestUrl })
            .containsExactly(UserAddonKind.MANIFEST to "https://old.example/manifest.json")
        users.put(UserAddonEntry(base, token, kind = UserAddonKind.OWN_TIDAL_SERVER))
        val plain = String(Scramble.open(java.util.Base64.getDecoder().decode(ds.data.first()[stringPreferencesKey("sealed")]!!)))
        assertThat(plain).contains("SOMETHING_NEWER")
        assertThat(store().loadedEntries().map { it.kind }).containsExactly(UserAddonKind.MANIFEST, UserAddonKind.OWN_TIDAL_SERVER)
    }
}
