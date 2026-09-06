/*
 * Ported from YumaPlayer (MuwMix) / ArchiveTune (Rukamori), GPL-3.0.
 * Original source: moe.rukamori.archivetune.kugou.KuGou
 */
package com.stash.data.lyrics.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlin.math.abs

/**
 * KuGou: the largest synced-lyrics catalogue reachable without a key.
 *
 * Search songs by "title - artist", take the ones within a few seconds of
 * the track's duration, ask for their lyrics candidates by hash, download
 * the first as base64 LRC; fall back to a keyword lyrics search. Timed lines
 * only, with KuGou's credit lines (作词/作曲/Lyrics by…) cut from the head and
 * tail, so the sheet shows lyrics and not liner notes.
 *
 * Returns null ONLY on a definitive miss (KuGou answered and had nothing).
 * Transport errors and non-2xx statuses THROW, so the repository never
 * mistakes "couldn't ask" for "asked, no lyrics".
 */
@Singleton
class KugouLyricsSource(
    okHttpClient: OkHttpClient,
    private val searchBaseUrl: String = "https://mobileservice.kugou.com",
    private val lyricsBaseUrl: String = "https://lyrics.kugou.com",
) : LyricsSource {

    override val id: String = "kugou"
    override val displayName: String = "KuGou"

    private val client: OkHttpClient = okHttpClient.newBuilder()
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    override suspend fun resolve(query: LyricsQuery): LyricsResult? = withContext(Dispatchers.IO) {
        val keyword = keywordFor(query.title, query.artist)
        val durationSec = query.durationMs?.let { (it / 1000).toInt() }?.takeIf { it > 0 } ?: NO_DURATION
        val candidate = findCandidate(keyword, durationSec) ?: return@withContext null
        val lrc = normalizeLrc(downloadLyrics(candidate))
        if (lrc.isBlank()) return@withContext null // a file with no timed lines is a miss, not lyrics
        LyricsResult(
            sourceId = id,
            plainText = plainFromLrc(lrc),
            syncedLrc = lrc,
            instrumental = false,
            language = null,
            sourceLyricsId = candidate.id.toString(),
        )
    }

    /** First lyrics candidate of the first song within the duration tolerance, else the keyword search's first. */
    private fun findCandidate(keyword: Keyword, durationSec: Int): Candidate? {
        for (song in searchSongs(keyword)) {
            if (durationSec == NO_DURATION || abs(song.duration - durationSec) <= DURATION_TOLERANCE_SEC) {
                searchLyrics(hash = song.hash).firstOrNull()?.let { return it }
            }
        }
        return searchLyrics(keyword = keyword, durationSec = durationSec).firstOrNull()
    }

    private fun searchSongs(keyword: Keyword): List<Song> {
        val url = "$searchBaseUrl/api/v3/search/song".toHttpUrl().newBuilder()
            .addQueryParameter("version", "9108")
            .addQueryParameter("plat", "0")
            .addQueryParameter("pagesize", PAGE_SIZE.toString())
            .addQueryParameter("showtype", "0")
            .addQueryParameter("keyword", keyword.query)
            .build()
        return get<SongSearchResponse>(url).data?.info.orEmpty()
    }

    private fun searchLyrics(hash: String? = null, keyword: Keyword? = null, durationSec: Int = NO_DURATION): List<Candidate> {
        val builder = "$lyricsBaseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("ver", "1")
            .addQueryParameter("man", "yes")
            .addQueryParameter("client", "pc")
        if (hash != null) {
            builder.addQueryParameter("hash", hash)
        } else {
            if (durationSec != NO_DURATION) builder.addQueryParameter("duration", (durationSec * 1000L).toString())
            builder.addQueryParameter("keyword", requireNotNull(keyword).query)
        }
        return get<LyricsSearchResponse>(builder.build()).candidates.orEmpty().sortedByDescending { it.isOfficial }
    }

    private fun downloadLyrics(candidate: Candidate): String {
        val url = "$lyricsBaseUrl/download".toHttpUrl().newBuilder()
            .addQueryParameter("fmt", "lrc")
            .addQueryParameter("charset", "utf8")
            .addQueryParameter("client", "pc")
            .addQueryParameter("ver", "1")
            .addQueryParameter("id", candidate.id.toString())
            .addQueryParameter("accesskey", candidate.accesskey)
            .build()
        val content = get<DownloadResponse>(url).content ?: throw IOException("kugou download without content")
        return String(Base64.getDecoder().decode(content), Charsets.UTF_8)
    }

    private inline fun <reified T> get(url: HttpUrl): T {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("kugou HTTP ${response.code} for ${url.encodedPath}")
            val body = response.body?.string() ?: throw IOException("kugou empty body for ${url.encodedPath}")
            return JSON.decodeFromString(body)
        }
    }

    /** What KuGou is asked for: a cleaned title and KuGou's artist joiner (、). */
    data class Keyword(val title: String, val artist: String) {
        val query: String get() = "$title - $artist"
    }

    @Serializable
    private data class SongSearchResponse(val data: SongSearchData? = null)

    @Serializable
    private data class SongSearchData(val info: List<Song> = emptyList())

    @Serializable
    private data class Song(val hash: String, val duration: Int = 0)

    @Serializable
    private data class LyricsSearchResponse(val candidates: List<Candidate> = emptyList())

    @Serializable
    data class Candidate(
        val id: Long,
        val accesskey: String = "",
        @kotlinx.serialization.SerialName("product_from") val productFrom: String = "",
    ) {
        /** KuGou marks its curated file; the rest are user uploads of varying quality. */
        val isOfficial: Boolean get() = productFrom == OFFICIAL_PRODUCT
    }

    @Serializable
    private data class DownloadResponse(val content: String? = null)

    companion object {
        private const val CALL_TIMEOUT_SECONDS = 15L
        private const val PAGE_SIZE = 8
        private const val DURATION_TOLERANCE_SEC = 8
        private const val NO_DURATION = -1
        private const val OFFICIAL_PRODUCT = "官方推荐歌词"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        /** Header/footer credit lines live within this many timed lines of either end. */
        private const val CREDIT_REGION_LINES = 30
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        private val PARENTHETICALS = listOf(
            "\\(.*?\\)", "（.*?）", "「.*?」", "『.*?』", "<.*?>", "《.*?》", "〈.*?〉", "＜.*?＞", "\\[.*?\\]",
        ).map(::Regex)
        private val TIMED_LINE = Regex("\\[(\\d\\d):(\\d\\d)\\.(\\d{2,3})\\].*")
        /** A timed line whose text carries a colon: 作词 : X, Lyrics by : Y, and friends. */
        private val CREDIT_LINE = Regex(".+].+[:：].+")
        private val TIMESTAMP = Regex("^\\[\\d\\d:\\d\\d\\.\\d{2,3}\\]")

        /** Strips bracketed tags from the title and joins artists the way KuGou lists them. */
        fun keywordFor(title: String, artist: String): Keyword {
            val cleanTitle = PARENTHETICALS.fold(title) { acc, re -> acc.replace(re, "") }
                .replace(Regex("\\s+"), " ").trim()
            val cleanArtist = artist
                .replace(", ", "、")
                .replace(" & ", "、")
                .replace(".", "")
                .replace("和", "、")
                .replace(Regex("\\(.*?\\)"), "")
                .replace(Regex("（.*?）"), "")
                .replace(Regex("\\s+"), " ").trim()
            return Keyword(cleanTitle, cleanArtist)
        }

        /**
         * Timed lines only, with credit lines cut from the head region and the
         * tail region (each up to [CREDIT_REGION_LINES] lines, never past the
         * middle, so a short song's tail credit cannot swallow its lyrics).
         */
        fun normalizeLrc(raw: String): String {
            val lines = raw.replace("&apos;", "'").lines().filter { it.matches(TIMED_LINE) }
            if (lines.isEmpty()) return ""
            val region = minOf(CREDIT_REGION_LINES, lines.size / 2)
            val headCut = (region - 1 downTo 0).firstOrNull { lines[it].matches(CREDIT_LINE) }?.plus(1) ?: 0
            val tailCut = (region - 1 downTo 0).firstOrNull { lines[lines.lastIndex - it].matches(CREDIT_LINE) }?.plus(1) ?: 0
            return lines.drop(headCut).dropLast(tailCut).joinToString("\n")
        }

        /** The LRC's text without timestamps, one line per timed line. */
        fun plainFromLrc(lrc: String): String =
            lrc.lines().map { it.replace(TIMESTAMP, "").trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }
}
