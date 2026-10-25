package com.stash.core.data.backcover

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.AlbumBackCoverDao
import com.stash.core.data.db.entity.AlbumBackCoverEntity
import com.stash.core.data.musicbrainz.MusicBrainzClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class AlbumBackCoverRepositoryTest {

    private class FakeDao : AlbumBackCoverDao {
        val rows = MutableStateFlow<Map<String, AlbumBackCoverEntity>>(emptyMap())
        override fun observe(albumKey: String): Flow<AlbumBackCoverEntity?> = rows.map { it[albumKey.lowercase()] }
        override suspend fun get(albumKey: String) = rows.value[albumKey.lowercase()]
        override suspend fun upsert(row: AlbumBackCoverEntity) {
            rows.value = rows.value + (row.albumKey.lowercase() to row)
        }
        override suspend fun forgetDiscogsBefore(before: Long) {
            rows.value = rows.value.mapValues { (_, r) ->
                if (!(r.backFromDiscogs || r.insideFromDiscogs) || r.attemptedAt >= before) {
                    r
                } else {
                    r.copy(
                        imageUrl = r.imageUrl.takeUnless { r.backFromDiscogs },
                        releaseMbid = r.releaseMbid.takeUnless { r.backFromDiscogs },
                        insideUrls = r.insideUrls.takeUnless { r.insideFromDiscogs },
                        backFromDiscogs = false,
                        insideFromDiscogs = false,
                        discogsPage = null,
                        attemptedAt = 0,
                    )
                }
            }
        }
    }

    /** One album on MusicBrainz: a release group with [releases], found by ISRC [isrc] or by title. */
    private class FakeMusicBrainz(
        val groupId: String = "rg-1",
        val title: String = "Nevermind",
        val artist: String = "Nirvana",
        val isrc: String? = "USGF19942501",
        val releases: List<Pair<String, String>> = listOf("cd-1" to "CD", "vinyl-1" to "12\" Vinyl"),
        /** The Discogs master the release group links to; none by default. */
        val discogsMaster: Long? = null,
        /** The Discogs releases the group's releases link to, by MusicBrainz release; none by default. */
        val discogsReleases: Map<String, Long> = emptyMap(),
    ) : MusicBrainzClient {
        var calls = 0
        var browses = 0
        var failing = false
        var gate: CompletableDeferred<Unit>? = null

        private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

        override suspend fun lookupUrlRels(mbid: String): JsonObject? = null
        override suspend fun searchByName(name: String): JsonObject? = null

        override suspend fun searchRecordingsByIsrc(isrc: String): JsonObject? {
            calls++
            gate?.await()
            if (failing) return null
            if (isrc != this.isrc) return json("""{"recordings":[]}""")
            return json(
                """{"recordings":[{"id":"r","releases":[{"id":"x","title":"$title",
                "release-group":{"id":"$groupId","title":"$title","primary-type":"Album","secondary-types":[]}}]}]}""",
            )
        }

        override suspend fun searchReleaseGroups(artist: String, album: String): JsonObject? {
            calls++
            if (failing) return null
            if (!album.equals(title, ignoreCase = true)) return json("""{"release-groups":[]}""")
            return json(
                """{"release-groups":[{"id":"$groupId","score":100,"title":"$title","count":2,
                "artist-credit":[{"name":"${this.artist}","joinphrase":""}]}]}""",
            )
        }

        override suspend fun searchReleases(artist: String, album: String): JsonObject? {
            calls++
            if (failing) return null
            return json("""{"releases":[]}""")
        }

        override suspend fun browseReleases(releaseGroupId: String): JsonObject? {
            calls++
            browses++
            if (failing) return null
            val items = releases.joinToString(",") { (id, format) ->
                val rel = discogsReleases[id]?.let { """{"type":"discogs","url":{"resource":"https://www.discogs.com/release/$it"}}""" }
                """{"id":"$id","media":[{"format":"${format.replace("\"", "\\\"")}"}],"relations":[${rel.orEmpty()}]}"""
            }
            return json("""{"releases":[$items]}""")
        }

        override suspend fun releaseGroupUrlRels(releaseGroupId: String): JsonObject? {
            calls++
            if (failing) return null
            val rel = discogsMaster?.let { """{"type":"discogs","url":{"resource":"https://www.discogs.com/master/$it"}}""" }
            return json("""{"id":"$releaseGroupId","relations":[${rel.orEmpty()}]}""")
        }

        override suspend fun releaseUrlRels(releaseId: String): JsonObject? = null
    }

    /** One Discogs master whose most-collected vinyl is [release]; [joins] is what its thumbnails' seam says. */
    private class FakeDiscogs(
        val release: DiscogsRelease? = null,
        val joins: Boolean = false,
    ) : DiscogsClient {
        val asked = mutableListOf<String>()
        var failing = false

        override suspend fun versions(masterId: Long, format: String): List<DiscogsVersion>? {
            asked += "versions:$masterId:$format"
            if (failing) return null
            val r = release ?: return emptyList()
            return if (format == "Vinyl") listOf(DiscogsVersion(r.id, listOf("Vinyl"), accepted = true, inCollection = 9)) else emptyList()
        }

        override suspend fun release(id: Long): DiscogsRelease? {
            asked += "release:$id"
            return if (failing) null else release?.takeIf { it.id == id }
        }

        override suspend fun pixels(url: String): Pixels? {
            asked += "pixels:$url"
            if (failing) return null
            // Two flat images: the same color joins, black beside white doesn't.
            val color = if (joins || url.endsWith("1.jpeg")) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            return Pixels(4, 4, IntArray(16) { color })
        }
    }

    /** Listings by release id; the group's cover comes from [canonical]. */
    private class FakeCoverArt(
        val canonical: String? = "cd-1",
        val listings: Map<String, CaaListing?> = emptyMap(),
        /** Releases whose listing never comes: only a cancellation ends the wait. */
        val hanging: Set<String> = emptySet(),
    ) : CoverArtArchiveClient {
        val asked = mutableListOf<String>()
        var failing = false
        var groupFailing = false

        override suspend fun release(mbid: String): CaaListing? {
            asked += mbid
            if (mbid in hanging) awaitCancellation()
            return if (failing) null else listings[mbid] ?: CaaListing.NONE
        }

        override suspend fun releaseGroup(mbid: String): CaaListing? {
            asked += "group:$mbid"
            if (failing || groupFailing) return null
            return if (canonical == null) CaaListing.NONE else CaaListing(listOf(front()), canonical)
        }
    }

    private companion object {
        fun front() = CaaImage(listOf("Front"), front = true, back = false, url = "https://caa/front-1200.jpg")
        fun back(url: String) = CaaImage(listOf("Back"), front = false, back = true, url = url)
        fun page(url: String, type: String = "Booklet") = CaaImage(listOf(type), front = false, back = false, url = url)
        fun listing(vararg images: CaaImage) = CaaListing(images.toList(), null)
        val NEVERMIND = BackCoverAlbum("Nirvana", "Nevermind", "USGF19942501")

        fun dimage(n: Int, w: Int = 600, h: Int = 600) =
            DiscogsImage("https://i.discogs.com/$n.jpeg", "https://i.discogs.com/t$n.jpeg", w, h)

        /** A vinyl on Discogs: front, back (or the inside's two halves), labels, inner sleeve. */
        fun vinyl(images: Int = 6, gatefold: Boolean = false) = DiscogsRelease(
            id = 77,
            images = (0 until images).map { dimage(it) },
            formats = listOf("Vinyl"),
            formatText = if (gatefold) "Gatefold" else "",
            page = "https://www.discogs.com/release/77-Nirvana-Nevermind",
        )
    }

    // After MATCHING_CHANGED_AT, as a real clock is: a partial answer from before it is asked again.
    private var clock = AlbumBackCoverRepository.MATCHING_CHANGED_AT + 1_000_000L

    private fun repo(
        dao: FakeDao = FakeDao(),
        mb: FakeMusicBrainz = FakeMusicBrainz(),
        caa: FakeCoverArt,
        discogs: FakeDiscogs = FakeDiscogs(),
    ) = AlbumBackCoverRepository(dao, mb, caa, discogs).also { it.now = { clock } }

    @Test fun `finds the back and stores it for every song on the album`() = runTest {
        val dao = FakeDao()
        val caa = FakeCoverArt(listings = mapOf("vinyl-1" to listing(front(), back("https://caa/vinyl-back-1200.jpg"))))
        val repo = repo(dao, caa = caa)

        assertThat(repo.resolve(NEVERMIND)?.back).isEqualTo("https://caa/vinyl-back-1200.jpg")
        assertThat(repo.observe(NEVERMIND.copy(isrc = "OTHER")).first()?.back).isEqualTo("https://caa/vinyl-back-1200.jpg")
        val row = dao.get(NEVERMIND.key)!!
        assertThat(row.releaseMbid).isEqualTo("vinyl-1")
        assertThat(row.attemptedAt).isEqualTo(clock)
        assertThat(row.insideUrls).isEmpty() // looked for, none
    }

    @Test fun `the main edition's back and pages end the lookup without asking about other releases`() = runTest {
        val mb = FakeMusicBrainz()
        val caa = FakeCoverArt(
            canonical = "cd-1",
            listings = mapOf(
                "cd-1" to listing(front(), back("https://caa/main-back.jpg"), page("https://caa/p1.jpg"), page("https://caa/p2.jpg")),
                "vinyl-1" to listing(back("https://caa/vinyl-back.jpg")),
            ),
        )
        val art = repo(mb = mb, caa = caa).resolve(NEVERMIND)!!
        assertThat(art.back).isEqualTo("https://caa/main-back.jpg")
        assertThat(art.inside).containsExactly("https://caa/p1.jpg", "https://caa/p2.jpg").inOrder()
        assertThat(caa.asked).containsExactly("group:rg-1", "cd-1").inOrder()
    }

    @Test fun `vinyl is checked before CD after the main edition`() = runTest {
        val caa = FakeCoverArt(
            canonical = "other",
            listings = mapOf(
                "cd-1" to listing(back("https://caa/cd-back.jpg")),
                "vinyl-1" to listing(back("https://caa/vinyl-back.jpg")),
            ),
        )
        assertThat(repo(caa = caa).resolve(NEVERMIND)?.back).isEqualTo("https://caa/vinyl-back.jpg")
        assertThat(caa.asked.take(3)).containsExactly("group:rg-1", "other", "vinyl-1").inOrder()
    }

    @Test fun `the pages come from the first release that has them, when the back's has none`() = runTest {
        val mb = FakeMusicBrainz(releases = listOf("vinyl-1" to "12\" Vinyl", "cd-2" to "CD"))
        val caa = FakeCoverArt(
            canonical = "cd-1",
            listings = mapOf(
                "cd-1" to listing(front(), back("https://caa/main-back.jpg")),
                "cd-2" to listing(
                    page("https://caa/booklet-1.jpg"),
                    CaaImage(listOf("Booklet", "Raw/Unedited"), front = false, back = false, url = "https://caa/raw.jpg"),
                    page("https://caa/disc.jpg", type = "Medium"),
                    page("https://caa/booklet-2.jpg"),
                ),
            ),
        )
        val art = repo(mb = mb, caa = caa).resolve(NEVERMIND)!!
        assertThat(art.back).isEqualTo("https://caa/main-back.jpg")
        assertThat(art.inside).containsExactly("https://caa/booklet-1.jpg", "https://caa/booklet-2.jpg").inOrder()
    }

    @Test fun `the back is stored the moment it is found, while the walk goes on for the pages`() = runTest {
        val dao = FakeDao()
        val caa = FakeCoverArt(
            canonical = "other",
            listings = mapOf("vinyl-1" to listing(back("https://caa/vinyl.jpg"))),
            hanging = setOf("cd-1"), // the walk for the pages never ends
        )
        val repo = repo(dao, caa = caa)
        val walk = launch { repo.resolve(NEVERMIND) }
        advanceUntilIdle()

        assertThat(repo.observe(NEVERMIND).first()?.back).isEqualTo("https://caa/vinyl.jpg")
        assertThat(dao.get(NEVERMIND.key)!!.insideUrls).isNull() // not looked for to the end yet
        walk.cancel()
    }

    @Test fun `a row whose inside was never looked for is walked again, its back kept meanwhile`() = runTest {
        val dao = FakeDao()
        dao.upsert(AlbumBackCoverEntity(NEVERMIND.key, "https://caa/old-back.jpg", "cd-1", attemptedAt = clock, insideUrls = null))
        val mb = FakeMusicBrainz()
        val caa = FakeCoverArt(listings = mapOf("cd-1" to listing(back("https://caa/old-back.jpg"), page("https://caa/p.jpg"))))

        val art = repo(dao, mb, caa).resolve(NEVERMIND)!!

        assertThat(mb.calls).isGreaterThan(0)
        assertThat(art.inside).containsExactly("https://caa/p.jpg")
        assertThat(dao.get(NEVERMIND.key)!!.insideUrls).isEqualTo("https://caa/p.jpg")
    }

    @Test fun `a full answer is not looked up again`() = runTest {
        val mb = FakeMusicBrainz()
        val caa = FakeCoverArt(listings = mapOf("cd-1" to listing(back("https://caa/b.jpg"), page("https://caa/p.jpg"))))
        val repo = repo(mb = mb, caa = caa)
        repo.resolve(NEVERMIND)
        val calls = mb.calls
        val asked = caa.asked.size

        clock += AlbumBackCoverRepository.MISS_TTL_MS * 10
        assertThat(repo.resolve(NEVERMIND)?.back).isEqualTo("https://caa/b.jpg")
        assertThat(mb.calls).isEqualTo(calls)
        assertThat(caa.asked).hasSize(asked)
    }

    @Test fun `a partial answer is kept, then looked up again once it is old`() = runTest {
        val dao = FakeDao()
        val mb = FakeMusicBrainz()
        val caa = FakeCoverArt(listings = mapOf("vinyl-1" to listing(back("https://caa/b.jpg")))) // nothing inside
        val repo = repo(dao, mb, caa)

        assertThat(repo.resolve(NEVERMIND)?.back).isEqualTo("https://caa/b.jpg")
        val calls = mb.calls

        clock += AlbumBackCoverRepository.MISS_TTL_MS - 1
        assertThat(repo.resolve(NEVERMIND)?.inside).isEmpty()
        assertThat(mb.calls).isEqualTo(calls)

        clock += 2
        repo.resolve(NEVERMIND)
        assertThat(mb.calls).isGreaterThan(calls)
    }

    @Test fun `a miss is stored, then looked up again once it is old`() = runTest {
        val dao = FakeDao()
        val mb = FakeMusicBrainz()
        val caa = FakeCoverArt() // no release has a back or pages
        val repo = repo(dao, mb, caa)

        assertThat(repo.resolve(NEVERMIND)).isEqualTo(AlbumCoverArt(null, emptyList()))
        assertThat(dao.get(NEVERMIND.key)!!.imageUrl).isNull()
        val calls = mb.calls

        clock += AlbumBackCoverRepository.MISS_TTL_MS - 1
        assertThat(repo.resolve(NEVERMIND)?.back).isNull()
        assertThat(mb.calls).isEqualTo(calls)

        clock += 2
        repo.resolve(NEVERMIND)
        assertThat(mb.calls).isGreaterThan(calls)
    }

    @Test fun `a release group with no art at all ends the lookup`() = runTest {
        val dao = FakeDao()
        val caa = FakeCoverArt(canonical = null)
        assertThat(repo(dao, caa = caa).resolve(NEVERMIND)?.back).isNull()
        assertThat(caa.asked).containsExactly("group:rg-1")
        assertThat(dao.get(NEVERMIND.key)).isNotNull()
    }

    @Test fun `a failure stores nothing and waits before the next try`() = runTest {
        val dao = FakeDao()
        val mb = FakeMusicBrainz().apply { failing = true }
        val repo = repo(dao, mb, FakeCoverArt())

        assertThat(repo.resolve(NEVERMIND)).isNull()
        assertThat(dao.get(NEVERMIND.key)).isNull()
        val calls = mb.calls

        clock += AlbumBackCoverRepository.FAILURE_BACKOFF_MS - 1
        repo.resolve(NEVERMIND)
        assertThat(mb.calls).isEqualTo(calls)

        clock += 2
        mb.failing = false
        repo.resolve(NEVERMIND)
        assertThat(mb.calls).isGreaterThan(calls)
        assertThat(dao.get(NEVERMIND.key)).isNotNull()
    }

    @Test fun `a miss stored before the matching last changed is looked up again`() = runTest {
        val dao = FakeDao()
        dao.upsert(
            com.stash.core.data.db.entity.AlbumBackCoverEntity(
                NEVERMIND.key, imageUrl = null, attemptedAt = AlbumBackCoverRepository.MATCHING_CHANGED_AT - 1, insideUrls = "",
            ),
        )
        val caa = FakeCoverArt(listings = mapOf("vinyl-1" to listing(front(), back("https://caa/b.jpg"))))
        assertThat(repo(dao, caa = caa).resolve(NEVERMIND)?.back).isEqualTo("https://caa/b.jpg")
    }

    @Test fun `the group's listing failing still checks the releases`() = runTest {
        val caa = FakeCoverArt(listings = mapOf("vinyl-1" to listing(front(), back("https://caa/b.jpg")))).apply { groupFailing = true }
        assertThat(repo(caa = caa).resolve(NEVERMIND)?.back).isEqualTo("https://caa/b.jpg")
    }

    @Test fun `the archive failing on every release is a failure, not a miss`() = runTest {
        val dao = FakeDao()
        val caa = object : CoverArtArchiveClient {
            override suspend fun release(mbid: String): CaaListing? = null
            override suspend fun releaseGroup(mbid: String): CaaListing? = CaaListing(listOf(front()), "cd-1")
        }
        assertThat(AlbumBackCoverRepository(dao, FakeMusicBrainz(), caa, FakeDiscogs()).also { it.now = { clock } }.resolve(NEVERMIND)).isNull()
        assertThat(dao.get(NEVERMIND.key)).isNull()
    }

    @Test fun `without an ISRC the album is found by its title, edition and all`() = runTest {
        val caa = FakeCoverArt(listings = mapOf("vinyl-1" to listing(back("https://caa/b.jpg"))))
        val album = BackCoverAlbum("Nirvana", "Nevermind (Super Deluxe Edition)", isrc = null)
        assertThat(repo(caa = caa).resolve(album)?.back).isEqualTo("https://caa/b.jpg")
    }

    @Test fun `an ISRC from another album falls back to the title`() = runTest {
        val caa = FakeCoverArt(listings = mapOf("vinyl-1" to listing(back("https://caa/b.jpg"))))
        assertThat(repo(caa = caa).resolve(NEVERMIND.copy(isrc = "UNKNOWN"))?.back).isEqualTo("https://caa/b.jpg")
    }

    @Test fun `an album MusicBrainz doesn't know is a miss`() = runTest {
        val dao = FakeDao()
        val album = BackCoverAlbum("Someone", "Bedroom Demos", isrc = null)
        assertThat(repo(dao, caa = FakeCoverArt()).resolve(album)?.back).isNull()
        assertThat(dao.get(album.key)).isNotNull()
    }

    @Test fun `two callers for one album run one lookup`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val mb = FakeMusicBrainz().apply { this.gate = gate }
        val caa = FakeCoverArt(listings = mapOf("vinyl-1" to listing(back("https://caa/b.jpg"))))
        val repo = repo(mb = mb, caa = caa)

        val first = async { repo.resolve(NEVERMIND) }
        val second = async { repo.resolve(NEVERMIND) }
        yield()
        gate.complete(Unit)

        assertThat(listOf(first, second).awaitAll().map { it?.back }).containsExactly("https://caa/b.jpg", "https://caa/b.jpg")
        assertThat(caa.asked.count { it.startsWith("group:") }).isEqualTo(1)
    }

    @Test fun `once the back and pages are in, slower listings are cancelled`() = runTest {
        val caa = FakeCoverArt(
            canonical = "other",
            listings = mapOf("vinyl-1" to listing(back("https://caa/vinyl.jpg"), page("https://caa/p.jpg"))),
            hanging = setOf("cd-1"),
        )
        // Hangs (and the test times out) unless the CD's listing is cancelled once the vinyl's are in.
        assertThat(repo(caa = caa).resolve(NEVERMIND)?.back).isEqualTo("https://caa/vinyl.jpg")
    }

    @Test fun `a reply that can't be read is a failed lookup, not a crash`() = runTest {
        val dao = FakeDao()
        val garbled = object : MusicBrainzClient by FakeMusicBrainz() {
            override suspend fun searchRecordingsByIsrc(isrc: String): JsonObject? =
                Json.parseToJsonElement("""{"recordings":"not a list"}""").jsonObject
        }
        val repo = AlbumBackCoverRepository(dao, garbled, FakeCoverArt(), FakeDiscogs()).also { it.now = { clock } }
        assertThat(repo.resolve(NEVERMIND)).isNull()
        assertThat(dao.get(NEVERMIND.key)).isNull()
    }

    @Test fun `the plain title is searched first`() = runTest {
        val searched = mutableListOf<String>()
        val mb = object : MusicBrainzClient by FakeMusicBrainz() {
            override suspend fun searchReleaseGroups(artist: String, album: String): JsonObject? {
                searched += album
                return Json.parseToJsonElement(
                    """{"release-groups":[{"id":"rg-1","score":100,"title":"Abbey Road","count":73,
                    "artist-credit":[{"name":"The Beatles","joinphrase":""}]}]}""",
                ).jsonObject
            }
        }
        val caa = FakeCoverArt(canonical = "cd-1", listings = mapOf("cd-1" to listing(back("https://caa/ar.jpg"), page("https://caa/p.jpg"))))
        val repo = AlbumBackCoverRepository(FakeDao(), mb, caa, FakeDiscogs()).also { it.now = { clock } }
        assertThat(repo.resolve(BackCoverAlbum("The Beatles", "Abbey Road (Remastered)", isrc = null))?.back).isEqualTo("https://caa/ar.jpg")
        assertThat(searched).containsExactly("Abbey Road")
    }

    @Test fun `with no back on the archive, Discogs gives one, credited`() = runTest {
        val dao = FakeDao()
        val mb = FakeMusicBrainz(discogsMaster = 13814)
        val caa = FakeCoverArt(listings = mapOf("cd-1" to listing(front(), page("https://caa/p1.jpg"))))
        val discogs = FakeDiscogs(vinyl())

        val art = repo(dao, mb, caa, discogs).resolve(NEVERMIND)!!

        assertThat(art.back).isEqualTo("https://i.discogs.com/1.jpeg")
        assertThat(art.backFromDiscogs).isTrue()
        assertThat(art.inside).containsExactly("https://caa/p1.jpg") // the archive's, kept
        assertThat(art.insideFromDiscogs).isFalse()
        assertThat(art.discogsPage).isEqualTo("https://www.discogs.com/release/77-Nirvana-Nevermind")
        assertThat(discogs.asked).containsAtLeast("versions:13814:Vinyl", "release:77").inOrder()
        val row = dao.get(NEVERMIND.key)!!
        assertThat(row.releaseMbid).isNull()
        assertThat(row.backFromDiscogs).isTrue()
    }

    @Test fun `with no Discogs link on the album, a release's link finds it`() = runTest {
        // On Top (Four Tops): the group links no master; its vinyl links its Discogs release.
        val mb = FakeMusicBrainz(discogsReleases = mapOf("vinyl-1" to 77L))
        val caa = FakeCoverArt(listings = mapOf("cd-1" to listing(front())))
        val discogs = FakeDiscogs(vinyl())

        val art = repo(mb = mb, caa = caa, discogs = discogs).resolve(NEVERMIND)!!

        assertThat(art.back).isEqualTo("https://i.discogs.com/1.jpeg")
        assertThat(art.backFromDiscogs).isTrue()
        assertThat(art.inside).isNotEmpty()
        assertThat(art.discogsPage).isNotNull()
    }

    @Test fun `what came from Discogs is forgotten after six hours, the archive's part kept, and looked up again`() = runTest {
        val dao = FakeDao()
        val mb = FakeMusicBrainz(discogsMaster = 13814)
        val caa = FakeCoverArt(listings = mapOf("cd-1" to listing(front(), page("https://caa/p1.jpg"))))
        val discogs = FakeDiscogs(vinyl())
        val repo = repo(dao, mb, caa, discogs)
        repo.resolve(NEVERMIND)

        clock += AlbumBackCoverRepository.DISCOGS_TTL_MS - 1
        assertThat(repo.observe(NEVERMIND).first()!!.backFromDiscogs).isTrue()
        val asked = discogs.asked.size
        assertThat(repo.resolve(NEVERMIND)!!.back).isEqualTo("https://i.discogs.com/1.jpeg")
        assertThat(discogs.asked).hasSize(asked)

        clock += 2
        val seen = repo.observe(NEVERMIND).first()!!
        assertThat(seen.back).isNull()
        assertThat(seen.discogsPage).isNull()
        assertThat(seen.inside).containsExactly("https://caa/p1.jpg")
        assertThat(repo.resolve(NEVERMIND)!!.backFromDiscogs).isTrue()
        assertThat(discogs.asked.size).isGreaterThan(asked)
    }

    @Test fun `with a back but nothing inside on the archive, Discogs gives the inside, its own back left out`() = runTest {
        val mb = FakeMusicBrainz(discogsMaster = 13814)
        val caa = FakeCoverArt(listings = mapOf("cd-1" to listing(front(), back("https://caa/back.jpg"))))
        val art = repo(mb = mb, caa = caa, discogs = FakeDiscogs(vinyl(images = 4))).resolve(NEVERMIND)!!

        assertThat(art.back).isEqualTo("https://caa/back.jpg")
        assertThat(art.backFromDiscogs).isFalse()
        assertThat(art.inside).containsExactly("https://i.discogs.com/2.jpeg", "https://i.discogs.com/3.jpeg").inOrder()
        assertThat(art.insideFromDiscogs).isTrue()
        assertThat(art.discogsPage).isNotNull()
    }

    @Test fun `halves that join put the Discogs back after them`() = runTest {
        val mb = FakeMusicBrainz(discogsMaster = 1)
        val discogs = FakeDiscogs(vinyl(), joins = true)
        val art = repo(mb = mb, caa = FakeCoverArt(canonical = null), discogs = discogs).resolve(NEVERMIND)!!
        assertThat(art.back).isEqualTo("https://i.discogs.com/3.jpeg")
        assertThat(discogs.asked).containsAtLeast("pixels:https://i.discogs.com/t1.jpeg", "pixels:https://i.discogs.com/t2.jpeg")
    }

    @Test fun `a gatefold the format names needs no thumbnails`() = runTest {
        val discogs = FakeDiscogs(vinyl(gatefold = true))
        val art = repo(mb = FakeMusicBrainz(discogsMaster = 1), caa = FakeCoverArt(canonical = null), discogs = discogs)
            .resolve(NEVERMIND)!!
        assertThat(art.back).isEqualTo("https://i.discogs.com/3.jpeg")
        assertThat(discogs.asked.none { it.startsWith("pixels:") }).isTrue()
    }

    @Test fun `an album with no art on the archive gets Discogs' back and inside`() = runTest {
        val caa = FakeCoverArt(canonical = null)
        val art = repo(mb = FakeMusicBrainz(discogsMaster = 1), caa = caa, discogs = FakeDiscogs(vinyl())).resolve(NEVERMIND)!!
        assertThat(art.back).isEqualTo("https://i.discogs.com/1.jpeg")
        assertThat(art.inside).hasSize(4)
        assertThat(art.backFromDiscogs).isTrue()
        assertThat(art.insideFromDiscogs).isTrue()
        assertThat(caa.asked).containsExactly("group:rg-1")
    }

    @Test fun `a master with no vinyl is read from its most-collected CD`() = runTest {
        val cd = vinyl().copy(formats = listOf("CD"))
        val discogs = object : DiscogsClient by FakeDiscogs(cd) {
            override suspend fun versions(masterId: Long, format: String): List<DiscogsVersion>? =
                if (format == "CD") listOf(DiscogsVersion(77, listOf("CD"), accepted = true, inCollection = 3)) else emptyList()
        }
        val repo = AlbumBackCoverRepository(FakeDao(), FakeMusicBrainz(discogsMaster = 1), FakeCoverArt(canonical = null), discogs)
            .also { it.now = { clock } }
        assertThat(repo.resolve(NEVERMIND)?.back).isEqualTo("https://i.discogs.com/1.jpeg")
    }

    @Test fun `a full answer from the archive never asks Discogs`() = runTest {
        val mb = FakeMusicBrainz(discogsMaster = 1)
        val discogs = FakeDiscogs(vinyl())
        val caa = FakeCoverArt(listings = mapOf("cd-1" to listing(back("https://caa/b.jpg"), page("https://caa/p.jpg"))))
        val art = repo(mb = mb, caa = caa, discogs = discogs).resolve(NEVERMIND)!!
        assertThat(art.backFromDiscogs || art.insideFromDiscogs).isFalse()
        assertThat(art.discogsPage).isNull()
        assertThat(discogs.asked).isEmpty()
    }

    @Test fun `no Discogs link leaves the archive's partial answer`() = runTest {
        val discogs = FakeDiscogs(vinyl())
        val caa = FakeCoverArt(listings = mapOf("vinyl-1" to listing(back("https://caa/b.jpg"))))
        val art = repo(caa = caa, discogs = discogs).resolve(NEVERMIND)!!
        assertThat(art).isEqualTo(AlbumCoverArt("https://caa/b.jpg", emptyList()))
        assertThat(discogs.asked).isEmpty()
    }

    @Test fun `Discogs failing is a failed lookup, the archive's back kept meanwhile`() = runTest {
        val dao = FakeDao()
        val discogs = FakeDiscogs(vinyl()).apply { failing = true }
        val caa = FakeCoverArt(listings = mapOf("vinyl-1" to listing(back("https://caa/b.jpg"))))
        val repo = repo(dao, FakeMusicBrainz(discogsMaster = 1), caa, discogs)

        assertThat(repo.resolve(NEVERMIND)).isNull()
        val row = dao.get(NEVERMIND.key)!!
        assertThat(row.imageUrl).isEqualTo("https://caa/b.jpg")
        assertThat(row.insideUrls).isNull() // the inside is looked for again
        assertThat(repo.observe(NEVERMIND).first()?.back).isEqualTo("https://caa/b.jpg")

        clock += AlbumBackCoverRepository.FAILURE_BACKOFF_MS + 1
        discogs.failing = false
        assertThat(repo.resolve(NEVERMIND)?.insideFromDiscogs).isTrue()
    }

    @Test fun `an answer with Discogs' art is looked up again after six hours`() = runTest {
        val mb = FakeMusicBrainz(discogsMaster = 1)
        val repo = repo(mb = mb, caa = FakeCoverArt(canonical = null), discogs = FakeDiscogs(vinyl()))
        repo.resolve(NEVERMIND)
        val calls = mb.calls

        clock += AlbumBackCoverRepository.DISCOGS_TTL_MS - 1
        assertThat(repo.resolve(NEVERMIND)?.backFromDiscogs).isTrue()
        assertThat(mb.calls).isEqualTo(calls)

        clock += 2
        repo.resolve(NEVERMIND)
        assertThat(mb.calls).isGreaterThan(calls)
    }
}
