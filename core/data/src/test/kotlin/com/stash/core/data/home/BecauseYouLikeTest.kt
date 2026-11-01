package com.stash.core.data.home

import com.stash.data.ytmusic.InnerTubeClient
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.TrackSummary
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.math.exp
import kotlin.random.Random

/**
 * [BecauseYouLike]'s rules, the rows made from a real Related page (`home/related_never_gonna.json`: "Never Gonna
 * Give You Up", fetched signed out with hl=en gl=US on 2026-11-01, trimmed) read through the real [YTMusicApiClient].
 */
class BecauseYouLikeTest {

    private val day = 86_400_000L
    private val now = 1_793_000_000_000L

    private fun fixture(name: String): JsonObject = Json.parseToJsonElement(
        this::class.java.classLoader!!.getResourceAsStream("home/$name")!!.bufferedReader().use { it.readText() },
    ).jsonObject

    /** The Related page's songs, as the app reads them. */
    private val related: List<TrackSummary> by lazy {
        val inner = mock<InnerTubeClient>()
        runBlocking {
            whenever(inner.next(eq("lYBUbBu4W08"))).thenReturn(fixture("next_never_gonna.json"))
            whenever(inner.browse(eq("MPTRt_dcYZhAh5urI-1"), anyOrNull())).thenReturn(fixture("related_never_gonna.json"))
            YTMusicApiClient(inner).getRelatedSongs("lYBUbBu4W08")!!
        }
    }

    private val rick = BecauseSeed("lYBUbBu4W08", "Never Gonna Give You Up", "Rick Astley", "https://art/rick")

    private fun seed(id: String, artist: String = "Artist $id") = BecauseSeed(id, "Song $id", artist, null)

    // ── scores ──────────────────────────────────────────────────────────────

    @Test
    fun `a play fades with age, a third at two weeks, nothing past ninety days`() {
        val scores = BecauseYouLike.scores(
            plays = listOf(1L to now, 2L to now - 14 * day, 3L to now - 91 * day, 4L to now - 89 * day),
            likes = emptyList(),
            tired = emptySet(),
            nowMs = now,
        )
        assertEquals(1.0, scores.getValue(1L), 1e-9)
        assertEquals(exp(-1.0), scores.getValue(2L), 1e-9)
        assertTrue(scores.getValue(2L) in 0.33..0.37)
        assertFalse(3L in scores)
        assertTrue(scores.getValue(4L) < 0.002)
    }

    @Test
    fun `plays add up, a recent like adds more than a play, only the newest fifty likes count`() {
        val likes = (100L until 160L).toList()
        val scores = BecauseYouLike.scores(listOf(1L to now, 1L to now, 100L to now), likes, emptySet(), now)
        assertEquals(2.0, scores.getValue(1L), 1e-9)
        assertEquals(1.0 + BecauseYouLike.LIKE_WEIGHT, scores.getValue(100L), 1e-9)
        assertTrue(149L in scores)
        assertFalse(150L in scores)
    }

    @Test
    fun `a song skipped twice lately is no seed, however much it was played or liked`() {
        val scores = BecauseYouLike.scores(listOf(1L to now, 1L to now, 2L to now), listOf(1L), tired = setOf(1L), nowMs = now)
        assertEquals(setOf(2L), scores.keys)
    }

    // ── seeds ───────────────────────────────────────────────────────────────

    @Test
    fun `seeds are one per artist, never a song without a video, best score first`() {
        val candidates = listOf(
            SeedCandidate(1, seed("a", "Madonna"), 9.0),
            SeedCandidate(2, seed("b", "Madonna, Britney Spears"), 8.0),
            SeedCandidate(3, BecauseSeed("", "No video", "Toto", null), 50.0),
            SeedCandidate(4, seed("c", "Toto"), 2.0),
            SeedCandidate(5, seed("d", "Kenny Loggins"), 1.0),
        )
        val picked = BecauseYouLike.pickSeeds(candidates, Random(7), count = 3)
        assertEquals(listOf("a", "c", "d"), picked.map { it.seed.videoId })
        assertEquals(picked.sortedByDescending { it.score }, picked)
    }

    @Test
    fun `seeds are drawn by weight from the fourteen best, so the rows change while the favourites lead`() {
        val candidates = (0 until 30).map { SeedCandidate(it.toLong(), seed("v$it"), 30.0 - it) }
        val counts = HashMap<String, Int>()
        val random = Random(42)
        repeat(2_000) {
            BecauseYouLike.pickSeeds(candidates, random, count = 3).forEach { counts.merge(it.seed.videoId, 1, Int::plus) }
        }
        // Only the pool of fourteen is ever drawn
        assertTrue(counts.keys.all { it.removePrefix("v").toInt() < BecauseYouLike.POOL })
        // Not the same three every time, and the best more often than the fourteenth
        assertTrue(counts.size > 3)
        assertTrue(counts.getValue("v0") > counts.getValue("v13"))
    }

    // ── rows from a real Related page ───────────────────────────────────────

