package com.stash.core.data.weblink

import com.google.common.truth.Truth.assertThat
import java.security.interfaces.ECPublicKey
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The phone's pairing state machine (spec §5.1, sync-v1 §3.4) against an in-memory Worker and a scripted browser using the
 * same crypto: the three endings, R7 (nothing joined, created or pinned before the browser's reply opens under the phone's
 * own Kpair), and the server's refusals.
 */
class PairingSessionTest {
    private val server = FakeSyncServer()
    private val store = InMemoryWebLinkStore()
    private val repo = WebLinkRepository(server, store)
    private var clock = 0L
    private val session = PairingSession(server, store, repo, phoneName = { "Pixel 5" }, now = { clock }, sleep = { clock += it })

    /** The browser's user confirms as soon as the answer arrives (no space of its own). */
    private fun FakeBrowser.confirmsOnAnswer() {
        server.onAnswered = { slot ->
            readAnswer(slot)
            reply()
        }
    }

    @Test fun `a link that isn't a Stash code stops before any call`() = runTest {
        for (bad in listOf("hello", "https://stashfm.app/link#2.x.y.z", "https://example.com/link#1.a.b.c")) {
            assertThat(session.open(bad)).isEqualTo(PairingState.Failed(WebLinkCopy.NOT_A_CODE))
        }
        assertThat(server.calls).isEmpty()
    }

    @Test fun `new space - the phone shows the browser's code and creates the space only after the reply`() = runTest {
        val browser = FakeBrowser(server)
        val link = browser.openCode()
        browser.confirmsOnAnswer()
        server.pendingPolls = 2

        val confirm = session.open(link) as PairingState.Confirm
        assertThat(confirm.browserName).isEqualTo("Chrome on Windows")
        assertThat(store.space()).isNull()

        val done = session.confirm()
        assertThat(done).isEqualTo(PairingState.Linked("Chrome on Windows"))
        // The code the phone showed is the one the browser computed from the answer.
        assertThat(browser.code).isEqualTo(confirm.code)
        // Answer, then the long-poll (two pending), then the create: never a create before the reply.
        assertThat(server.calls.take(6)).containsExactly("label", "answer", "reply", "reply", "reply", "create").inOrder()

        val sp = store.space()!!
        assertThat(sp.epoch).isEqualTo(1)
        assertThat(sp.spaceId).matches("^s_[A-Za-z0-9_-]{22}$")
        // The browser learned the same space and key from the answer.
        assertThat(browser.answer!!.space!!.id).isEqualTo(sp.spaceId)
        assertThat(Base64Url.encode(browser.answer!!.space!!.k)).isEqualTo(Base64Url.encode(sp.k))
        assertThat(browser.answer!!.label.name).isEqualTo("Pixel 5")
        // Both labels went in under the data key, and the browser's opens as its own.
        assertThat(browser.openOwnLabel(sp.spaceId, sp.k).pub).isEqualTo(browser.pub)
        // The browser's key is pinned from its code-checked pairing label.
        val pinned = store.roster().single()
        assertThat(pinned.deviceId).isEqualTo(browser.deviceId)
        assertThat(pinned.pub).isEqualTo(browser.pub)
        assertThat((repo.status.value as WebLinkStatus.Linked).devices.map { it.name }).containsExactly("Pixel 5", "Chrome on Windows").inOrder()
    }

    @Test fun `R7 - a browser label swapped in the slot makes the reply fail to open, and nothing is joined or pinned`() = runTest {
        val browser = FakeBrowser(server)
        val link = browser.openCode()
        // Someone who photographed the QR (with the server's help) puts their own label and key in the slot.
        val attacker = FakeBrowser(server, name = "Chrome on Windows")
        val attackerLabel = SyncCrypto.seal(
            SyncKeys.pairLabelKey(browser.pairSecret), "pair", 0, SyncCrypto.Place.label(attacker.deviceId), SyncKeys.labelJson(attacker.label()),
        )
        server.slots.getValue(browser.pairId).browser =
            DeviceRecord(attacker.deviceId, SyncCrypto.tokenHash(attacker.token), Base64Url.encode(attacker.pub), attackerLabel)
        // The real browser's Kpair binds its own label, so it can't even open the answer; whatever it (or anyone without the
        // phone's Kpair) posts as a reply can't open on the phone.
        server.onAnswered = { slot ->
            browser.deriveKpair(slot)
            browser.reply()
        }

        val confirm = session.open(link) as PairingState.Confirm
        val done = session.confirm()

        assertThat(done).isEqualTo(PairingState.Failed(WebLinkCopy.UNFINISHED, confirm.code))
        // The codes differ, so the user would have cancelled on the browser anyway.
        assertThat(browser.code).isNotEqualTo(confirm.code)
        assertThat(server.calls).doesNotContain("create")
        assertThat(server.calls).doesNotContain("join")
        assertThat(store.space()).isNull()
        assertThat(store.roster()).isEmpty()
    }

