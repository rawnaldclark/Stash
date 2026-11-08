package com.stash.core.data.diagnostics

import android.content.Context
import android.net.Uri
import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthState
import com.stash.core.auth.spotify.SpotifyAccount
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.SourceAccountDao
import com.stash.core.data.db.dao.SpotifyAccountDao
import com.stash.core.data.sync.MAX_EXTRA_SPOTIFY_ACCOUNTS
import com.stash.core.data.sync.spotifyExtraAccountLabel
import com.stash.core.data.db.dao.SyncHistoryDao
import com.stash.core.data.db.dao.TrackBlocklistDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.SourceAccountEntity
import com.stash.core.model.MusicSource
import com.stash.core.model.SyncStepResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Assembles a single, self-contained diagnostics bundle for the user to share
 * when reporting a problem. Pulls a snapshot from every relevant subsystem
 * (auth, sync history, downloads, blocklist, crash reports, logs, plus every
 * [DiagnosticsContributor] bound by another module), assembles them into a
 * plain-text report, then runs the WHOLE thing through [DiagnosticsRedactor]
 * as a final secret-scrubbing pass.
 *
 * What the user shares is a ZIP: `report.txt` (the same text the preview shows,
 * with a short log tail inline) plus the full rolling log capture and the
 * recent crash files, each redacted on the way in. A text-only bundle capped
 * at 1,500 log lines covered a couple of minutes under load — one noisy tag
 * with stack traces ate most of it, and a sync that ran ten minutes before the
 * report was already gone.
 *
 * PRIVACY: auth is reported as connected/not-connected booleans plus
 * connection timestamps — never tokens, emails, display names, or avatar URLs.
 * The redactor is a backstop for secrets that leak through error strings and
 * log lines; the assembler itself is responsible for not emitting PII in the
 * first place.
 *
 * RESILIENCE: every section is wrapped in its own [runCatching] so that a
 * single failing data source (e.g. a locked DB) degrades to an inline
 * "[<section> unavailable: …]" note instead of sinking the entire bundle.
 */