    @Test
    fun `a row is the related songs, not the seed's other performances, two per artist, each once`() {
        assertEquals(23, related.size)
        val row = BecauseYouLike.rows(listOf(rick), mapOf(rick.videoId to related), known = { false }, exclude = emptySet()).single()
        assertEquals(rick, row.seed)
        assertEquals(BecauseYouLike.CARDS, row.songs.size)
        assertEquals("Don't Dream It's Over", row.songs.first().title)
        // "Other performances": the remaster, a cover, a translation: never in the row
        assertTrue(row.songs.none { it.title.startsWith("Never Gonna Give You Up") })
        // Madonna has four songs on the page; two make the row, the first two
        assertEquals(listOf("Like a Virgin", "Papa Don't Preach"), row.songs.filter { it.artist == "Madonna" }.map { it.title })
        // "Holding Out For A Hero" is listed twice (album and soundtrack): once in the row
        assertEquals(1, row.songs.count { it.title.startsWith("Holding Out", ignoreCase = true) })
        // YouTube's order stands
        assertEquals(related.filter { it in row.songs }, row.songs)
    }

    @Test
    fun `about three quarters are songs you don't have`() {
        // Say you have every song on the page: a row of them would be no discovery
        val all = BecauseYouLike.rows(listOf(rick), mapOf(rick.videoId to related), known = { true }, exclude = emptySet())
        assertEquals(emptyList<BecauseRow>(), all)
        // You have the first eight: the row is the new ones, with a few known ones among them
        val known = related.take(8).map { it.videoId }.toSet()
        val row = BecauseYouLike.rows(listOf(rick), mapOf(rick.videoId to related), known = { it.videoId in known }, exclude = emptySet()).single()
        val knownInRow = row.songs.count { it.videoId in known }
        assertTrue(knownInRow <= BecauseYouLike.CARDS / 4)
        assertTrue(knownInRow * 3 <= row.songs.size - knownInRow)
        assertTrue(knownInRow > 0)
    }

    @Test
    fun `a song shows only in the first row with it, and a row under four cards is dropped`() {
        val other = BecauseSeed("other", "Take On Me", "a-ha", null)
        val third = BecauseSeed("third", "Africa", "Toto", null)
        val rows = BecauseYouLike.rows(
            listOf(rick, other, third),
            mapOf(rick.videoId to related, other.videoId to related, third.videoId to related.take(14)),
            known = { false },
            exclude = emptySet(),
        )
        // The second row gets what the first left; the third, only songs already shown, is dropped
        assertEquals(listOf(rick, other), rows.map { it.seed })
        val first = rows[0].songs.map { it.videoId }.toSet()
        assertTrue(rows[1].songs.none { it.videoId in first })
        assertTrue(rows[1].songs.size >= BecauseYouLike.MIN_CARDS)
        // A seed is never a card, in its row or another: "Africa" seeds the third row and leaves the others
        assertTrue(rows.flatMap { it.songs }.none { it.title == "Africa" })
    }

    @Test
    fun `daily discover's songs are left out`() {
        val dailyDiscover = setOf(BecauseYouLike.songKey("Don't Dream It's Over", "Crowded House"), BecauseYouLike.songKey("Africa", "Toto"))
        val row = BecauseYouLike.rows(listOf(rick), mapOf(rick.videoId to related), known = { false }, exclude = dailyDiscover).single()
        assertTrue(row.songs.none { it.title == "Don't Dream It's Over" || it.title == "Africa" })
        assertEquals("Danger Zone (From \"Top Gun\" Original Soundtrack)", row.songs.first().title)
    }

    @Test
    fun `the record's mixes on home take their songs out of the rows, a row left short goes`() {
        val row = BecauseYouLike.rows(listOf(rick), mapOf(rick.videoId to related), known = { false }, exclude = emptySet()).single()
        val shown = setOf(BecauseYouLike.songKey(row.songs[0].title, row.songs[0].artist))
        assertEquals(row.songs.drop(1), BecauseYouLike.without(listOf(row), shown).single().songs)
        val most = row.songs.drop(3).map { BecauseYouLike.songKey(it.title, it.artist) }.toSet()
        assertEquals(emptyList<BecauseRow>(), BecauseYouLike.without(listOf(row), most))
        assertEquals(listOf(row), BecauseYouLike.without(listOf(row), emptySet()))
    }

    @Test
    fun `a song's key is its main act and loose title`() {
        assertEquals(
            BecauseYouLike.songKey("Holding Out For A Hero", "Bonnie Tyler"),
            BecauseYouLike.songKey("Holding Out for a Hero (From \"Footloose\" Soundtrack)", "Bonnie Tyler"),
        )
        assertEquals(BecauseYouLike.songKey("Faith", "George Michael"), BecauseYouLike.songKey("Faith", "George Michael, Someone"))
        assertFalse(BecauseYouLike.songKey("Faith", "George Michael") == BecauseYouLike.songKey("Faith", "Limp Bizkit"))
    }
}