    @Test fun `a code someone else answered first - already used, and the code stays on screen`() = runTest {
        val browser = FakeBrowser(server)
        val link = browser.openCode()
        val confirm = session.open(link) as PairingState.Confirm
        server.failNext("answer", SyncResult.Error(409, SyncErrorCode.USED))

        assertThat(session.confirm()).isEqualTo(PairingState.Failed(WebLinkCopy.USED, confirm.code))
        assertThat(store.space()).isNull()
    }

    @Test fun `expired and used codes are refused at the label`() = runTest {
        assertThat(session.open(FakeBrowser(server).openCode().also { server.slots.clear() }))
            .isEqualTo(PairingState.Failed(WebLinkCopy.EXPIRED))
        val b = FakeBrowser(server)
        val link = b.openCode()
        server.failNext("label", SyncResult.Error(409, SyncErrorCode.USED))
        assertThat(session.open(link)).isEqualTo(PairingState.Failed(WebLinkCopy.USED))
        server.failNext("label", SyncResult.Error(429, SyncErrorCode.RATE_LIMITED))
        assertThat(session.open(link)).isEqualTo(PairingState.Failed(WebLinkCopy.SLOW_DOWN))
    }

    @Test fun `a label whose key isn't the one the server lists is not a Stash code`() = runTest {
        val browser = FakeBrowser(server)
        val link = browser.openCode()
        val slot = server.slots.getValue(browser.pairId)
        slot.browser = slot.browser.copy(pub = Base64Url.encode(SyncCrypto.rawPublicKey(SyncCrypto.newKeyPair().public as ECPublicKey)))
        assertThat(session.open(link)).isEqualTo(PairingState.Failed(WebLinkCopy.NOT_A_CODE))
    }

    @Test fun `the server refusing the create until the reply lands - 409 no_reply is retried`() = runTest {
        val browser = FakeBrowser(server)
        val link = browser.openCode()
        browser.confirmsOnAnswer()
        server.failNext("create", SyncResult.Error(409, SyncErrorCode.NO_REPLY))
        session.open(link)
        assertThat(session.confirm()).isEqualTo(PairingState.Linked("Chrome on Windows"))
        assertThat(server.calls.count { it == "create" }).isEqualTo(2)
    }

    @Test fun `no reply before the code dies - linking didn't finish, nothing kept`() = runTest {
        val browser = FakeBrowser(server)
        val link = browser.openCode()
        server.onAnswered = { browser.readAnswer(it) } // the browser's user never confirms (Cancel)
        val confirm = session.open(link) as PairingState.Confirm
        // The fake long-poll answers "pending" at once; the phone paces itself, and gives up past the code's life.
        assertThat(session.confirm()).isEqualTo(PairingState.Failed(WebLinkCopy.UNFINISHED, confirm.code))
        assertThat(clock).isAtLeast(PairingSession.REPLY_WAIT_MS)
        assertThat(server.calls.count { it == "reply" }).isAtMost((PairingSession.REPLY_WAIT_MS / 1_000).toInt() + 1)
        assertThat(server.calls).doesNotContain("create")
        assertThat(store.space()).isNull()
    }

