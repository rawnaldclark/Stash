package com.stash.core.data.weblink

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Device management on the phone (spec §2.6, §5.2, §12; sync-v1 §3.5, §3.6): the list from labels this phone opened, keys
 * pinned, rename, remove with a proven key rotation, catching up with someone else's rotation, `401 revoked`, unlink
 * everything.
 */
class WebLinkRepositoryTest {
    private val server = FakeSyncServer()
    private val store = InMemoryWebLinkStore()
    private val repo = WebLinkRepository(server, store)
    private var clock = 0L
    private val session = PairingSession(server, store, repo, phoneName = { "Pixel 5" }, now = { clock }, sleep = { clock += it })

    private suspend fun link(name: String): FakeBrowser {
        val b = FakeBrowser(server, name = name)
        val link = b.openCode()
        server.onAnswered = { slot ->
            b.readAnswer(slot)
            b.reply()
        }
        session.reset()
        session.open(link)
        assertThat(session.confirm()).isEqualTo(PairingState.Linked(name))
        return b
    }

    private fun linked() = repo.status.value as WebLinkStatus.Linked

    @Test fun `the list shows this phone first, then each browser by the name in its label`() = runTest {
        link("Chrome on Windows")
        link("Firefox on Mac")
        assertThat(repo.refresh()).isEqualTo(WebLinkResult.Ok)
        assertThat(linked().devices.map { it.name }).containsExactly("Pixel 5", "Chrome on Windows", "Firefox on Mac").inOrder()
        assertThat(linked().devices.map { it.trust }.toSet()).containsExactly(DeviceTrust.VERIFIED)
        assertThat(linked().devices.first().isMe).isTrue()
    }

    @Test fun `removing a browser cuts it off and rotates the key to the others, proven under the old key`() = runTest {
        val chrome = link("Chrome on Windows")
        val firefox = link("Firefox on Mac")
        val before = store.space()!!

        assertThat(repo.remove(firefox.deviceId)).isEqualTo(WebLinkResult.Ok)

        val space = server.spaces.getValue(before.spaceId)
        assertThat(space.devices.keys).doesNotContain(firefox.deviceId)
        assertThat(space.rotationDue).isFalse()
        assertThat(space.epoch).isEqualTo(2)
        val after = store.space()!!
        assertThat(after.epoch).isEqualTo(2)
        assertThat(Base64Url.encode(after.prevK!!)).isEqualTo(Base64Url.encode(before.k))
        // The remaining browser opens its envelope with its device key and checks the proof under K of epoch 1.
        val next = chrome.openKey(before.spaceId, 2, before.k)
        assertThat(Base64Url.encode(next.k)).isEqualTo(Base64Url.encode(after.k))
        assertThat(next.members).containsExactly(store.identity()!!.deviceId, chrome.deviceId)
        // No envelope for the removed one; labels re-sealed under the new key.
        assertThat(space.envelopes.keys).doesNotContain(firefox.deviceId to 2)
        assertThat(chrome.openOwnLabel(before.spaceId, after.k).name).isEqualTo("Chrome on Windows")
        assertThat(linked().devices.map { it.name }).containsExactly("Pixel 5", "Chrome on Windows").inOrder()
    }

    @Test fun `a rotation re-seals the mirror config under the new key`() = runTest {
        link("Chrome on Windows")
        val gone = link("Firefox on Mac")
        val sp = store.space()!!
        val config = """{"kind":"stash-mirror-config","v":1,"at":[1,0,"d_x"],"likes":{"dir":"off"},"plays":{"dir":"off"},"playlists":{"dir":"off","ids":[],"newOnes":false}}"""
        server.spaces.getValue(sp.spaceId).config =
            ConfigSlot("d_x", 1, SyncCrypto.seal(SyncCrypto.dataKey(sp.k, sp.spaceId), sp.spaceId, 1, SyncCrypto.Place.CONFIG, config))

        assertThat(repo.remove(gone.deviceId)).isEqualTo(WebLinkResult.Ok)
        val after = store.space()!!
        val env = server.lastRotate!!.config!!
        assertThat(SyncCrypto.open(SyncCrypto.dataKey(after.k, sp.spaceId), sp.spaceId, SyncCrypto.Place.CONFIG, env)).isEqualTo(config)
    }

    @Test fun `a config nobody can open doesn't block the rotation - the default goes in its place`() = runTest {
        link("Chrome on Windows")
        val gone = link("Firefox on Mac")
        val sp = store.space()!!
        // Valid AES-GCM under K, but not JSON: a buggy or hostile writer.
        server.spaces.getValue(sp.spaceId).config =
            ConfigSlot("d_x", 1, SyncCrypto.seal(SyncCrypto.dataKey(sp.k, sp.spaceId), sp.spaceId, 1, SyncCrypto.Place.CONFIG, "not json"))

        assertThat(repo.remove(gone.deviceId)).isEqualTo(WebLinkResult.Ok)
        val after = store.space()!!
        assertThat(after.epoch).isEqualTo(2)
        val text = SyncCrypto.open(SyncCrypto.dataKey(after.k, sp.spaceId), sp.spaceId, SyncCrypto.Place.CONFIG, server.lastRotate!!.config!!)
        assertThat(text).contains("\"kind\":\"stash-mirror-config\"")
        assertThat(text).contains("\"likes\":{\"dir\":\"off\"}")
        assertThat(text).contains(store.identity()!!.deviceId)
    }

