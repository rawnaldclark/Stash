package com.stash.data.download.lossless.qobuz

import com.google.common.truth.Truth.assertWithMessage
import com.stash.core.model.MusicSource
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.forLibrarySource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Test
import java.util.zip.GZIPInputStream

/**
 * Scores [QobuzCandidateMatcher] against a labelled test set of real library songs
 * (`src/test/resources/matcher/corpus-v1.json`): each song from the owner's library, the top
 * catalog search results for it, and a verdict per result (match / wrong_version / wrong_song /
 * unsure) labelled by Claude under the app's version rules (see the corpus's `policy` field).
 *
 * The matcher picks like the sources do: the highest-scoring candidate at or above
 * [QobuzCandidateMatcher.MIN_CONFIDENCE]. Per song the outcome is one of:
 *  - correct: it picked a "match";
 *  - wrong accept: it picked a wrong version or a wrong song (the user gets the wrong audio);
 *  - miss: it picked nothing (or nothing it should) although a "match" was listed;
 *  - correct reject: it picked nothing and nothing listed was a match.
 *
 * The baselines below are a ratchet: a matcher change may not make either count worse. When a
 * change improves them, lower the numbers in the same commit.
 */
class MatcherCorpusTest {

    @Serializable
    private data class Corpus(val version: Int, val policy: String, val items: List<Item>)

    @Serializable
    private data class Item(val qid: String, val source: String, val query: Query, val candidates: List<Candidate>)

    @Serializable
    private data class Query(
        val artist: String,
        val title: String,
        val album: String? = null,
        val durationMs: Long? = null,
        val isrc: String? = null,
    )

    @Serializable
    private data class Candidate(
        val cid: String,
        val title: String,
        val version: String = "",
        val artist: String,
        val album: String = "",
        val durationSec: Int = 0,
        val verdict: String,
        val reason: String = "",
    )

    private enum class Outcome { CORRECT, WRONG_VERSION, WRONG_SONG, MISS, CORRECT_REJECT, UNSURE }

    /** A corpus from test resources; `.gz` ones are gzipped compact JSON. */
    private fun load(name: String): Corpus {
        val url = checkNotNull(javaClass.classLoader?.getResource("matcher/$name")) { "matcher/$name missing from test resources" }
        val text = url.openStream().use { raw ->
            (if (name.endsWith(".gz")) GZIPInputStream(raw) else raw).bufferedReader().readText()
        }
        return Json { ignoreUnknownKeys = true }.decodeFromString(text)
    }

    /** The query the app builds for this library row (forLibrarySource: YouTube rows parsed, length unreliable). */
    private fun query(q: Query, source: String) = TrackQuery(
        artist = q.artist,
        title = q.title,
        album = q.album?.takeIf { it.isNotBlank() },
        isrc = q.isrc?.takeIf { it.isNotBlank() },
        durationMs = q.durationMs?.takeIf { it > 0 },
    ).forLibrarySource(if (source == "YOUTUBE") MusicSource.YOUTUBE else MusicSource.SPOTIFY)

    private fun score(q: TrackQuery, c: Candidate): Float = QobuzCandidateMatcher.confidence(
        query = q,
        candTitle = c.title,
        candArtist = c.artist,
        candIsrc = null,
        candDurationSec = c.durationSec,
        candStreamable = true,
        candVersion = c.version.takeIf { it.isNotBlank() },
        candAlbum = c.album.takeIf { it.isNotBlank() },
    )

    private fun outcome(item: Item): Pair<Outcome, Candidate?> {
        val q = query(item.query, item.source)
        val best = item.candidates
            .map { it to score(q, it) }
            .filter { it.second >= QobuzCandidateMatcher.MIN_CONFIDENCE }
            .maxByOrNull { it.second }
            ?.first
        val hasMatch = item.candidates.any { it.verdict == "match" }
        return when {
            best == null && hasMatch -> Outcome.MISS to null
            best == null -> Outcome.CORRECT_REJECT to null
            best.verdict == "match" -> Outcome.CORRECT to best
            best.verdict == "wrong_version" -> Outcome.WRONG_VERSION to best
            best.verdict == "wrong_song" -> Outcome.WRONG_SONG to best
            else -> Outcome.UNSURE to best
        }
    }

    @Test fun `matcher does not get worse on corpus v1`() = check(load("corpus-v1.json"), V1_MAX_WRONG, V1_MAX_MISSES)

    @Test fun `matcher does not get worse on corpus v2`() = check(load("corpus-v2.json.gz"), V2_MAX_WRONG, V2_MAX_MISSES)

    private fun check(corpus: Corpus, maxWrong: Int, maxMisses: Int) {
        val results = corpus.items.map { it to outcome(it) }
        val counts = Outcome.entries.associateWith { o -> results.count { it.second.first == o } }
        val wrong = counts.getValue(Outcome.WRONG_VERSION) + counts.getValue(Outcome.WRONG_SONG)
        val misses = counts.getValue(Outcome.MISS)

        println("matcher corpus v${corpus.version}: ${corpus.items.size} songs -> $counts")
        results.filter { it.second.first in setOf(Outcome.WRONG_VERSION, Outcome.WRONG_SONG, Outcome.MISS) }
            .take(REPORT_LIMIT)
            .forEach { (item, r) ->
                val (o, picked) = r
                val q = item.query
                val shouldBe = item.candidates.firstOrNull { it.verdict == "match" }
                println(
                    "  $o [${item.source}] ${item.qid} '${q.artist} - ${q.title}'" +
                        (picked?.let { " picked '${it.artist} - ${it.title}${if (it.version.isNotBlank()) " (${it.version})" else ""}': ${it.reason}" } ?: "") +
                        (shouldBe?.let { " | match: '${it.artist} - ${it.title}${if (it.version.isNotBlank()) " (${it.version})" else ""}'" } ?: ""),
                )
            }

        assertWithMessage("v${corpus.version} wrong accepts (wrong version or wrong song picked)").that(wrong).isAtMost(maxWrong)
        assertWithMessage("v${corpus.version} misses (a listed match was not picked)").that(misses).isAtMost(maxMisses)
    }

    private companion object {
        /**
         * Ratchet: today's counts on corpus v1. Lower them when the matcher improves. YouTube rows are
         * scored with [YouTubeSongTitle]-parsed fields like the app's lookups (forLibrarySource); v1's
         * candidates came from raw-title searches, so this set cannot credit the parser's search gain
         * (catalog hit rate 61% -> 76% on YouTube rows).
         */
        const val V1_MAX_WRONG = 10
        const val V1_MAX_MISSES = 18

        /** Ratchet for corpus v2 (3,682 songs, Deezer + Apple candidates, Fable-checked labels). */
        const val V2_MAX_WRONG = 54
        const val V2_MAX_MISSES = 108
        const val REPORT_LIMIT = 400
    }
}