    @Test fun `the browser grants its phone-less space - the phone joins it without creating one`() = runTest {
        val browser = FakeBrowser(server)
        // The browser is in a space whose phone was removed.
        val grant = SyncKeys.SpaceGrant(WebLinkIds.newSpaceId(), SyncCrypto.randomBytes(32), 3)
        server.spaces[grant.id] = FakeSyncServer.Space(grant.id).apply {
            epoch = 3
            devices[browser.deviceId] = FakeSyncServer.Dev(browser.deviceId, "web", SyncCrypto.tokenHash(browser.token), Base64Url.encode(browser.pub), null)
        }
        val link = browser.openCode()
        server.onAnswered = { slot ->
            browser.readAnswer(slot)
            // Sponsor: adds the phone (its label re-sealed under the data key), then replies with the space.
            val phoneLabel = browser.answer!!.label
            val labelCt = SyncCrypto.seal(
                SyncCrypto.dataKey(grant.k, grant.id), grant.id, 3, SyncCrypto.Place.label(phoneLabel.deviceId), SyncKeys.labelJson(phoneLabel),
            )
            server.browserAddsPhone(grant.id, slot.pairId, labelCt)
            browser.reply(grant)
        }
        session.open(link)
        assertThat(session.confirm()).isEqualTo(PairingState.Linked("Chrome on Windows"))
        assertThat(server.calls).doesNotContain("create")
        val sp = store.space()!!
        assertThat(sp.spaceId).isEqualTo(grant.id)
        assertThat(sp.epoch).isEqualTo(3)
        assertThat(Base64Url.encode(sp.k)).isEqualTo(Base64Url.encode(grant.k))
        assertThat(store.roster().single().deviceId).isEqualTo(browser.deviceId)
    }

    @Test fun `a linked phone adds a second browser to its space, label under the data key`() = runTest {
        linkFirstBrowser()
        val sp = store.space()!!
        val second = FakeBrowser(server, name = "Firefox on Mac")
        val link = second.openCode()
        second.confirmsOnAnswer()

        session.reset()
        session.open(link)
        assertThat(session.confirm()).isEqualTo(PairingState.Linked("Firefox on Mac"))
        // The answer granted the phone's space; the phone joined the browser after the reply.
        assertThat(second.answer!!.space!!.id).isEqualTo(sp.spaceId)
        assertThat(server.calls.last { it == "join" || it == "create" }).isEqualTo("join")
        assertThat(server.spaces.getValue(sp.spaceId).devices).hasSize(3)
        assertThat(second.openOwnLabel(sp.spaceId, sp.k).name).isEqualTo("Firefox on Mac")
        assertThat(store.roster().map { it.deviceId }).contains(second.deviceId)
    }

    @Test fun `a fifth browser is refused before answering`() = runTest {
        linkFirstBrowser()
        val sp = store.space()!!
        repeat(3) {
            val b = FakeBrowser(server)
            server.spaces.getValue(sp.spaceId).devices[b.deviceId] = FakeSyncServer.Dev(b.deviceId, "web", "h", Base64Url.encode(b.pub), null)
        }
        val fifth = FakeBrowser(server)
        assertThat(session.open(fifth.openCode())).isEqualTo(PairingState.Failed(WebLinkCopy.FULL))
        assertThat(server.calls.count { it == "answer" }).isEqualTo(1) // only the first link's
    }

    @Test fun `joining after a rotation elsewhere hands the browser a proven key envelope`() = runTest {
        linkFirstBrowser()
        val sp = store.space()!!
        val second = FakeBrowser(server, name = "Firefox on Mac")
        val link = second.openCode()
        second.confirmsOnAnswer()
        session.reset()
        session.open(link)
        // Between the answer and the join, the space rotates to epoch 2 (another device's rotation): the phone gets its envelope.
        val k2 = SyncCrypto.randomBytes(32)
        val space = server.spaces.getValue(sp.spaceId)
        val me = store.identity()!!
        val members = space.devices.keys.sorted()
        space.envelopes[me.deviceId to 2] = SyncKeys.sealKeyFor(k2, sp.spaceId, 2, me.deviceId, me.devicePub, members, sp.k)
        space.epoch = 2

        assertThat(session.confirm()).isEqualTo(PairingState.Linked("Firefox on Mac"))
        assertThat(store.space()!!.epoch).isEqualTo(2)
        // The newcomer's envelope for epoch 2 opens with its device key, proven under the K it was given (epoch 1).
        assertThat(Base64Url.encode(second.openKey(sp.spaceId, 2, sp.k).k)).isEqualTo(Base64Url.encode(k2))
    }

    private suspend fun linkFirstBrowser(): FakeBrowser {
        val browser = FakeBrowser(server)
        val link = browser.openCode()
        browser.confirmsOnAnswer()
        session.open(link)
        assertThat(session.confirm()).isInstanceOf(PairingState.Linked::class.java)
        return browser
    }
}
