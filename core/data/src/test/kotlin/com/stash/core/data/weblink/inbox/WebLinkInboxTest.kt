package com.stash.core.data.weblink.inbox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.weblibrary.ImportSelection
import com.stash.core.data.weblibrary.WebLibraryContent
import com.stash.core.data.weblibrary.WebLibraryImportResult
import com.stash.core.data.weblibrary.WebLibraryImporter
import com.stash.core.data.weblink.FakeBrowser
import com.stash.core.data.weblink.FakeSyncServer
import com.stash.core.data.weblink.InMemoryWebLinkStore
import com.stash.core.data.weblink.PairingSession
import com.stash.core.data.weblink.PairingState
import com.stash.core.data.weblink.SyncCrypto
import com.stash.core.data.weblink.SyncErrorCode
import com.stash.core.data.weblink.SyncResult
import com.stash.core.data.weblink.WebLinkConfig
import com.stash.core.data.weblink.WebLinkCopy
import com.stash.core.data.weblink.WebLinkRepository
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * One-off sends (spec §2.3, sync-v1 §3.3, §5.5): the document cut into sealed parts at `inbox:<to>:<sendId>:<part>/<count>`,
 * reassembled by the browser; too big, too many waiting and a rotation mid-send; receiving, adding, putting off, discarding.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class WebLinkInboxTest {
    private val server = FakeSyncServer()
    private val store = InMemoryWebLinkStore()
    private val repo = WebLinkRepository(server, store)
    private var clock = 0L
    private val session = PairingSession(server, store, repo, phoneName = { "Pixel 5" }, now = { clock }, sleep = { clock += it })
    private val importer = mockk<WebLibraryImporter>()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences("inbox-test", Context.MODE_PRIVATE)

    private fun inbox(partBytes: Int = 1_000) =
        WebLinkInbox(server, store, repo, importer, prefs, WebLinkConfig("http://127.0.0.1:8795", true), { clock }, partBytes)

    private suspend fun link(name: String = "Chrome on Windows"): FakeBrowser {
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

    /** A library document big enough to need several 1,000-byte parts even gzipped. */
    private fun document(likes: Int = 300): String {
        val items = (1..likes).joinToString(",") { """{"item":{"title":"Song $it ${it * 7919 % 10007}","artist":"Artist ${it * 31 % 97}"},"likedAt":${1_700_000_000_000 + it}}""" }
        return """{"kind":"stash-web-library","v":1,"exportedAt":"2026-10-10T00:00:00.000Z","likes":[$items],"playlists":[],"history":[]}"""
    }

    @Test fun `a send goes in sealed parts the browser opens back into the same document`() = runTest {
        val chrome = link()
        val doc = document()
        val inbox = inbox()
        assertThat(inbox.targets().map { it.name }).containsExactly("Chrome on Windows")

        assertThat(inbox.send(chrome.deviceId, doc)).isEqualTo(SendOutcome.Sent)

        val (sendId, send) = server.sends.entries.single()
        assertThat(sendId).matches("x_[A-Za-z0-9_-]{22}")
        assertThat(send.count).isGreaterThan(1)
        // The browser, with the space key from the answer, opens each part at its own place.
        val space = chrome.answer!!.space!!
        val text = SyncCrypto.openParts(
            SyncCrypto.dataKey(space.k, space.id), space.id, { i, n -> "inbox:${chrome.deviceId}:$sendId:$i/$n" },
            (0 until send.count).map { send.parts.getValue(it) },
        )
        assertThat(text).isEqualTo(doc)
    }

    @Test fun `over 16 parts is too big, before anything goes up`() = runTest {
        val chrome = link()
        val out = inbox(partBytes = 100).send(chrome.deviceId, document(likes = 400))
        assertThat(out).isEqualTo(SendOutcome.Failed(WebLinkInbox.TOO_BIG))
        assertThat(server.calls.none { it.startsWith("putInbox") }).isTrue()
    }

    @Test fun `a browser with too many sends waiting, and a full space, say so`() = runTest {
        val chrome = link()
        server.sendsPerDevice = 0
        assertThat(inbox().send(chrome.deviceId, document())).isEqualTo(
            SendOutcome.Failed("Chrome on Windows has too many sends waiting. Try again once it has added them."),
        )
        server.sendsPerDevice = 8
        server.failNext("putInbox", SyncResult.Error(413, SyncErrorCode.SPACE_FULL))
        assertThat(inbox().send(chrome.deviceId, document())).isEqualTo(SendOutcome.Failed(WebLinkInbox.TOO_BIG))
        server.failNext("putInbox", SyncResult.Error(503, "unavailable"))
        assertThat(inbox().send(chrome.deviceId, document())).isEqualTo(SendOutcome.Failed(WebLinkCopy.OFFLINE))
        assertThat(server.sends).isEmpty()
    }

    @Test fun `a rotation due mid-send settles the key and sends again`() = runTest {
        val chrome = link()
        server.failNext("putInbox", SyncResult.Error(409, SyncErrorCode.ROTATION_DUE))
        assertThat(inbox().send(chrome.deviceId, document())).isEqualTo(SendOutcome.Sent)
        val send = server.sends.values.single()
        assertThat(send.parts.size).isEqualTo(send.count)
    }

    /** The browser sends [doc] to this phone, as the web does. */
    private fun browserSends(chrome: FakeBrowser, doc: String, sendId: String = WebLinkInbox.newSendId(), partBytes: Int = 1_000): String {
        val space = chrome.answer!!.space!!
        val phone = store.id!!.deviceId
        val envs = SyncCrypto.sealParts(SyncCrypto.dataKey(space.k, space.id), space.id, space.epoch, { i, n -> SyncCrypto.Place.inbox(phone, sendId, i, n) }, doc, partBytes)
        val send = FakeSyncServer.Send(phone, chrome.deviceId, envs.size, server.clock)
        envs.forEachIndexed { i, e -> send.parts[i] = e }
        server.sends[sendId] = send
        return sendId
    }

    @Test fun `a send that arrived is read as a library file, offered once, added as an import, then deleted`() = runTest {
        val chrome = link()
        val inbox = inbox()
        val doc = """{"kind":"stash-web-library","v":1,"settings":{"quality":"high"},"likes":[{"item":{"title":"A","artist":"B"},"likedAt":5}],
            "playlists":[{"id":"p1","name":"Gym","items":[]},{"id":"p2","name":"Run","items":[]},{"id":"p3","name":"Chill","items":[]}],"history":[]}"""
        val sendId = browserSends(chrome, doc)

        inbox.check()
        val send = inbox.sends.value.single()
        assertThat(send.name).isEqualTo("Chrome on Windows")
        assertThat(send.readable).isTrue()
        assertThat(inbox.notice.value?.sendId).isEqualTo(sendId)
        // Listing it downloads nothing: its parts are fetched only once Add or Choose asks for it (review B1).
        assertThat(server.calls.none { it.startsWith("inboxPart") }).isTrue()
        val opened = inbox.open(sendId) as OpenedSend.Ok
        assertThat(opened.summary).isEqualTo("3 playlists and 1 like")

        val content = slot<WebLibraryContent>()
        val origin = slot<String>()
        coEvery { importer.import(capture(content), any(), capture(origin)) } returns WebLibraryImportResult(likesAdded = 1, playlistsAdded = 3)
        val pick = ImportSelection(likes = true, plays = false, playlistIds = setOf("p1"))
        assertThat(inbox.add(sendId, pick)).isEqualTo(AddOutcome.Added(WebLibraryImportResult(likesAdded = 1, playlistsAdded = 3)))
        assertThat(origin.captured).isEqualTo(chrome.deviceId)
        assertThat(content.captured.playlists.map { it.name }).containsExactly("Gym", "Run", "Chill").inOrder()
        assertThat(server.sends).isEmpty()
        assertThat(inbox.sends.value).isEmpty()
        assertThat(inbox.notice.value).isNull()
    }

    @Test fun `not now keeps it listed without asking again, discard deletes it`() = runTest {
        val chrome = link()
        val inbox = inbox()
        val a = browserSends(chrome, document(likes = 2))
        inbox.check()
        inbox.later(a)
        assertThat(inbox.notice.value).isNull()
        assertThat(inbox.sends.value.map { it.sendId }).containsExactly(a)
        assertThat(server.sends.keys).containsExactly(a)
        inbox.check()
        assertThat(inbox.notice.value).isNull()

        inbox.discard(a)
        assertThat(server.sends).isEmpty()
        assertThat(inbox.sends.value).isEmpty()
    }

    @Test fun `a send from a newer Stash can only be discarded`() = runTest {
        val chrome = link()
        val inbox = inbox()
        val id = browserSends(chrome, """{"kind":"stash-web-library","v":2,"likes":[]}""")
        inbox.check()
        assertThat(inbox.open(id)).isEqualTo(OpenedSend.Failed(WebLinkCopy.UPDATE_APP))
        val send = inbox.sends.value.single()
        assertThat(send.problem).isEqualTo(WebLinkCopy.UPDATE_APP)
        assertThat(inbox.notice.value).isNull()
        // Remembered: a later look (a new launch) shows it as unreadable without downloading it again.
        server.calls.clear()
        inbox().check()
        assertThat(server.calls.none { it.startsWith("inboxPart") }).isTrue()
    }

    @Test fun `a gzip bomb of 64 MiB of empty arrays is refused before it is parsed, and never tried again`() = runTest {
        val chrome = link()
        val inbox = inbox()
        // One small part that inflates to 64 MiB of `[],`: a JSON tree of 22 million arrays if anything parsed it.
        val bomb = buildString(64 * 1024 * 1024 + 64) {
            append("""{"kind":"stash-web-library","v":1,"history":[""")
            while (length < 64 * 1024 * 1024) append("[],")
            append("[]]}")
        }
        val id = browserSends(chrome, bomb, partBytes = SyncCrypto.PART_BYTES)
        inbox.check()
        assertThat(inbox.open(id)).isEqualTo(OpenedSend.Failed(WebLinkInbox.TOO_BIG_TO_OPEN))
        assertThat(inbox.add(id)).isEqualTo(AddOutcome.Failed(WebLinkInbox.TOO_BIG_TO_OPEN))
        assertThat(inbox.notice.value).isNull()
    }

    @Test fun `a shape too big to parse is refused even under the inflate cap`() = runTest {
        val chrome = link()
        val inbox = inbox()
        val dense = buildString { append("""{"kind":"stash-web-library","v":1,"history":["""); repeat(1_100_000) { append("[],") }; append("[]]}") }
        val id = browserSends(chrome, dense)
        inbox.check()
        assertThat(inbox.open(id)).isEqualTo(OpenedSend.Failed(WebLinkInbox.TOO_BIG_TO_OPEN))
    }

    @Test fun `an open that never finished (the app died while reading) is unreadable on the next launch, not tried again`() = runTest {
        val chrome = link()
        val id = browserSends(chrome, document(likes = 2))
        prefs.edit().putString("trying", id).commit()
        val inbox = inbox()
        inbox.check()
        assertThat(inbox.sends.value.single().problem).isEqualTo(WebLinkInbox.CANT_READ)
        assertThat(inbox.notice.value).isNull()
        assertThat(inbox.open(id)).isEqualTo(OpenedSend.Failed(WebLinkInbox.CANT_READ))
        assertThat(server.calls.none { it.startsWith("inboxPart") }).isTrue()
    }

    @Test fun `unlinking clears what was listed at once`() = runTest {
        val chrome = link()
        val inbox = inbox()
        browserSends(chrome, document(likes = 2))
        inbox.check()
        assertThat(inbox.sends.value).hasSize(1)
        repo.unlinkEverything()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(5_000) { while (inbox.sends.value.isNotEmpty() || inbox.notice.value != null) kotlinx.coroutines.delay(10) }
        }
        assertThat(inbox.sends.value).isEmpty()
    }

    @Test fun `a send whose answer was lost is replaced by the retry, not doubled`() = runTest {
        val chrome = link()
        val inbox = inbox(partBytes = 100_000)
        // The only part lands, but its answer never comes back; taking it back fails too (still offline).
        server.landThenLose = true
        assertThat(inbox.send(chrome.deviceId, document(likes = 3))).isEqualTo(SendOutcome.Failed(WebLinkCopy.OFFLINE))
        assertThat(server.sends).hasSize(1)
        val first = server.sends.keys.single()
        assertThat(inbox.send(chrome.deviceId, document(likes = 3))).isEqualTo(SendOutcome.Sent)
        assertThat(server.sends.keys).containsExactly(first)
    }

    @Test fun `a store that throws is nothing to show, never a crash`() = runTest {
        link()
        store.failReads = true
        val inbox = inbox()
        inbox.check()
        assertThat(inbox.sends.value).isEmpty()
        assertThat(inbox.targets()).isEmpty()
        assertThat(inbox.send("d_x", document())).isEqualTo(SendOutcome.Failed(WebLinkCopy.STORE_BUSY))
    }
}
