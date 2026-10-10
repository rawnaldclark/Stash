package com.stash.core.data.weblink

import com.stash.core.data.diagnostics.DiagnosticsContributor
import com.stash.core.data.weblink.handoff.HandoffPrefs
import com.stash.core.data.weblink.mirror.Dir
import com.stash.core.data.weblink.mirror.Kind
import com.stash.core.data.weblink.mirror.MirrorEngine
import com.stash.core.data.weblink.mirror.MirrorWire
import com.stash.core.data.weblink.store.WebLinkStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The diagnostics bundle's "Link Stash on the web" section (spec §9, §11): whether this phone is linked, how many devices and of
 * which kind, the key's epoch, handoff on or off, the mirror settings, how far it has read, its last run and when it last sent.
 * PRIVACY: never a device, space or mirror id, a key, a token, a label or a device name, and nothing from the library.
 */
@Singleton
class SyncDiagnosticsContributor @Inject constructor(
    private val config: WebLinkConfig,
    private val store: WebLinkStore,
    private val engine: MirrorEngine,
    private val handoff: HandoffPrefs,
) : DiagnosticsContributor {
    override val title: String = "Link Stash on the web"

    /** Test seam for the clock. */
    internal var nowMs: () -> Long = System::currentTimeMillis

    override suspend fun section(): String = buildString {
        appendLine("Feature: ${if (config.enabled) "on" else "off"}")
        val space = runCatching { store.space() }.getOrNull()
        if (space == null) {
            appendLine("Linked: no")
            return@buildString
        }
        val roster = runCatching { store.roster() }.getOrDefault(emptyList())
        appendLine("Linked: yes · this phone + ${roster.count { it.type == "web" }} browser(s) · key epoch ${space.epoch}")
        appendLine("Pick up where you left off: ${if (handoff.enabled.value) "on" else "off"}")
        val r = engine.record()
        val cfg = r?.configJson?.let { runCatching { MirrorWire.readConfig(it) }.getOrNull() }
        if (r == null || cfg == null || !cfg.anyOn) {
            appendLine("Mirroring: off")
        } else {
            val kinds = Kind.entries.joinToString(", ") { k ->
                val d = cfg.of(k).dir
                val joined = if (d != Dir.OFF && r.joined[k] == cfg.of(k).since) "" else if (d != Dir.OFF) " (not joined yet)" else ""
                "${k.wire} ${d.wire}$joined"
            }
            appendLine("Mirroring: $kinds")
            appendLine("Playlists chosen: ${cfg.ids.size}; new ones too: ${if (cfg.newOnes) "yes" else "no"}")
            if (r.waiting) appendLine("Likes: waiting for a browser to share its own")
            if (r.question != null) appendLine("Likes: the first-merge question waits for an answer")
        }
        if (r != null) {
            appendLine("Log read up to: ${r.seen} (snapshot at ${r.snapUpto}); unreadable entries: ${r.gaps.size}")
            appendLine("In the space: ${r.view.likes.count { it.on }} likes, ${r.view.plays.size} plays, ${r.view.playlists.size} playlists")
            appendLine("Last run: ${ago(r.lastRunAt)} (${r.lastResult ?: "never"}); last sent: ${ago(r.lastPushAt)}")
        }
    }

    private fun ago(t: Long): String {
        if (t <= 0) return "never"
        val m = (nowMs() - t).coerceAtLeast(0) / 60_000
        return when {
            m < 1 -> "just now"
            m < 60 -> "$m min ago"
            m < 48 * 60 -> "${m / 60} h ago"
            else -> "${m / 1440} days ago"
        }
    }
}
