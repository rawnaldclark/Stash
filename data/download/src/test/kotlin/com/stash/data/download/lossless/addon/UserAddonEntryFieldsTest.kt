package com.stash.data.download.lossless.addon

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import com.stash.core.addons.AddonSees
import com.stash.core.addons.UserAddonEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * What a sealed add-on entry carries since phase 4 (plan §2.13): the id and name it was added
 * under, what the user agreed its server may see, and when. And Replace link (§2.9): the new link
 * takes the old one's place.
 */
class UserAddonEntryFieldsTest {

    private val dir: File = Files.createTempDirectory("addon-entries").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "u.preferences_pb") }

    @After fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    /** Not encryption: a reversible scramble, so the test reads what was sealed. */
    private object Scramble : EncryptedUserAddons.Sealer {
        override fun seal(plain: ByteArray) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun open(sealed: ByteArray) = seal(sealed)
    }

    private val first = UserAddonEntry(
        "https://relay.example.org/v1/tok/manifest.json", id = "hifi", name = "Hi-Fi Relay",
        consent = setOf(AddonSees.PLAYS.name, AddonSees.NEXT_UP.name), addedAt = 1_794_355_200_000L,
    )
    private val second = UserAddonEntry("https://two.example/manifest.json", id = "two", name = "Two")

    @Test fun `the id, the name, what was agreed and when are sealed and read back by the next run`() = runBlocking<Unit> {
        val users = EncryptedUserAddons(store, Scramble)
        users.put(first)
        users.put(second)
        val next = EncryptedUserAddons(store, Scramble)
        assertThat(next.loadedEntries()).containsExactly(first, second).inOrder()
        // Nothing agreed is not the same as agreeing to nothing.
        assertThat(next.loadedEntries().last().consent).isNull()
    }

    @Test fun `agreeing to more is recorded on that add-on only`() = runBlocking<Unit> {
        val users = EncryptedUserAddons(store, Scramble)
        users.put(first)
        users.put(second)
        users.setConsent(first.manifestUrl, setOf(AddonSees.PLAYS.name, AddonSees.NEXT_UP.name, AddonSees.SAVES.name))
        val now = EncryptedUserAddons(store, Scramble).loadedEntries()
        assertThat(now.first().consent).containsExactly("PLAYS", "NEXT_UP", "SAVES")
        assertThat(now.last()).isEqualTo(second)
    }

    @Test fun `a replaced link keeps its add-on's place in the list`() = runBlocking<Unit> {
        val users = EncryptedUserAddons(store, Scramble)
        users.put(first)
        users.put(second)
        val replaced = first.copy(manifestUrl = "https://relay.example.org/v1/new-tok/manifest.json")
        users.replace(first.manifestUrl, replaced)
        assertThat(users.entries.value).containsExactly(replaced, second).inOrder()
        assertThat(EncryptedUserAddons(store, Scramble).loadedEntries()).containsExactly(replaced, second).inOrder()
        // Replacing one that isn't there adds it.
        val third = UserAddonEntry("https://three.example/manifest.json", id = "three")
        users.replace("https://gone.example/manifest.json", third)
        assertThat(users.entries.value).containsExactly(replaced, second, third).inOrder()
    }

    @Test fun `a list sealed before these fields existed still reads`() = runBlocking<Unit> {
        // As phase 2 sealed it: a manifest, a token and the switch.
        val old = """[{"manifest":"https://old.example/manifest.json","token":"t","enabled":false}]"""
        val blob = java.util.Base64.getEncoder().encodeToString(Scramble.seal(old.toByteArray()))
        store.updateData { it.toMutablePreferences().apply { set(androidx.datastore.preferences.core.stringPreferencesKey("sealed"), blob) } }
        val entry = EncryptedUserAddons(store, Scramble).loadedEntries().single()
        assertThat(entry).isEqualTo(UserAddonEntry("https://old.example/manifest.json", token = "t", enabled = false))
        assertThat(entry.id).isNull()
        assertThat(entry.consent).isNull()
    }
}