    @Test fun `a passing Keystore failure keeps the link and reports it, never crashes`() = runTest {
        link("Chrome on Windows")
        val wipes = store.wiped
        store.failReads = true
        val r = repo.refresh()
        assertThat(r).isEqualTo(WebLinkResult.Failed(WebLinkCopy.STORE_BUSY))
        store.failReads = false
        assertThat(store.space()).isNotNull()
        assertThat(store.wiped).isEqualTo(wipes)
    }

    @Test fun `a device key that is definitely gone unlinks, and the phone removes itself on the server`() = runTest {
        link("Chrome on Windows")
        val sp = store.space()!!
        val me = store.identity()!!.deviceId
        val wipes = store.wiped
        store.keyGone = true
        assertThat(repo.refresh()).isEqualTo(WebLinkResult.Ok)
        assertThat(repo.status.value).isEqualTo(WebLinkStatus.NotLinked)
        assertThat(store.wiped).isEqualTo(wipes + 1)
        assertThat(server.spaces.getValue(sp.spaceId).devices.keys).doesNotContain(me)
    }

    @Test fun `a changed device set during the rotation is re-read and retried`() = runTest {
        link("Chrome on Windows")
        val gone = link("Firefox on Mac")
        server.failNext("rotate", SyncResult.Error(409, SyncErrorCode.DEVICES_CHANGED))
        assertThat(repo.remove(gone.deviceId)).isEqualTo(WebLinkResult.Ok)
        assertThat(server.calls.count { it == "rotate" }).isEqualTo(2)
        assertThat(store.space()!!.epoch).isEqualTo(2)
    }

    @Test fun `a device whose label carries another key shows Key changed and is removed before a rotation`() = runTest {
        val chrome = link("Chrome on Windows")
        val firefox = link("Firefox on Mac")
        val sp = store.space()!!
        // The server (or a removed device with K) puts a label with another key under Chrome's id.
        val imposter = SyncCrypto.rawPublicKey(SyncCrypto.newKeyPair().public as java.security.interfaces.ECPublicKey)
        server.spaces.getValue(sp.spaceId).devices.getValue(chrome.deviceId).labelCt = SyncCrypto.seal(
            SyncCrypto.dataKey(sp.k, sp.spaceId), sp.spaceId, 1, SyncCrypto.Place.label(chrome.deviceId),
            SyncKeys.labelJson(SyncKeys.Label("Chrome on Windows", "web", chrome.deviceId, imposter)),
        )
        repo.refresh()
        assertThat(linked().devices.first { it.id == chrome.deviceId }.trust).isEqualTo(DeviceTrust.KEY_CHANGED)
        // The pin holds.
        assertThat(store.roster().first { it.deviceId == chrome.deviceId }.pub).isEqualTo(chrome.pub)

        // Removing Firefox needs a rotation; Chrome can't be sealed to, so it is removed first and gets no key.
        assertThat(repo.remove(firefox.deviceId)).isEqualTo(WebLinkResult.Ok)
        assertThat(server.calls).contains("remove:${chrome.deviceId}")
        assertThat(server.lastRotate!!.envelopes.keys).containsExactly(store.identity()!!.deviceId)
    }

    @Test fun `someone else's rotation is caught up with one proven envelope at a time`() = runTest {
        val chrome = link("Chrome on Windows")
        val sp = store.space()!!
        val me = store.identity()!!
        val space = server.spaces.getValue(sp.spaceId)
        val members = space.devices.keys.sorted()
        // Chrome rotates twice (epoch 2, then 3) while the phone is away.
        val k2 = SyncCrypto.randomBytes(32)
        val k3 = SyncCrypto.randomBytes(32)
        space.envelopes[me.deviceId to 2] = SyncKeys.sealKeyFor(k2, sp.spaceId, 2, me.deviceId, me.devicePub, members, sp.k)
        space.envelopes[me.deviceId to 3] = SyncKeys.sealKeyFor(k3, sp.spaceId, 3, me.deviceId, me.devicePub, members, k2)
        space.epoch = 3
        space.devices.values.forEach { d ->
            val label = if (d.id == me.deviceId) SyncKeys.Label("Pixel 5", "phone", d.id, me.devicePub) else chrome.label()
            d.labelCt = SyncCrypto.seal(SyncCrypto.dataKey(k3, sp.spaceId), sp.spaceId, 3, SyncCrypto.Place.label(d.id), SyncKeys.labelJson(label))
        }

        assertThat(repo.refresh()).isEqualTo(WebLinkResult.Ok)
        assertThat(server.calls).containsAtLeast("key:2", "key:3").inOrder()
        assertThat(store.space()!!.epoch).isEqualTo(3)
        assertThat(Base64Url.encode(store.space()!!.k)).isEqualTo(Base64Url.encode(k3))
        assertThat(linked().devices.map { it.trust }.toSet()).containsExactly(DeviceTrust.VERIFIED)
    }

