package com.stash.data.download.files

import com.google.common.truth.Truth.assertThat
import com.stash.core.common.AppVersionProvider
import com.stash.core.model.Track
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class ExtraTagsTest {
    private lateinit var server: MockWebServer
    private lateinit var catalogue: CatalogueTags
    private var now = 0L

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        catalogue = CatalogueTags(OkHttpClient()).apply {
            baseUrl = server.url("/").toString()
            paceMs = 0
            clock = { now }
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun hit(
        title: String = "Nude",
        artist: String = "Radiohead",
        album: String = "In Rainbows",
        albumArtist: String = "",
        date: String = "2007-10-10T07:00:00Z",
        track: Int = 3,
        disc: Int = 1,
        genre: String = "Alternative",
        ms: Long = 255_400,
    ) = CatalogueHit(title, artist, album, albumArtist, date, track, disc, genre, ms)

    private fun track(title: String = "Nude", artist: String = "Radiohead", album: String = "", ms: Long = 255_000) =
        Track(id = 1, title = title, artist = artist, album = album, durationMs = ms)

    @Test
    fun `picks the same recording on the record it first came out on`() {
        val hits = listOf(
            hit(album = "The Best Of", date = "2008-06-02T07:00:00Z", track = 9, disc = 2, ms = 255_000),
            hit(),
            hit(title = "Nude (Live)", album = "Live Somewhere", date = "2001-01-01T07:00:00Z"),
            hit(artist = "Someone Else", album = "Covers", date = "1999-01-01T07:00:00Z", genre = "Pop"),
            hit(album = "Hits of the 2000s", albumArtist = "Various Artists", date = "2005-01-01T07:00:00Z", genre = "Rock"),
            hit(album = "Radio Edits - Single", date = "2006-01-01T07:00:00Z", ms = 199_000),
        )

        assertThat(CatalogueMatch.pick(track(), hits))
            .isEqualTo(ExtraTags(album = "In Rainbows", year = 2007, trackNumber = 3, discNumber = 1, genre = "Alternative"))
        // Another song, or the same title at another length, is not guessed at.
        assertThat(CatalogueMatch.pick(track(title = "Reckoner"), hits)).isNull()
        assertThat(CatalogueMatch.pick(track(ms = 400_000), hits)).isNull()
    }

    @Test
    fun `a release label is not another version and a credit of several matches one`() {
        val hits = listOf(hit(title = "Dreams", artist = "Fleetwood Mac", album = "Rumours", date = "1977-02-04T08:00:00Z", track = 2, genre = "Rock", ms = 257_000))
        assertThat(CatalogueMatch.pick(track(title = "Dreams - 2004 Remaster", artist = "Fleetwood Mac", ms = 257_800), hits)?.album).isEqualTo("Rumours")
        assertThat(CatalogueMatch.pick(track(title = "Dreams (Live)", artist = "Fleetwood Mac", ms = 257_800), hits)).isNull()

        val single = listOf(hit(title = "Song (feat. B)", artist = "A & B", album = "Song (feat. B) - Single", date = "2020-05-01T07:00:00Z", track = 1, genre = "Hip-Hop/Rap", ms = 200_000))
        val found = CatalogueMatch.pick(track(title = "Song", artist = "A, B", ms = 201_000), single)
        assertThat(found?.album).isEqualTo("Song (feat. B)")
        assertThat(CatalogueMatch.query(track(title = "Song (feat. B) - 2011 Remaster", artist = "A, B"))).isEqualTo("a song")
    }

    @Test
    fun `only blanks are filled and a track number only for the same album`() {
        val found = ExtraTags(album = "In Rainbows", year = 2007, trackNumber = 3, discNumber = 1, genre = "Alternative")

        // A bare file takes it all (disc one is not worth writing).
        assertThat(found.onlyBlanks(ExistingTags(), track()))
            .isEqualTo(ExtraTags(album = "In Rainbows", year = 2007, trackNumber = 3, genre = "Alternative"))
        // A track Stash knows on another album keeps it: only the genre is taken.
        assertThat(found.onlyBlanks(ExistingTags(), track(album = "The Best Of")))
            .isEqualTo(ExtraTags(genre = "Alternative"))
        // The same album by name takes the numbers, not the album again.
        assertThat(found.onlyBlanks(ExistingTags(album = "in rainbows", hasGenre = true), track()))
            .isEqualTo(ExtraTags(year = 2007, trackNumber = 3))
        // A file a source tagged in full is left as it is, and is not even looked up.
        val full = ExistingTags(album = "In Rainbows", hasGenre = true, hasYear = true, hasTrackNumber = true)
        assertThat(found.onlyBlanks(full, track()).isEmpty).isTrue()
        assertThat(full.isComplete(track())).isTrue()
        assertThat(ExistingTags(hasGenre = true, hasYear = true, hasTrackNumber = true).isComplete(track())).isFalse()
    }

    @Test
    fun `the extra tags are written under ffmpeg's names and the track's own album wins`() {
        val version = object : AppVersionProvider {
            override val versionName = "0.9.126"
            override val versionCode = 162
        }
        fun args(track: Track, extra: ExtraTags?) = MetadataEmbedder
            .buildFfmpegArgs(File("/tmp/in.flac"), File("/tmp/out.flac"), track, null, version, extraTags = extra)
            .let { all -> all.indices.filter { all[it] == "-metadata" }.map { all[it + 1] } }

        val bare = args(track(), ExtraTags(album = "In Rainbows", year = 2007, trackNumber = 3, discNumber = 2, genre = "Alternative"))
        assertThat(bare).containsAtLeast("album=In Rainbows", "genre=Alternative", "date=2007", "track=3", "disc=2")

        val known = args(track(album = "The Best Of"), ExtraTags(album = "In Rainbows", genre = "Alternative"))
        assertThat(known).contains("album=The Best Of")
        assertThat(known).doesNotContain("album=In Rainbows")

        val none = args(track(), null)
        assertThat(none.none { it.startsWith("genre=") || it.startsWith("date=") || it.startsWith("track=") }).isTrue()
    }

    @Test
    fun `the catalogue is asked for the first artist and the title and its answer is read`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"resultCount":2,"results":[
                {"wrapperType":"track","kind":"song","trackName":"Nude","artistName":"Radiohead","collectionName":"In Rainbows","releaseDate":"2007-10-10T07:00:00Z","trackNumber":3,"discNumber":1,"primaryGenreName":"Alternative","trackTimeMillis":255400,"somethingNew":true},
                {"wrapperType":"track","kind":"music-video","trackName":"Nude","artistName":"Radiohead","collectionName":"Videos","releaseDate":"2001-01-01T07:00:00Z","trackNumber":1,"discNumber":1,"primaryGenreName":"Alternative","trackTimeMillis":255400}
                ]}""",
            ),
        )

        val found = catalogue.lookUp(track(artist = "Radiohead, Someone"))

        assertThat(found).isEqualTo(ExtraTags(album = "In Rainbows", year = 2007, trackNumber = 3, discNumber = 1, genre = "Alternative"))
        val asked = server.takeRequest().requestUrl!!
        assertThat(asked.encodedPath).isEqualTo("/search")
        assertThat(asked.queryParameter("term")).isEqualTo("radiohead nude")
        assertThat(asked.queryParameter("entity")).isEqualTo("song")
    }

    @Test
    fun `a failure is no answer, and a turned-away request leaves the catalogue alone for a while`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        assertThat(catalogue.lookUp(track())).isNull()
        server.enqueue(MockResponse().setBody("not json"))
        assertThat(catalogue.lookUp(track())).isNull()

        server.enqueue(MockResponse().setResponseCode(403))
        assertThat(catalogue.lookUp(track())).isNull()
        val asked = server.requestCount
        // While it cools off, nothing is asked at all.
        assertThat(catalogue.lookUp(track())).isNull()
        assertThat(server.requestCount).isEqualTo(asked)
        // After the wait it is asked again.
        now += 120_001
        server.enqueue(MockResponse().setBody("""{"results":[]}"""))
        assertThat(catalogue.lookUp(track())).isNull()
        assertThat(server.requestCount).isEqualTo(asked + 1)
    }
}
