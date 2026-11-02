package com.stash.feature.record.mixes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.stash.core.model.weblink.SongKey
import com.stash.feature.record.R
import com.stash.feature.record.archive.ArchivePlay
import com.stash.feature.record.art.ArtLookup
import com.stash.feature.record.taste.SongStat
import com.stash.feature.record.taste.Taste
import com.stash.feature.record.taste.TasteRows
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The history mixes here are the web's (`src/test/resources/mixes/`, written by `scripts/mix-vectors.ts` from stash-player's own
 * code at the live commit): `historyMixes` over the golden fixture's taste and crafted ones (ids, kinds, titles, lines and item
 * keys in order, with `mixHeading`, `mixLine`, `aboutLength` and the Mixes tab's `card`), `weekMix` over crafted archives and
 * the fixture's, `rotation` and `homePicks` day by day, and the words, in English from the app's own strings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HistoryMixesVectorsTest {
    private val res = ApplicationProvider.getApplicationContext<Context>().resources
    private val words = ResourceMixWords(ApplicationProvider.getApplicationContext())
    private val json = Json { ignoreUnknownKeys = true }

    private fun read(name: String): JsonElement = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/mixes/$name")) { name }.readText())

    private val fixture by lazy { read("fixture.json").jsonObject }
    private val fixtureTaste by lazy { tasteOf(fixture["taste"]!!.jsonObject) }
    private val fixtureRows by lazy {
        fixture["rows"]!!.jsonArray.map { r ->
            val a = r.jsonArray
            ArchivePlay(
                id = a[0].jsonPrimitive.content, t = a[1].jsonPrimitive.content, a = a[2].jsonPrimitive.content,
                al = (a[3] as? JsonPrimitive)?.contentOrNull, sid = (a[4] as? JsonPrimitive)?.contentOrNull,
                s = a[5].jsonPrimitive.long, ms = a[6].jsonPrimitive.long, f = a[7].jsonPrimitive.int,
            )
        }
    }

    private fun strings(e: JsonElement?): List<String> = e?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    /** A taste as the web wrote it: the body's fields, its songs, and when it was worked out. */
    private fun tasteOf(o: JsonObject): Taste {
        val b = json.decodeFromJsonElement(TasteRows.Body.serializer(), o)
        val songs = o["songs"]!!.jsonArray.map { json.decodeFromJsonElement(SongStat.serializer(), it) }
        return Taste(
            computedAt = o["computedAt"]!!.jsonPrimitive.long, first = b.first, last = b.last, plays = b.plays, listenedMs = b.listenedMs,
            artists = b.artists, songs = songs, eras = b.eras, constants = b.constants, lost = b.lost, obsessions = b.obsessions,
            found = b.found, years = b.years, topArtists = b.topArtists, seedArtists = b.seedArtists, yearTops = b.yearTops,
        )
    }

    private fun tasteIn(c: JsonObject): Taste = (c["taste"] as? JsonPrimitive)?.let { fixtureTaste } ?: tasteOf(c["taste"]!!.jsonObject)

    private fun libs(e: JsonElement?): List<LibrarySong> = e?.jsonArray.orEmpty().mapIndexed { i, x ->
        val o = x.jsonObject
        LibrarySong(
            trackId = i + 1L,
            title = o["title"]!!.jsonPrimitive.content,
            artist = o["artist"]!!.jsonPrimitive.content,
            album = o["album"]?.jsonPrimitive?.content,
            isrc = o["isrc"]?.jsonPrimitive?.content,
            durationMs = o["durationMs"]?.jsonPrimitive?.longOrNull ?: 0,
        )
    }

    /** service.ts `taste()`, as the app's mixes read it: the songs put back and the library's songs no longer kept out. */
    private fun asRead(t: Taste, keep: List<String>, have: List<LibrarySong>): Taste =
        HistoryMixes.putBackIn(t, (keep + have.map { SongKey.songKey(it.title, it.artist) }).toSet())

    private fun itemsSame(why: String, items: List<MixSong>, want: JsonArray) {
        assertWithMessage("$why: items").that(items.map { it.key }).isEqualTo(want.map { it.jsonObject["key"]!!.jsonPrimitive.content })
        assertWithMessage("$why: titles").that(items.map { it.title }).isEqualTo(want.map { it.jsonObject["title"]!!.jsonPrimitive.content })
        assertWithMessage("$why: artists").that(items.map { it.artist }).isEqualTo(want.map { it.jsonObject["artist"]!!.jsonPrimitive.content })
        assertWithMessage("$why: albums").that(items.map { it.album }).isEqualTo(want.map { it.jsonObject["album"]?.jsonPrimitive?.content })
    }

    private fun cardSame(why: String, c: HistoryMixes.MixCard, want: JsonObject) {
        assertWithMessage("$why: card when").that(c.`when`).isEqualTo(want["when"]!!.jsonPrimitive.content)
        assertWithMessage("$why: card who").that(c.who).isEqualTo(want["who"]!!.jsonPrimitive.content)
        assertWithMessage("$why: card line").that(c.line).isEqualTo(want["line"]!!.jsonPrimitive.content)
        assertWithMessage("$why: card foot").that(c.foot).isEqualTo(want["foot"]!!.jsonPrimitive.content)
        assertWithMessage("$why: card hue").that(c.hue).isEqualTo(want["hue"]!!.jsonPrimitive.int)
    }

    @Test fun `historyMixes - ids, kinds, titles, lines and item keys, with headings, lines, lengths and cards`() {
        val cases = read("history.json").jsonArray.map { it.jsonObject }
        assertThat(cases.size).isAtLeast(12)
        var mixes = 0
        for (c in cases) {
            val name = c["name"]!!.jsonPrimitive.content
            val have = libs(c["have"])
            val t = asRead(tasteIn(c), strings(c["keep"]), have)
            val got = HistoryMixes.historyMixes(t, HistoryMixes.haveIndex(have), words)
            val want = c["mixes"]!!.jsonArray.map { it.jsonObject }
            assertWithMessage("$name: ids").that(got.map { it.id }).isEqualTo(want.map { it["id"]!!.jsonPrimitive.content })
            for ((m, w) in got.zip(want)) {
                val why = "$name / ${m.id}"
                assertWithMessage("$why: kind").that(m.kind.wire).isEqualTo(w["kind"]!!.jsonPrimitive.content)
                assertWithMessage("$why: title").that(m.title).isEqualTo(w["title"]!!.jsonPrimitive.content)
                assertWithMessage("$why: sub").that(m.sub).isEqualTo(w["sub"]!!.jsonPrimitive.content)
                itemsSame(why, m.items, w["items"]!!.jsonArray)
                assertWithMessage("$why: heading").that(HistoryMixes.mixHeading(m, t)).isEqualTo(w["heading"]!!.jsonPrimitive.content)
                assertWithMessage("$why: line").that(HistoryMixes.mixLine(m, t, words)).isEqualTo(w["line"]!!.jsonPrimitive.content)
                assertWithMessage("$why: about").that(HistoryMixes.aboutLength(m.items, t, words)).isEqualTo(w["about"]!!.jsonPrimitive.content)
                cardSame(why, HistoryMixes.card(m, t, words), w["card"]!!.jsonObject)
                mixes++
            }
            assertWithMessage("$name: firstSongs").that(HistoryMixes.firstSongs(t).map { it.key }).isEqualTo(strings(c["firstSongs"]))
            val artistMixes = t.topArtists.take(12).map { HistoryMixes.artistMix(t, it, null, words)?.id }
            assertWithMessage("$name: artistMix").that(artistMixes).isEqualTo(c["artistMixes"]!!.jsonArray.map { (it as? JsonPrimitive)?.contentOrNull })
            val (tab, years) = HistoryMixes.mixesTab(got, null)
            assertWithMessage("$name: the Mixes tab").that(tab.map { it.id }).isEqualTo(strings(c["tab"]!!.jsonObject["mixes"]))
            assertWithMessage("$name: Your years").that(years.map { it.id }).isEqualTo(strings(c["tab"]!!.jsonObject["years"]))
        }
        assertThat(mixes).isAtLeast(60)
    }

    @Test fun `the cases cover what they say they do`() {
        val all = read("history.json").jsonArray.map { it.jsonObject }.associate { it["name"]!!.jsonPrimitive.content to it }
        fun ids(part: String) = all.entries.single { it.key.contains(part) }.value["mixes"]!!.jsonArray.map { it.jsonObject }
        // the year still going says so; the fixture's four eras and its years
        assertThat(ids("so far").map { it["title"]!!.jsonPrimitive.content }).contains("Your 2026 so far")
        assertThat(ids("golden fixture, its playlists").count { it["kind"]!!.jsonPrimitive.content == "era" }).isEqualTo(4)
        // an era across years, and early, mid and late ones
        val eras = ids("eras newest first").map { it["title"]!!.jsonPrimitive.content }
        assertThat(eras).contains("Northern Lights · November 2019 – February 2020")
        assertThat(eras.any { it.endsWith("Early 2021") } && eras.any { it.endsWith("Mid 2022") } && eras.any { it.endsWith("Late 2023") }).isTrue()
        assertThat(eras).doesNotContain("Seven Seas · Early 2025")
        // Rediscover leaves out The ones that stayed's songs, only when it is made
        assertThat(ids("Rediscover leaves out").single { it["id"]!!.jsonPrimitive.content == "lost" }["items"]!!.jsonArray).hasSize(14)
        assertThat(ids("with 7 songs is no mix").map { it["id"]!!.jsonPrimitive.content }).containsExactly("lost")
        // 7 songs no artist mix, 8 one; put back and liked songs play
        assertThat(ids("an artist with 7").map { it["id"]!!.jsonPrimitive.content }).doesNotContain("artist:seven ways")
        assertThat(ids("an artist with 7").map { it["id"]!!.jsonPrimitive.content }).contains("artist:eight fold")
        val liked = ids("put back and liked").first()["items"]!!.jsonArray.map { it.jsonObject["key"]!!.jsonPrimitive.content }
        // found by words, the library's ISRC copy; the remaster isn't the ISRC copy of the plain title (by key only without an ISRC)
        assertThat(liked).containsAtLeast("isrc:GBXYZ0000001", "old friend 2011 remaster|evergreen")
    }

    /** A song of the Mixes tab's lists as the web wrote it (toItem: its words' key, title, artist, album). */
    private fun itemSame(why: String, s: MixSong, want: JsonObject) {
        assertWithMessage("$why: key").that(s.key).isEqualTo(want["key"]!!.jsonPrimitive.content)
        assertWithMessage("$why: title").that(s.title).isEqualTo(want["title"]!!.jsonPrimitive.content)
        assertWithMessage("$why: artist").that(s.artist).isEqualTo(want["artist"]!!.jsonPrimitive.content)
        assertWithMessage("$why: album").that(s.album).isEqualTo(want["album"]?.jsonPrimitive?.content)
    }

    @Test fun `Top artists, Top songs and Not in your Stash yet - the lists, the play or open rule, the lines`() {
        val all = read("lists.json").jsonObject
        val cases = all["cases"]!!.jsonArray.map { it.jsonObject }
        assertThat(cases.size).isAtLeast(12)
        var artists = 0
        var lost = 0
        for (c in cases) {
            val name = c["name"]!!.jsonPrimitive.content
            val have = libs(c["have"])
            val t = asRead(tasteIn(c), strings(c["keep"]), have)

            val rows = HistoryMixes.topArtists(t)
            val made = HistoryMixes.historyMixes(t, null, words).map { it.id }
            val wantArtists = c["artists"]!!.jsonArray.map { it.jsonObject }
            assertWithMessage("$name: top artists").that(rows.map { it.artist.key }).isEqualTo(wantArtists.map { it["key"]!!.jsonPrimitive.content })
            for ((r, w) in rows.zip(wantArtists)) {
                val why = "$name / ${r.artist.name}"
                assertWithMessage("$why: name").that(r.artist.name).isEqualTo(w["name"]!!.jsonPrimitive.content)
                assertWithMessage("$why: line").that(words.artistLine(r.artist.listenedMs, r.artist.plays)).isEqualTo(w["line"]!!.jsonPrimitive.content)
                assertWithMessage("$why: opens").that(r.mixId).isEqualTo((w["mixId"] as? JsonPrimitive)?.contentOrNull)
                // the row's mix is the one historyMixes makes; with none, the row plays the artist's songs
                if (r.mixId != null) {
                    assertWithMessage("$why: its mix").that(made).contains(r.mixId)
                    assertWithMessage("$why: plays").that(w["plays"]).isEqualTo(JsonNull)
                } else {
                    assertWithMessage("$why: plays").that(HistoryMixes.artistSongs(t, r.artist.key).map { it.key }).isEqualTo(strings(w["plays"]))
                }
                artists++
            }

            val tops = HistoryMixes.topSongs(t).map(HistoryMixes::songItem)
            val wantTops = c["tops"]!!.jsonArray.map { it.jsonObject }
            assertWithMessage("$name: top songs").that(tops.map { it.songKey }).isEqualTo(wantTops.map { it["key"]!!.jsonPrimitive.content })
            for ((s, w) in tops.zip(wantTops)) itemSame("$name / top ${s.songKey}", s, w["item"]!!.jsonObject)

            val stats = t.songs.associateBy { it.key }
            val notIn = HistoryMixes.notInLibrary(t.lost, stats, HistoryMixes.libraryIndex(have)).map(HistoryMixes::songItem)
            val wantLost = c["lost"]!!.jsonArray.map { it.jsonObject }
            assertWithMessage("$name: not in your Stash yet").that(notIn.map { it.songKey }).isEqualTo(wantLost.map { it["key"]!!.jsonPrimitive.content })
            assertWithMessage("$name: lostSongs alone").that(notIn.map { it.songKey }).isEqualTo(strings(c["lostAlone"]))
            for ((s, w) in notIn.zip(wantLost)) itemSame("$name / lost ${s.songKey}", s, w["item"]!!.jsonObject)
            lost += notIn.size
            assertWithMessage("$name: its line").that(words.lostLine(notIn.size)).isEqualTo(c["lostLine"]!!.jsonPrimitive.content)
            assertWithMessage("$name: its Play").that(res.getString(R.string.record_mixes_lost_play, words.songs(notIn.size))).isEqualTo(c["lostPlay"]!!.jsonPrimitive.content)
        }
        assertThat(artists).isAtLeast(50)
        assertThat(lost).isAtLeast(400)
    }

    @Test fun `the lists' cases cover what they say they do`() {
        val cases = read("lists.json").jsonObject["cases"]!!.jsonArray.map { it.jsonObject }.associateBy { it["name"]!!.jsonPrimitive.content }
        fun case(part: String) = cases.entries.single { it.key.contains(part) }.value
        fun lostTitles(part: String) = case(part)["lost"]!!.jsonArray.map { it.jsonObject["item"]!!.jsonObject["title"]!!.jsonPrimitive.content }
        fun opens(part: String, artist: String) = case(part)["artists"]!!.jsonArray.map { it.jsonObject }.single { it["name"]!!.jsonPrimitive.content == artist }["mixId"]
        // 7 songs play, 8 open; a song put back or liked makes the 8th; only the first 10
        assertThat(opens("8 songs open", "Seven Ways")).isEqualTo(JsonNull)
        assertThat(opens("8 songs open", "Eight Fold")).isEqualTo(JsonPrimitive("artist:eight fold"))
        assertThat(opens("a song put back counts", "Seven Ways")).isEqualTo(JsonPrimitive("artist:seven ways"))
        assertThat(opens("a liked song counts", "Seven Ways")).isEqualTo(JsonPrimitive("artist:seven ways"))
        assertThat(case("8 songs open")["artists"]!!.jsonArray).hasSize(10)
        // the library by words, ISRC, the second of two ISRC twins and guest credits leaves; a remaster stays; kept never lost
        val before = lostTitles("nothing in the library, 60")
        val minus = lostTitles("the library by words")
        assertThat(before).containsAtLeast("By Words", "By Isrc", "Twin B", "Guest Song", "Guest Title", "Remaster Song - 2011 Remaster")
        assertThat(before).containsNoneOf("Kept Song", "Hidden Song", "Skipped Song", "Recent", "Seven Plays")
        assertThat(minus).containsNoneOf("By Words", "By Isrc", "Twin B", "Guest Song", "Guest Title")
        assertThat(minus).contains("Remaster Song - 2011 Remaster")
        assertThat(lostTitles("liked since")).containsNoneOf("Lost 03", "Lost 07")
        assertThat(lostTitles("put back are lost")).containsAtLeast("Hidden Song", "Skipped Song")
        assertThat(lostTitles("put back, then liked")).doesNotContain("Hidden Song")
        // past the page of 50
        assertThat(before.size).isGreaterThan(HistoryMixes.LOST_PAGE)
    }

    @Test fun `the rest of the Mixes tab says what the web says`() {
        val w = read("lists.json").jsonObject
        fun said(part: String, k: String) = w[part]!!.jsonObject[k]!!.jsonPrimitive.content
        assertThat(">${res.getString(R.string.record_mixes_top_artists)}</h2>").isEqualTo(said("page", ">Top artists</h2>"))
        assertThat(">${res.getString(R.string.record_mixes_top_songs)}</h2>").isEqualTo(said("page", ">Top songs</h2>"))
        assertThat(">${res.getString(R.string.record_mixes_lost)}</h2>").isEqualTo(said("page", ">Not in your Stash yet</h2>"))
        assertThat(">${res.getString(R.string.record_mixes_show_more)}</button>").isEqualTo(said("page", ">Show more</button>"))
        assertThat("aria-label=\"${res.getString(R.string.record_mixes_artist_open, "{a.name}")}\"").isEqualTo(said("page", "aria-label=\"Open your most played songs by {a.name}\""))
        assertThat("aria-label=\"${res.getString(R.string.record_mixes_artist_play, "{a.name}")}\"").isEqualTo(said("page", "aria-label=\"Play your most played songs by {a.name}\""))
        assertThat("aria-label=\"${res.getString(R.string.record_mixes_lost_play, "{songs(lost.length)}")}\"").isEqualTo(said("page", "aria-label=\"Play the {songs(lost.length)} not in your Stash yet\""))
        assertThat(res.getQuantityString(R.plurals.record_mixes_lost_line, 2, 2).removePrefix("2 songs"))
            .isEqualTo(said("page", "{songs(lost.length)} you loved and haven’t played in a year. Like one to keep it.").removePrefix("{songs(lost.length)}"))
        assertThat("aria-label=\"${res.getString(R.string.record_song_more, "{item.title}")}\"").isEqualTo(said("row", "aria-label=\"More for {item.title}\""))
        assertThat("liked ? '${res.getString(R.string.record_song_unlike)}' : '${res.getString(R.string.record_song_like)}'").isEqualTo(said("like", "liked ? 'Unlike' : 'Like'"))
        // mix.ts's own numbers
        val constants = w["constants"]!!.jsonObject
        assertThat(constants["TOP_ARTISTS"]!!.jsonPrimitive.int).isEqualTo(HistoryMixes.TOP_ARTISTS)
        assertThat(constants["TOP_SONGS"]!!.jsonPrimitive.int).isEqualTo(HistoryMixes.TOP_SONGS)
        assertThat(constants["LOST_PAGE"]!!.jsonPrimitive.int).isEqualTo(HistoryMixes.LOST_PAGE)
    }

    @Test fun `an artist's picture - the Worker's own artistSearch and pickArtist, case by case`() {
        val cases = read("artists.json").jsonArray.map { it.jsonObject }
        assertThat(cases.size).isAtLeast(35)
        var pictures = 0
        for (c in cases) {
            val name = c["name"]!!.jsonPrimitive.content
            val artist = c["artist"]!!.jsonPrimitive.content
            val hits = (c["hits"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            assertWithMessage("$name: the search").that(ArtLookup.artistSearch(artist)).isEqualTo(c["search"]!!.jsonPrimitive.content)
            val want = (c["picture"] as? JsonPrimitive)?.contentOrNull
            assertWithMessage("$name: the picture").that(ArtLookup.pickArtist(hits, artist)).isEqualTo(want)
            if (want != null) pictures++
        }
        // the cases pick a picture and refuse one, both
        assertThat(pictures).isAtLeast(20)
        assertThat(cases.size - pictures).isAtLeast(10)
    }

    @Test fun `weekMix - the windows, the counts, the first spelling, the words`() {
        val cases = read("week.json").jsonArray.map { it.jsonObject }
        assertThat(cases.size).isAtLeast(10)
        for (c in cases) {
            val name = c["name"]!!.jsonPrimitive.content
            val fixtureCase = c["rows"] is JsonPrimitive
            val rows = if (fixtureCase) fixtureRows else c["rows"]!!.jsonArray.map { it.jsonObject }.map {
                ArchivePlay(
                    id = it["id"]!!.jsonPrimitive.content, t = it["t"]!!.jsonPrimitive.content, a = it["a"]!!.jsonPrimitive.content,
                    al = it["al"]?.jsonPrimitive?.content, sid = it["sid"]?.jsonPrimitive?.content,
                    s = it["s"]!!.jsonPrimitive.long, ms = it["ms"]!!.jsonPrimitive.long, f = it["f"]!!.jsonPrimitive.int,
                )
            }
            val t = if (fixtureCase) fixtureTaste else c["taste"]!!.jsonObject.let { o ->
                Taste(
                    computedAt = 0, first = o["first"]!!.jsonPrimitive.long, last = 0, plays = 0, listenedMs = 0, artists = 0,
                    songs = o["songs"]!!.jsonArray.map { json.decodeFromJsonElement(SongStat.serializer(), it) }, eras = emptyList(),
                    constants = emptyList(), lost = emptyList(), obsessions = emptyList(), found = null, years = emptyList(),
                    topArtists = emptyList(), seedArtists = emptyList(), yearTops = emptyList(),
                )
            }
            val sorted = rows.sortedWith(HistoryMixes.BY_START)
            val between = { from: Long, to: Long -> sorted.filter { it.s >= from && it.s < to } }
            val m = HistoryMixes.weekMix(between, t, c["now"]!!.jsonPrimitive.long, HistoryMixes.haveIndex(libs(c["have"])), words)
            val want = c["mix"]
            if (want == null || want is JsonNull) {
                assertWithMessage(name).that(m).isNull()
                continue
            }
            val w = want.jsonObject
            assertWithMessage(name).that(m).isNotNull()
            assertWithMessage("$name: id").that(m!!.id).isEqualTo(w["id"]!!.jsonPrimitive.content)
            assertWithMessage("$name: kind").that(m.kind.wire).isEqualTo(w["kind"]!!.jsonPrimitive.content)
            assertWithMessage("$name: title").that(m.title).isEqualTo(w["title"]!!.jsonPrimitive.content)
            assertWithMessage("$name: sub").that(m.sub).isEqualTo(w["sub"]!!.jsonPrimitive.content)
            itemsSame(name, m.items, w["items"]!!.jsonArray)
            assertWithMessage("$name: about").that(HistoryMixes.aboutLength(m.items, t, words)).isEqualTo(w["about"]!!.jsonPrimitive.content)
            cardSame(name, HistoryMixes.card(m, t, words), w["card"]!!.jsonObject)
        }
    }

    @Test fun `rotation and homePicks, every day`() {
        val history = read("history.json").jsonArray.map { it.jsonObject }.associateBy { it["name"]!!.jsonPrimitive.content }
        val cases = read("rotation.json").jsonArray.map { it.jsonObject }
        assertThat(cases.size).isEqualTo(history.size)
        val week = HistoryMix("week", MixKind.WEEK, "This week, years ago", "", emptyList())
        for (c in cases) {
            val name = c["name"]!!.jsonPrimitive.content
            val h = history.getValue(name)
            val have = libs(h["have"])
            val all = HistoryMixes.historyMixes(asRead(tasteIn(h), strings(h["keep"]), have), HistoryMixes.haveIndex(have), words)
            assertWithMessage("$name: rotation").that(HistoryMixes.rotation(all).map { it.id }).isEqualTo(strings(c["rotation"]))
            for (d in c["picks"]!!.jsonArray.map { it.jsonObject } + c["far"]!!.jsonArray.map { it.jsonObject }) {
                val day = d["day"]!!.jsonPrimitive.long
                assertWithMessage("$name: day $day").that(HistoryMixes.homePicks(all, null, day, HistoryMixes.WEB_HOME_MIXES).map { it.id }).isEqualTo(strings(d["ids"]))
                d["withWeek"]?.let { assertWithMessage("$name: day $day, with this week").that(HistoryMixes.homePicks(all, week, day, HistoryMixes.WEB_HOME_MIXES).map { it.id }).isEqualTo(strings(it)) }
            }
        }
    }

    @Test fun `the app's Home shows six a day, the web's three first, none twice`() {
        val history = read("history.json").jsonArray.map { it.jsonObject }.associateBy { it["name"]!!.jsonPrimitive.content }
        val cases = read("rotation.json").jsonArray.map { it.jsonObject }
        val week = HistoryMix("week", MixKind.WEEK, "This week, years ago", "", emptyList())
        assertThat(HistoryMixes.HOME_MIXES).isEqualTo(6)
        for (c in cases) {
            val name = c["name"]!!.jsonPrimitive.content
            val h = history.getValue(name)
            val have = libs(h["have"])
            val all = HistoryMixes.historyMixes(asRead(tasteIn(h), strings(h["keep"]), have), HistoryMixes.haveIndex(have), words)
            for (d in c["picks"]!!.jsonArray.map { it.jsonObject } + c["far"]!!.jsonArray.map { it.jsonObject }) {
                val day = d["day"]!!.jsonPrimitive.long
                val picks = HistoryMixes.homePicks(all, null, day).map { it.id }
                assertWithMessage("$name: day $day").that(picks.size).isEqualTo(minOf(HistoryMixes.HOME_MIXES, all.size))
                assertWithMessage("$name: day $day").that(picks).containsNoDuplicates()
                // The same daily turn as the web's: its three lead
                assertWithMessage("$name: day $day").that(picks.take(HistoryMixes.WEB_HOME_MIXES)).isEqualTo(strings(d["ids"]))
                d["withWeek"]?.let {
                    val withWeek = HistoryMixes.homePicks(all, week, day).map { m -> m.id }
                    assertWithMessage("$name: day $day, with this week").that(withWeek.first()).isEqualTo("week")
                    assertWithMessage("$name: day $day, with this week").that(withWeek.size).isEqualTo(minOf(HistoryMixes.HOME_MIXES, all.size + 1))
                    assertWithMessage("$name: day $day, with this week").that(withWeek).containsNoDuplicates()
                    assertWithMessage("$name: day $day, with this week").that(withWeek.take(HistoryMixes.WEB_HOME_MIXES)).isEqualTo(strings(it))
                }
            }
        }
    }

    @Test fun `eraWhen, andList and aboutLength`() {
        val w = read("words.json").jsonObject
        for (e in w["eraWhen"]!!.jsonArray.map { it.jsonObject }) {
            val (start, end) = e["start"]!!.jsonPrimitive.long to e["end"]!!.jsonPrimitive.long
            assertWithMessage("$start..$end").that(HistoryMixes.eraWhen(start, end, words)).isEqualTo(e["when"]!!.jsonPrimitive.content)
        }
        for (a in w["andList"]!!.jsonArray.map { it.jsonObject }) {
            assertThat(HistoryMixes.andList(strings(a["xs"]), words)).isEqualTo(a["line"]!!.jsonPrimitive.content)
        }
        val about = w["about"]!!.jsonObject
        val t = tasteOf(about["taste"]!!.jsonObject)
        for (c in about["cases"]!!.jsonArray.map { it.jsonObject }) {
            val items = libs(c["items"]).map { l ->
                MixSong(l.title, l.artist, l.album, l.libraryKey, SongKey.songKey(l.title, l.artist), lib = l.takeIf { it.durationMs != 0L })
            }
            assertWithMessage(c["items"].toString()).that(HistoryMixes.aboutLength(items, t, words)).isEqualTo(c["about"]!!.jsonPrimitive.content)
        }
    }

    @Test fun `the mix page, Home's shelf and the Mixes tab say what the web says`() {
        val w = read("words.json").jsonObject
        fun said(file: String, k: String) = w[file]!!.jsonObject[k]!!.jsonPrimitive.content
        assertThat(res.getString(R.string.record_mix_eyebrow)).isEqualTo(said("mixView", "Mix from your record"))
        assertThat(res.getString(R.string.record_mix_save)).isEqualTo(said("mixView", "Save as playlist"))
        assertThat(res.getString(R.string.record_mix_saving)).isEqualTo(said("mixView", "Saving…"))
        assertThat(res.getString(R.string.record_mix_open_saved, "{saved.name}")).isEqualTo(said("mixView", "Open “{saved.name}”"))
        assertThat(res.getString(R.string.record_mix_saved, "\${p.name}")).isEqualTo(said("mixView", "Saved “\${p.name}” to your playlists"))
        assertThat(res.getString(R.string.record_mix_save_failed)).isEqualTo(said("mixView", "Couldn’t save it just now. Try again."))
        assertThat(res.getString(R.string.record_mix_gone)).isEqualTo(said("mixView", "That mix isn’t in your record any more: mixes change as your listening does."))
        assertThat(res.getString(R.string.record_mix_gone_back)).isEqualTo(said("mixView", "Back to Your record"))
        assertThat("aria-label=\"${res.getString(R.string.record_mix_play_label, "{mix.title}")}\"").isEqualTo(said("mixView", "aria-label=\"Play {mix.title}\""))
        assertThat("aria-label=\"${res.getString(R.string.record_mix_shuffle_label, "{mix.title}")}\"").isEqualTo(said("mixView", "aria-label=\"Shuffle {mix.title}\""))
        assertThat(res.getString(R.string.record_mixes_shelf)).isEqualTo(said("homeShelf", "From your record"))
        assertThat(res.getString(R.string.record_mixes_show_all)).isEqualTo(said("homeShelf", "Show all"))
        assertThat("label=\"${res.getString(R.string.record_mixes_open, "{m.title}")}\"").isEqualTo(said("homeShelf", "label=\"Open {m.title}\""))
        assertThat(">${res.getString(R.string.record_tab_record)}</button>").isEqualTo(said("recordPage", ">The record</button>"))
        assertThat(">${res.getString(R.string.record_tab_mixes)}</button>").isEqualTo(said("recordPage", ">Mixes</button>"))
        assertThat(">${res.getString(R.string.record_mixes_from_years)}</h2>").isEqualTo(said("recordPage", ">Mixes from your years</h2>"))
        assertThat(">${res.getString(R.string.record_mixes_your_years)}</h2>").isEqualTo(said("recordPage", ">Your years</h2>"))
        assertThat(res.getString(R.string.record_mixes_none_action)).isEqualTo(said("recordPage", "Bring your listening"))
        assertThat("aria-label=\"${res.getString(R.string.record_mix_play_label, "{m.title}")}\"").isEqualTo(said("recordPage", "aria-label=\"Play {m.title}\""))
        // format.ts songs
        assertThat(words.songs(1)).isEqualTo("1 song")
        assertThat(words.songs(50)).isEqualTo("50 songs")
    }
}