    @Test fun `a forged rotation (no valid proof) is refused and the key is kept`() = runTest {
        link("Chrome on Windows")
        val sp = store.space()!!
        val me = store.identity()!!
        val space = server.spaces.getValue(sp.spaceId)
        // The server mints a "rotation" without K: its proof is under a key it made up.
        space.envelopes[me.deviceId to 2] = SyncKeys.sealKeyFor(SyncCrypto.randomBytes(32), sp.spaceId, 2, me.deviceId, me.devicePub, listOf(me.deviceId), SyncCrypto.randomBytes(32))
        space.epoch = 2
        assertThat(repo.refresh()).isEqualTo(WebLinkResult.Failed(WebLinkCopy.CANT_READ))
        assertThat(store.space()!!.epoch).isEqualTo(1)
        assertThat(Base64Url.encode(store.space()!!.k)).isEqualTo(Base64Url.encode(sp.k))
    }

    @Test fun `401 revoked deletes the local link once, keeps nothing, and says so`() = runTest {
        link("Chrome on Windows")
        val sp = store.space()!!
        server.spaces.getValue(sp.spaceId).devices.remove(store.identity()!!.deviceId)

        val r = repo.refresh()
        assertThat(r).isEqualTo(WebLinkResult.Unlinked(WebLinkCopy.unlinkedFrom("Chrome on Windows")))
        assertThat(store.space()).isNull()
        assertThat(store.identity()).isNull()
        assertThat(repo.status.value).isEqualTo(WebLinkStatus.NotLinked)
        assertThat(repo.notice.value).isEqualTo("Unlinked from Chrome on Windows.")
    }

    @Test fun `left out of a rotation (no key envelope) counts as removed`() = runTest {
        link("Chrome on Windows")
        server.spaces.getValue(store.space()!!.spaceId).epoch = 2 // rotated without an envelope for this phone
        assertThat(repo.refresh()).isInstanceOf(WebLinkResult.Unlinked::class.java)
        assertThat(store.space()).isNull()
    }

    @Test fun `renaming this phone rewrites its label, renaming a browser is a nickname kept here`() = runTest {
        val chrome = link("Chrome on Windows")
        val me = store.identity()!!
        val sp = store.space()!!

        assertThat(repo.rename(me.deviceId, "  Rawn's Pixel  ")).isEqualTo(WebLinkResult.Ok)
        val env = server.spaces.getValue(sp.spaceId).devices.getValue(me.deviceId).labelCt!!
        val label = SyncKeys.readLabel(SyncCrypto.open(SyncCrypto.dataKey(sp.k, sp.spaceId), sp.spaceId, SyncCrypto.Place.label(me.deviceId), env), me.deviceId)
        assertThat(label.name).isEqualTo("Rawn's Pixel")
        assertThat(store.identity()!!.name).isEqualTo("Rawn's Pixel")

        assertThat(repo.rename(chrome.deviceId, "Work PC")).isEqualTo(WebLinkResult.Ok)
        assertThat(linked().devices.map { it.name }).containsExactly("Rawn's Pixel", "Work PC").inOrder()
        assertThat(server.calls.count { it == "label-put" }).isEqualTo(1) // only this phone's own label is written
        assertThat(repo.rename(me.deviceId, "   ")).isEqualTo(WebLinkResult.Failed("Type a name."))
    }

    @Test fun `a rename while a rotation is due rotates first, then writes under the new key`() = runTest {
        link("Chrome on Windows")
        val gone = link("Firefox on Mac")
        val sp = store.space()!!
        server.spaces.getValue(sp.spaceId).apply {
            devices.remove(gone.deviceId)
            rotationDue = true // Chrome removed Firefox and hasn't rotated yet
        }
        val me = store.identity()!!
        assertThat(repo.rename(me.deviceId, "Pixel")).isEqualTo(WebLinkResult.Ok)
        assertThat(store.space()!!.epoch).isEqualTo(2)
        assertThat(server.spaces.getValue(sp.spaceId).devices.getValue(me.deviceId).labelCt!!.e).isEqualTo(2)
    }

    @Test fun `unlink everything deletes the space and the local state`() = runTest {
        link("Chrome on Windows")
        val sp = store.space()!!
        assertThat(repo.unlinkEverything()).isEqualTo(WebLinkResult.Ok)
        assertThat(server.spaces).doesNotContainKey(sp.spaceId)
        assertThat(store.space()).isNull()
        assertThat(repo.status.value).isEqualTo(WebLinkStatus.NotLinked)
    }

    @Test fun `offline - the last list stays, with the problem, and nothing is deleted`() = runTest {
        link("Chrome on Windows")
        repo.refresh()
        server.failNext("space", SyncResult.Error(503, SyncErrorCode.UNAVAILABLE))
        assertThat(repo.refresh()).isEqualTo(WebLinkResult.Failed(WebLinkCopy.OFFLINE))
        assertThat(linked().problem).isEqualTo(WebLinkCopy.OFFLINE)
        assertThat(linked().devices).hasSize(2)
        assertThat(store.space()).isNotNull()
    }
}