@Singleton
class DiagnosticsBundleBuilder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncHistoryDao: SyncHistoryDao,
    private val downloadQueueDao: DownloadQueueDao,
    private val trackBlocklistDao: TrackBlocklistDao,
    private val sourceAccountDao: SourceAccountDao,
    /** The extra Spotify accounts: one connection line each, by label only (see [extraSpotifyLines]). */
    private val spotifyAccountDao: SpotifyAccountDao,
    private val playlistDao: PlaylistDao,
    private val trackDao: TrackDao,
    private val tokenManager: TokenManager,
    private val crashFileStore: CrashFileStore,
    private val logcatCapture: LogcatCapture,
    private val contributors: Set<@JvmSuppressWildcards DiagnosticsContributor>,
) {

    /** A built bundle: the redacted report text, the on-disk zip, a shareable URI and its MIME type. */
    data class DiagnosticsBundle(
        val text: String,
        val file: File,
        val contentUri: Uri,
        val mimeType: String,
    )

    /**
     * Assemble the full report and return it redacted. Each section is
     * independently fault-isolated; the final pass redacts the assembled text.
     */
    internal suspend fun buildText(): String {
        val sections = buildList {
            add(section("Header") { crashFileStore.deviceMetadataBlock() })
            add(section("Connection") { connectionSection() })
            add(section("Library") { librarySection() })
            add(section("Recent sync history") { syncHistorySection() })
            add(section("Downloads") { downloadsSection() })
            add(section("Counts") { countsSection() })
            // Sections owned by modules this one cannot see. Sorted by title so
            // the report reads in the same order on every device.
            contributors.sortedBy { it.title }.forEach { c ->
                add(section(c.title) { "== ${c.title} ==\n" + c.section() })
            }
            add(section("Recent crash reports") { crashReportsSection() })
            add(section("Process exit reasons") { exitReasonsSection() })
            add(
                section("Recent logs") {
                    "== Recent logs (last $INLINE_LOG_LINES lines; the shared file carries the full capture) ==\n" +
                        logcatCapture.recentLogs(INLINE_LOG_LINES)
                },
            )
        }
        val assembled = sections.joinToString("\n\n")
        return DiagnosticsRedactor.redact(assembled)
    }

    private inline fun section(name: String, block: () -> String): String =
        runCatching { block() }.getOrElse { "[$name unavailable: ${it.message}]" }

    // ── Section: Process exit reasons ───────────────────────────────────────
    //
    // Java-uncaught crashes land in CrashFileStore, but ANRs, native crashes,
    // and low-memory (LMK/OOM) kills leave NO crash file — the only record is
    // the OS exit-reason ring buffer. Surfacing it is what lets us triage the
    // OOM reports (#238/#239) that show "Recent crash reports: none". API 30+.

    private fun exitReasonsSection(): String {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            return "== Process exit reasons ==\nunavailable (needs Android 11+)"
        }
        val am = context.getSystemService(android.app.ActivityManager::class.java)
            ?: return "== Process exit reasons ==\nunavailable"
        val reasons = am.getHistoricalProcessExitReasons(context.packageName, 0, 8)
        return buildString {
            appendLine("== Process exit reasons ==")
            if (reasons.isEmpty()) {
                append("none")
                return@buildString
            }
            reasons.forEach { info ->
                // pss/rss are reported in KB. No PII — this is our own process.
                appendLine(
                    "- ts=${info.timestamp} | ${exitReasonName(info.reason)} | " +
                        "importance=${info.importance} | pss=${info.pss / 1024}MB rss=${info.rss / 1024}MB" +
                        (info.description?.takeIf { it.isNotBlank() }?.let { " | $it" } ?: ""),
                )
            }
        }
    }

    private fun exitReasonName(reason: Int): String = when (reason) {
        android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        android.app.ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        android.app.ApplicationExitInfo.REASON_CRASH -> "CRASH(java)"
        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        android.app.ApplicationExitInfo.REASON_ANR -> "ANR"
        android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT_FAILURE"
        android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE"
        android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        android.app.ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        android.app.ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "reason=$reason"
    }

    // ── Section 2: Connection ───────────────────────────────────────────────

    private suspend fun connectionSection(): String {
        val accounts = sourceAccountDao.getAll().first()
        return buildString {
            appendLine("== Connection ==")
            appendLine(connectionLine("Spotify", tokenManager.spotifyAuthState.value, accounts, MusicSource.SPOTIFY))
            extraSpotifyLines().forEach { appendLine(it) }
            append(connectionLine("YouTube", tokenManager.youTubeAuthState.value, accounts, MusicSource.YOUTUBE))
        }
    }

    /**
     * One line per extra Spotify account (plan 2026-11-04 §3.7), in the order a sync reads them
     * (row id): whether its sign-in is on this phone and working, and which kinds it syncs.
     * "Spotify (extra 3): Connected · playlists=on liked=on albums=on mixes=off".
     *
     * PRIVACY: named by its label ([spotifyExtraAccountLabel], the local row id) only. Never the
     * name the listener gave it, never its Spotify user id (read only to ask whether its sign-in
     * is the row's account), never anything about its sign-in but yes or no.
     */
    private suspend fun extraSpotifyLines(): List<String> =
        spotifyAccountDao.getAll().sortedBy { it.id }.mapIndexed { index, row ->
            val signedIn = runCatching {
                tokenManager.hasSpotifyExtraSignIn(SpotifyAccount(row.id, row.userId))
            }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it else false }
            val state = when {
                !signedIn -> "No sign-in on this phone"
                row.signedOutAt != null -> "Signed out (signed_out_at=${row.signedOutAt})"
                else -> "Connected"
            }
            val kinds = "playlists=${row.syncPlaylists.onOff()} liked=${row.syncLiked.onOff()} " +
                "albums=${row.syncAlbums.onOff()} mixes=${row.syncMixes.onOff()}"
            val limit = if (index >= MAX_EXTRA_SPOTIFY_ACCOUNTS) " · past the limit, not read" else ""
            "Spotify (${spotifyExtraAccountLabel(row.id)}): $state · $kinds$limit"
        }

    private fun Boolean.onOff(): String = if (this) "on" else "off"

    private fun connectionLine(
        label: String,
        authState: AuthState,
        accounts: List<SourceAccountEntity>,
        source: MusicSource,
    ): String {
        val connected = authState is AuthState.Connected
        // PRIVACY: from the row read ONLY source/connectedAt/lastSyncAt; never
        // email/displayName/avatarUrl. From AuthState.Connected never the user.
        val row = accounts.firstOrNull { it.source == source }
        return buildString {
            append("$label: ${if (connected) "Connected" else "Not connected"}")
            if (row != null) {
                append(" (connected_at=${row.connectedAt}, last_sync_at=${row.lastSyncAt})")
            }
        }
    }

    // ── Section 3: Recent sync history ──────────────────────────────────────

    private suspend fun syncHistorySection(): String {
        val rows = syncHistoryDao.getRecentSyncs(10).first()
        return buildString {
            appendLine("== Recent sync history ==")
            if (rows.isEmpty()) {
                append("none")
                return@buildString
            }
            rows.forEachIndexed { index, row ->
                appendLine(
                    "- ${row.startedAt} | ${row.status} | ${row.trigger} | " +
                        "checked=${row.playlistsChecked} found=${row.newTracksFound} " +
                        "downloaded=${row.tracksDownloaded} failed=${row.tracksFailed}",
                )
                row.errorMessage?.let { appendLine("    error: $it") }
                val diagnostics = row.diagnostics
                if (!diagnostics.isNullOrBlank()) {
                    val steps = runCatching {
                        Json.decodeFromString<List<SyncStepResult>>(diagnostics)
                    }.getOrElse {
                        appendLine("    [steps unavailable: ${it.message}]")
                        emptyList()
                    }
                    steps.forEach { step ->
                        // PRIVACY: an extra account's step names it by its label ("extra N") only.
                        val service = step.account?.let { "${step.service} ($it)" } ?: step.service
                        append(
                            "    $service · ${step.step} · ${step.status} · " +
                                "http=${step.httpCode} · items=${step.itemCount}",
                        )
                        step.errorMessage?.let { append(" · $it") }
                        step.note?.let { append(" · $it") }
                        appendLine()
                    }
                }
                // Trim trailing newline only on the very last row.
                if (index == rows.lastIndex) deleteCharAt(length - 1)
            }
        }
    }

    // ── Section 4: Downloads ────────────────────────────────────────────────

    private suspend fun downloadsSection(): String {
        val counts = downloadQueueDao.getStatusCounts()
        val failed = downloadQueueDao.getFailedDownloads().first().take(20)
        val unmatched = downloadQueueDao.getUnmatchedCount().first()
        return buildString {
            appendLine("== Downloads ==")
            if (counts.isEmpty()) {
                appendLine("Status counts: none")
            } else {
                counts.forEach { appendLine("${it.status}: ${it.count}") }
            }
            appendLine("Unmatched: $unmatched")
            if (failed.isEmpty()) {
                append("Failed: none")
            } else {
                appendLine("Failed:")
                failed.forEachIndexed { index, f ->
                    append(
                        "- ${f.artist} - ${f.title} [${f.failureType}] " +
                            "retries=${f.retryCount} ${f.errorMessage.orEmpty()}",
                    )
                    if (index != failed.lastIndex) appendLine()
                }
            }
        }
    }

    // ── Library: what sync has built, as counts (never playlist names) ─────

    private suspend fun librarySection(): String {
        val t = trackDao.diagnosticsTotals()
        val rows = playlistDao.diagnosticsCounts()
        return buildString {
            appendLine("== Library ==")
            appendLine("Tracks: ${t.total} · downloaded ${t.downloaded} · file missing ${t.missingFiles}")
            appendLine("Active playlists by source/type (total · switched on · never synced · no tracks linked):")
            if (rows.isEmpty()) appendLine("  none")
            rows.forEach {
                appendLine("  ${it.source}/${it.type}: ${it.total} · on ${it.switchedOn} · never synced ${it.neverSynced} · empty ${it.empty}")
            }
            append("Inactive playlists: ${playlistDao.inactiveCount()}")
        }
    }

    // ── Section 5: Counts ───────────────────────────────────────────────────

    private suspend fun countsSection(): String {
        val blocklist = trackBlocklistDao.observeCount().first()
        return buildString {
            appendLine("== Counts ==")
            append("Blocklist: $blocklist")
        }
    }

    // ── Section 6: Recent crash reports ─────────────────────────────────────

    private fun crashReportsSection(): String {
        val files = crashFileStore.allCrashFiles().take(2)
        return buildString {
            appendLine("== Recent crash reports ==")
            if (files.isEmpty()) {
                append("none")
                return@buildString
            }
            files.forEachIndexed { index, file ->
                appendLine("--- ${file.name} ---")
                append(file.readText())
                if (index != files.lastIndex) appendLine()
            }
        }
    }

    // ── Public entry point ──────────────────────────────────────────────────

    /**
     * Build the bundle and persist it as a single rotating zip under
     * `cacheDir/diagnostics` (older `stash-diagnostics-*` files, zip or the
     * pre-zip `.txt`, are deleted first so only the newest remains):
     *
     *  - `report.txt`        — the redacted report, exactly what the preview shows
     *  - `logs/<file>.txt`   — the whole rolling logcat capture, redacted per file
     *  - `crashes/<file>.txt`— the newest [MAX_CRASH_FILES] crash reports, redacted
     *
     * A file that cannot be read is skipped, not fatal: the report is the part
     * that must always ship. Returns a shareable [DiagnosticsBundle].
     */
    suspend fun build(): DiagnosticsBundle {
        val text = buildText()
        val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        dir.listFiles { f -> f.isFile && f.name.startsWith("stash-diagnostics-") }
            ?.forEach { runCatching { it.delete() } }
        val zip = File(dir, "stash-diagnostics-${System.currentTimeMillis()}.zip")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zip))).use { out ->
            out.entry("report.txt", text)
            logcatCapture.logFiles().forEach { f ->
                runCatching { out.entry("logs/${f.name}", DiagnosticsRedactor.redact(f.readText())) }
            }
            crashFileStore.allCrashFiles().take(MAX_CRASH_FILES).forEach { f ->
                runCatching { out.entry("crashes/${f.name}", DiagnosticsRedactor.redact(f.readText())) }
            }
        }
        val uri = crashFileStore.shareUriFor(zip)
        return DiagnosticsBundle(text = text, file = zip, contentUri = uri, mimeType = ZIP_MIME)
    }

    private fun ZipOutputStream.entry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray())
        closeEntry()
    }

    companion object {
        /** Log lines kept inline in `report.txt`; the zip carries the full capture. */
        internal const val INLINE_LOG_LINES = 400
        private const val MAX_CRASH_FILES = 5
        const val ZIP_MIME = "application/zip"
    }
}
