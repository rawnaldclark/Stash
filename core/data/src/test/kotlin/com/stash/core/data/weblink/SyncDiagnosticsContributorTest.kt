package com.stash.core.data.weblink

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.weblink.handoff.HandoffPrefs
import com.stash.core.data.weblink.mirror.Dir
import com.stash.core.data.weblink.mirror.FakeMirrorLibrary
import com.stash.core.data.weblink.mirror.InMemoryMirrorStore
import com.stash.core.data.weblink.mirror.Kind
import com.stash.core.data.weblink.mirror.MirrorChange
import com.stash.core.data.weblink.mirror.MirrorEngine
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The diagnostics bundle's link section (spec §9, §11): what is useful, and never an id, a key, a token or a name. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SyncDiagnosticsContributorTest {
    private val server = FakeSyncServer()
    private val store = InMemoryWebLinkStore()
    private val repo = WebLinkRepository(server, store)
    private var clock = 3_000_000L
    private val records = InMemoryMirrorStore()
    private val engine = MirrorEngine(server, store, repo, records, FakeMirrorLibrary(), { clock }, { "m_Phone00000000001" })
    private val section = SyncDiagnosticsContributor(
        WebLinkConfig("http://127.0.0.1:8795", true), store, engine, HandoffPrefs(ApplicationProvider.getApplicationContext()),
    )

    @Test fun `not linked says so`() = runTest {
        assertThat(section.section()).contains("Linked: no")
    }

    @Test fun `linked and mirroring - settings, position and last run, without ids or names`() = runTest {
        val b = FakeBrowser(server, name = "Chrome on Windows")
        val link = b.openCode()
        server.onAnswered = { slot -> b.readAnswer(slot); b.reply() }
        val session = PairingSession(server, store, repo, phoneName = { "Pixel 5" }, now = { clock }, sleep = { clock += it })
        session.open(link)
        session.confirm()
        repo.refresh()
        engine.configure(MirrorChange(dirs = mapOf(Kind.LIKES to Dir.BOTH)))

        section.nowMs = { clock }
        val text = section.section()
        assertThat(text).contains("Linked: yes · this phone + 1 browser(s) · key epoch 1")
        assertThat(text).contains("Mirroring: likes both")
        assertThat(text).contains("Last run: just now (ok)")
        val sp = store.space()!!
        for (secret in listOf(store.id!!.deviceId, b.deviceId, sp.spaceId, "Chrome on Windows", "Pixel 5", "m_Phone")) {
            assertThat(text).doesNotContain(secret)
        }
    }
}
