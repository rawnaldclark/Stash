package com.stash.data.lyrics.source

import android.util.Log
import com.stash.data.lyrics.parser.TtmlParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Word-synced TTML from a static store keyed by ISRC (lrc.red, Binimum).
 *
 * [onDemandOnly]: asked only for a track being played or just downloaded, never by a library-wide
 * pass. First in the chain (see `LyricsModule`); its failures count as misses.
 *
 * The ISRC is the track's own when it has one; a clean 404 on it is a miss, with no search
 * (searching would only find another version of the song). Without one, [IsrcResolver] looks it
 * up by title/artist.
 *
 * Only word-timed TTML is accepted (`lrc:timing` / `itunes:timing` = "Word" on the root): a
 * line-timed file parses fine but would replace LRCLIB's line sync with fake per-word timing.
 * 404 = not there; any other non-2xx = a failure; a 2xx that isn't TTML = a miss (and an HTML
 * page, i.e. a Cloudflare challenge, also opens the shared [IsrcLyricsHostBreaker]).
 */
class IsrcTtmlLyricsSource(
    client: OkHttpClient,
    appVersionName: String,
    override val id: String,
    override val displayName: String,
    /** Contains the literal `{ISRC}`. */
    private val urlTemplate: String,
    private val isrcResolver: IsrcResolver,
    private val breaker: IsrcLyricsHostBreaker,
    /** [LyricsDns] in the app; tests pass [Dns.SYSTEM] so they never reach Cloudflare. */
    lyricsDns: Dns = LyricsDns(),
) : LyricsSource {

    override val onDemandOnly: Boolean = true

    private val userAgent = "Stash/$appVersionName (Android)"
    private val interactiveHttp = client.newBuilder().dns(lyricsDns)
        .callTimeout(INTERACTIVE_TIMEOUT_S, TimeUnit.SECONDS).build()
    private val backgroundHttp = client.newBuilder().dns(lyricsDns)
        .callTimeout(BACKGROUND_TIMEOUT_S, TimeUnit.SECONDS).build()

    override suspend fun resolve(query: LyricsQuery): LyricsResult? {
        val isrc = cleanIsrc(query.isrc)
            ?: cleanIsrc(isrcResolver.resolve(query))
            ?: return null
        return fetch(isrc, query.interactive)
    }

    private suspend fun fetch(isrc: String, interactive: Boolean): LyricsResult? = withContext(Dispatchers.IO) {
        val url = urlTemplate.replace("{ISRC}", isrc).toHttpUrl()
        val http = if (interactive) interactiveHttp else backgroundHttp
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()

        val body = breaker.call {
            http.newCall(request).execute().use { r ->
                when {
                    r.code == 404 -> null
                    !r.isSuccessful -> throw AppleTtmlLyricsSource.HttpStatusException(r.code, url.host)
                    else -> r.body?.string()
                }
            }
        }?.trim()?.removePrefix(BOM_CHAR) ?: return@withContext null

        if (!body.startsWith("<tt")) {
            if (looksLikeHtml(body)) breaker.onBlocked("$id returned an HTML page")
            Log.d(TAG, "$id: $isrc is not TTML (${body.length} chars)")
            return@withContext null
        }
        if (!isWordTimed(body)) {
            Log.d(TAG, "$id: $isrc is not word-timed, keeping the current lyrics")
            return@withContext null
        }
        val parsed = TtmlParser.parse(body)?.takeIf { it.lines.isNotEmpty() } ?: return@withContext null

        Log.d(TAG, "$id hit for $isrc -> ${parsed.lines.size} lines")
        LyricsResult(
            sourceId = id,
            plainText = parsed.toPlainText(),
            syncedLrc = parsed.toLrc(),
            instrumental = false,
            language = null,
            sourceLyricsId = isrc,
            ttml = body,
        )
    }

    companion object {
        private const val TAG = "IsrcTtmlLyricsSource"
        private const val BOM_CHAR = "﻿"
        private const val INTERACTIVE_TIMEOUT_S = 3L
        private const val BACKGROUND_TIMEOUT_S = 10L
        const val LRC_RED_ID = "lrc-red"
        const val BINIMUM_ID = "binimum"
        const val LRC_RED_URL = "https://lrc.red/s/{ISRC}.ttml"
        const val BINIMUM_URL = "https://lyrics-storage.binimum.org/{ISRC}.ttml"

        /** The root `<tt>` tag's timing attribute, in either store's namespace. */
        private val TIMING_ATTR = Regex("""^<tt\b[^>]*?\b(?:lrc|itunes):timing\s*=\s*["']([^"']*)["']""")

        internal fun isWordTimed(ttml: String): Boolean =
            TIMING_ATTR.find(ttml)?.groupValues?.get(1).equals("word", ignoreCase = true)

        private fun looksLikeHtml(body: String): Boolean {
            val head = body.take(64).lowercase(Locale.ROOT)
            return head.startsWith("<!doctype html") || head.startsWith("<html")
        }

        internal fun cleanIsrc(raw: String?): String? = raw
            ?.uppercase(Locale.ROOT)
            ?.filter { it.isLetterOrDigit() }
            ?.takeIf { it.length == 12 }
    }
}
