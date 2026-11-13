package com.stash.data.download.lossless.addon

import com.google.common.truth.Truth.assertThat
import com.stash.addons.contract.AddonKind
import com.stash.addons.contract.Artwork
import com.stash.addons.contract.Delivery
import com.stash.addons.contract.ErrorCode
import com.stash.addons.contract.Manifest
import com.stash.addons.contract.SearchResponse
import com.stash.addons.contract.StreamCapabilities
import com.stash.addons.contract.StreamInfo
import com.stash.addons.contract.StreamRequest
import com.stash.addons.contract.Tier
import com.stash.addons.contract.Track
import com.stash.core.addons.AddonDialect
import com.stash.core.addons.AddonFailure
import com.stash.core.addons.AddonResult
import com.stash.core.addons.EclipseTraits
import com.stash.core.addons.HeldStream
import com.stash.core.addons.HttpAddonClient
import com.stash.core.addons.InstalledAddon
import com.stash.core.addons.MediaRefusal
import com.stash.core.data.db.dao.AddonRefDao
import com.stash.core.data.db.entity.AddonRefEntity
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.tidal.TidalClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * [AddonRefs] (add-ons plan phase 4 §2.4): a ref before search, miss stamps that hold, `not_found`
 * dropping the ref, [AddonRefs.forget] clearing one add-on's refs, and the held answer of an
 * out-of-range lossless reply reaching the lossy pass of the same play. And what review-882 asked
 * of it: no search for a download from an add-on the user added (J3), no match on words alone and
 * an Eclipse `/resolve` item checked (J4), an unlabelled lossy answer (J5), and failures that are
 * not misses (M3).
 */
class AddonRefsTest {

    /** Rows keyed by add-on and song, as the table is. */
    private class Dao : AddonRefDao {
        val rows = ConcurrentHashMap<Pair<String, String>, AddonRefEntity>()
        override suspend fun get(addonId: String, songKey: String) = rows[addonId to songKey]
        override suspend fun upsert(entity: AddonRefEntity) { rows[entity.addonId to entity.songKey] = entity }
        override suspend fun delete(addonId: String, songKey: String) = if (rows.remove(addonId to songKey) != null) 1 else 0
        override suspend fun deleteFor(addonId: String): Int {
            val gone = rows.keys.filter { it.first == addonId }
            gone.forEach { rows.remove(it) }
            return gone.size
        }
        override suspend fun deleteOlderThan(addonId: String, before: Long) = 0
    }

    private val dao = Dao()
    private var now = 1_000_000_000_000L

    private fun refs(client: HttpAddonClient) = AddonRefs(client, dao).also { it.nowMs = { now } }

    private fun addon(
        id: String = "bc",
        resources: List<String> = listOf("search", "stream"),
        dialect: AddonDialect = AddonDialect.V1,
        eclipse: EclipseTraits? = null,
    ) = InstalledAddon(
        base = "https://addon.example/",
        manifest = Manifest(
            stash = 1, id = id, name = id, version = "1", kind = AddonKind.SERVER, resources = resources,
            stream = StreamCapabilities(cors = false, manifests = listOf("none")),
        ),
        userAdded = true,
        dialect = dialect,
        eclipse = eclipse,
    )

    private val song = TrackQuery(artist = "Daft Punk", title = "Digital Love", durationMs = 301_000L, isrc = "GBDUW0000059")
    private val key = TidalClient.cacheKey(song)

    private val exact = Track(id = "42", title = "Digital Love", artist = "Daft Punk", durationMs = 301_000L, isrc = "GBDUW0000059")
    private val cover = Track(id = "77", title = "Digital Love", artist = "Some Covers Band", durationMs = 240_000L)

    private val flac = StreamInfo(url = "https://cdn.example/a.flac", manifest = Delivery.NONE, tier = Tier.LOSSLESS, cors = false)
    private val mp3 = StreamInfo(url = "https://cdn.example/a.mp3", manifest = Delivery.NONE, tier = Tier.HIGH, cors = false)

    private val losslessReq = StreamRequest(Tier.HIRES, Tier.LOSSLESS, listOf(Delivery.NONE, Delivery.DASH))
    private val highReq = StreamRequest(Tier.HIGH, Tier.HIGH, listOf(Delivery.NONE, Delivery.DASH))

    private fun searching(vararg rows: Track): HttpAddonClient = mockk {
        coEvery { search(any(), any(), any(), any()) } returns AddonResult.Ok(SearchResponse(tracks = rows.toList()))
        coEvery { stream(any(), any(), any(), any()) } returns AddonResult.Ok(flac)
    }

    /** The same words and length as [exact], another recording (no ISRC): on words and length it scores 1.0, above an ISRC match's 0.95. */
    private val twin = Track(id = "41", title = "Digital Love", artist = "Daft Punk", durationMs = 301_000L)

    @Test fun `a search is matched on the phone, the row with the song's ISRC first, and kept`() = runTest {
        // The twin is listed first and outscores the ISRC row: only taking the ISRC rows first picks 42.
        val client = searching(twin, cover, exact)
        val found = refs(client).find(addon(), song, AddonRefs.Pass.LOSSLESS)
        assertThat((found as AddonRefs.Found.Hit).ref.remoteId).isEqualTo("42")
        assertThat(found.fresh).isTrue()
        val row = dao.rows["bc" to key]!!
        assertThat(row.remoteId).isEqualTo("42")
        assertThat(row.confidence).isAtLeast(0.9f)
        coVerify(exactly = 1) { client.search(any(), "Daft Punk Digital Love", any(), any()) }
    }

    @Test fun `without the song's ISRC among the rows, the best row on words and length is taken`() = runTest {
        val found = refs(searching(cover, twin)).find(addon(), song, AddonRefs.Pass.LOSSLESS)
        assertThat((found as AddonRefs.Found.Hit).ref.remoteId).isEqualTo("41")
    }

    @Test fun `a ref is used before search`() = runTest {
        val client = searching(exact)
        val refs = refs(client)
        refs.find(addon(), song, AddonRefs.Pass.LOSSLESS)
        now += 86_400_000L
        val again = refs.find(addon(), song, AddonRefs.Pass.LOSSLESS)
        assertThat((again as AddonRefs.Found.Hit).ref.remoteId).isEqualTo("42")
        coVerify(exactly = 1) { client.search(any(), any(), any(), any()) }
        // And past 30 days, the add-on is asked again.
        now += TidalClient.MATCH_TTL_MS
        refs.find(addon(), song, AddonRefs.Pass.LOSSLESS)
        coVerify(exactly = 2) { client.search(any(), any(), any(), any()) }
    }

    @Test fun `no confident row is a miss kept for 14 days, for every pass`() = runTest {
        val client = searching(cover)
        val refs = refs(client)
        assertThat(refs.find(addon(), song, AddonRefs.Pass.LOSSLESS)).isEqualTo(AddonRefs.Found.Miss)
        assertThat(refs.find(addon(), song, AddonRefs.Pass.LOSSY)).isEqualTo(AddonRefs.Found.Miss)
        coVerify(exactly = 1) { client.search(any(), any(), any(), any()) }
        now += TidalClient.MISS_TTL_MS
        refs.find(addon(), song, AddonRefs.Pass.LOSSY)
        coVerify(exactly = 2) { client.search(any(), any(), any(), any()) }
    }

    @Test fun `an outage is never a miss`() = runTest {
        val client: HttpAddonClient = mockk {
            coEvery { search(any(), any(), any(), any()) } returns AddonResult.Failed(AddonFailure.Timeout)
        }
        val refs = refs(client)
        assertThat(refs.find(addon(), song, AddonRefs.Pass.LOSSLESS)).isEqualTo(AddonRefs.Found.Unavailable)
        assertThat(dao.rows).isEmpty()
        refs.find(addon(), song, AddonRefs.Pass.LOSSLESS)
        coVerify(exactly = 2) { client.search(any(), any(), any(), any()) }
    }

    @Test fun `a resolve miss at the lossless floor holds for the lossless pass only`() = runTest {
        val client: HttpAddonClient = mockk {
            coEvery { resolve(any(), match { it.minTier == Tier.LOSSLESS }, any()) } returns AddonResult.Ok(null)
            coEvery { resolve(any(), match { it.minTier == null }, any()) } returns AddonResult.Ok(exact)
        }
        val refs = refs(client)
        val a = addon(resources = listOf("resolve", "stream"))
        assertThat(refs.find(a, song, AddonRefs.Pass.LOSSLESS)).isEqualTo(AddonRefs.Found.Miss)
        assertThat(refs.find(a, song, AddonRefs.Pass.LOSSLESS)).isEqualTo(AddonRefs.Found.Miss)
        coVerify(exactly = 1) { client.resolve(any(), any(), any()) }
        // The lossy pass still asks, with no tier floor, and finds it.
        val lossy = refs.find(a, song, AddonRefs.Pass.LOSSY)
        assertThat((lossy as AddonRefs.Found.Hit).ref.remoteId).isEqualTo("42")
        // And the lossless miss outlives that hit (review-882 N2): the next play's lossless pass
        // is still a miss, with nothing asked.
        assertThat(dao.rows["bc" to key]!!.losslessMissAt).isEqualTo(now)
        assertThat(refs.find(a, song, AddonRefs.Pass.LOSSLESS)).isEqualTo(AddonRefs.Found.Miss)
        coVerify(exactly = 2) { client.resolve(any(), any(), any()) }
    }

    private val notFound = AddonResult.Failed(AddonFailure.Refused(ErrorCode.NOT_FOUND, 404, null, null))

    @Test fun `not_found from the stream drops a kept ref, and the next play looks again`() = runTest {
        val client: HttpAddonClient = mockk {
            coEvery { search(any(), any(), any(), any()) } returns AddonResult.Ok(SearchResponse(tracks = listOf(exact)))
            coEvery { stream(any(), any(), any(), any()) } returns notFound
        }
        // A ref kept by an earlier play: the add-on has since dropped that id.
        dao.upsert(AddonRefEntity("bc", key, remoteId = "old-id", confidence = 0.9f, resolvedAt = now - 86_400_000L))
        val refs = refs(client)
        assertThat(refs.stream(addon(), song, AddonRefs.Pass.LOSSLESS, losslessReq)).isEqualTo(AddonRefs.Streamed.Miss)
        assertThat(dao.rows).isEmpty()
        coVerify(exactly = 0) { client.search(any(), any(), any(), any()) }
        // The next play looks again.
        refs.stream(addon(), song, AddonRefs.Pass.LOSSLESS, losslessReq)
        coVerify(exactly = 1) { client.search(any(), any(), any(), any()) }
    }

    /** review-882 M3: the add-on lists the song and cannot stream it. Asked on every play and sweep, forever. */
    @Test fun `not_found for a ref made a moment ago is remembered for that pass`() = runTest {
        val client: HttpAddonClient = mockk {
            coEvery { search(any(), any(), any(), any()) } returns AddonResult.Ok(SearchResponse(tracks = listOf(exact)))
            coEvery { stream(any(), any(), any(), any()) } returns notFound
        }
        val refs = refs(client)
        assertThat(refs.stream(addon(), song, AddonRefs.Pass.LOSSLESS, losslessReq)).isEqualTo(AddonRefs.Streamed.Miss)
        val row = dao.rows["bc" to key]!!
        assertThat(row.remoteId).isNull()
        assertThat(row.losslessMissAt).isEqualTo(now)
        // The next plays' lossless pass asks nothing, for 14 days.
        assertThat(refs.stream(addon(), song, AddonRefs.Pass.LOSSLESS, losslessReq)).isEqualTo(AddonRefs.Streamed.Miss)
        coVerify(exactly = 1) { client.search(any(), any(), any(), any()) }
        coVerify(exactly = 1) { client.stream(any(), any(), any(), any()) }
        // The lossy pass is its own question, and its answer leaves the lossless one in place.
        refs.stream(addon(), song, AddonRefs.Pass.LOSSY, highReq)
        coVerify(exactly = 2) { client.search(any(), any(), any(), any()) }
        assertThat(dao.rows["bc" to key]!!.losslessMissAt).isEqualTo(now)
        assertThat(dao.rows["bc" to key]!!.aacMissAt).isEqualTo(now)
        now += TidalClient.MISS_TTL_MS
        refs.stream(addon(), song, AddonRefs.Pass.LOSSLESS, losslessReq)
        coVerify(exactly = 3) { client.search(any(), any(), any(), any()) }
    }

    // ------------------------------------------------------------------------------------------ refused on the wire (review-882 round 2, F7)

    @Test fun `a stream the media client refused to fetch loses its ref, and the song is left to the next source`() = runTest {
        val client = searching(exact)
        val refs = refs(client)
        val served = refs.stream(addon(), song, AddonRefs.Pass.LOSSLESS, losslessReq) as AddonRefs.Streamed.Ok

        // Its file redirected to the user's own network: the guard reports the stream's URL.
        refs.refusedOnTheWire(served.info.url, MediaRefusal.URL)

        val row = dao.rows["bc" to key]!!
        assertThat(row.remoteId).isNull()
        assertThat(row.losslessMissAt).isEqualTo(now)
        assertThat(row.aacMissAt).isEqualTo(now)
        // Neither pass offers that add-on's stream for the song again, and nothing is asked.
        assertThat(refs.stream(addon(), song, AddonRefs.Pass.LOSSLESS, losslessReq)).isEqualTo(AddonRefs.Streamed.Miss)
        assertThat(refs.stream(addon(), song, AddonRefs.Pass.LOSSY, highReq)).isEqualTo(AddonRefs.Streamed.Miss)
        coVerify(exactly = 1) { client.search(any(), any(), any(), any()) }
        coVerify(exactly = 1) { client.stream(any(), any(), any(), any()) }
        // For 14 days, as any miss.
        now += TidalClient.MISS_TTL_MS
        refs.stream(addon(), song, AddonRefs.Pass.LOSSLESS, losslessReq)
        coVerify(exactly = 2) { client.search(any(), any(), any(), any()) }
    }

    @Test fun `a refusal for a URL this add-on never answered changes nothing, and one is counted once`() = runTest {
        val refs = refs(searching(exact))
        val served = refs.stream(addon(), song, AddonRefs.Pass.LOSSLESS, losslessReq) as AddonRefs.Streamed.Ok
        refs.refusedOnTheWire("https://elsewhere.example/other.flac", MediaRefusal.URL)
        assertThat(dao.rows["bc" to key]!!.remoteId).isEqualTo("42")

        refs.refusedOnTheWire(served.info.url, MediaRefusal.URL)
        val stamped = dao.rows["bc" to key]!!
        now += 60_000L
        refs.refusedOnTheWire(served.info.url, MediaRefusal.URL)
        assertThat(dao.rows["bc" to key]).isEqualTo(stamped)
    }

    @Test fun `a refusal is matched to its stream in the media client's spelling of the URL`() = runTest {
        val client: HttpAddonClient = mockk {
            coEvery { search(any(), any(), any(), any()) } returns AddonResult.Ok(SearchResponse(tracks = listOf(exact)))
            // As the add-on wrote it: the host in capitals, the default port spelled out.
            coEvery { stream(any(), any(), any(), any()) } returns AddonResult.Ok(flac.copy(url = "https://CDN.Example:443/a.flac"))
        }
        val refs = refs(client)
        assertThat(refs.stream(addon(), song, AddonRefs.Pass.LOSSLESS, losslessReq)).isInstanceOf(AddonRefs.Streamed.Ok::class.java)

        // As OkHttp names the request it refused.
        refs.refusedOnTheWire("https://cdn.example/a.flac", MediaRefusal.URL)

        assertThat(dao.rows["bc" to key]!!.remoteId).isNull()
    }

    // ------------------------------------------------------------------------------------------ refused by this network's DNS (review-882 round 3, R3)

    @Test fun `a refusal by this network's DNS drops the ref and keeps the miss in memory only`() = runTest {
        val client = searching(exact)
        val refs = refs(client)
        val served = refs.stream(addon(), song, AddonRefs.Pass.LOSSLESS, losslessReq) as AddonRefs.Streamed.Ok

        // Every answer for the media host was private: a DNS blocker, a work network's split DNS.
        refs.refusedOnTheWire(served.info.url, MediaRefusal.NO_PUBLIC_ADDRESS)

        // The ref is gone and nothing is written for 14 days.
        assertThat(dao.rows).isEmpty()
        // While the app runs, neither pass offers that add-on's stream for the song, and nothing is asked.
        assertThat(refs.stream(addon(), song, AddonRefs.Pass.LOSSLESS, losslessReq)).isEqualTo(AddonRefs.Streamed.Miss)
        assertThat(refs.stream(addon(), song, AddonRefs.Pass.LOSSY, highReq)).isEqualTo(AddonRefs.Streamed.Miss)
        coVerify(exactly = 1) { client.search(any(), any(), any(), any()) }
        coVerify(exactly = 1) { client.stream(any(), any(), any(), any()) }
        // The next start, perhaps on another network, asks the add-on again.
        val nextStart = refs(client)
        assertThat(nextStart.stream(addon(), song, AddonRefs.Pass.LOSSLESS, losslessReq)).isInstanceOf(AddonRefs.Streamed.Ok::class.java)
        coVerify(exactly = 2) { client.search(any(), any(), any(), any()) }
    }

    @Test fun `a refusal by this network's DNS leaves a miss the row already had`() = runTest {
        val client = searching(exact)
        // A ref the lossy pass uses while the lossless pass missed a day ago (N2).
        dao.upsert(AddonRefEntity("bc", key, remoteId = "42", confidence = 0.9f, resolvedAt = now - 3_600_000L, losslessMissAt = now - 86_400_000L))
        val refs = refs(client)
        val served = refs.stream(addon(), song, AddonRefs.Pass.LOSSY, highReq) as AddonRefs.Streamed.Ok

        refs.refusedOnTheWire(served.info.url, MediaRefusal.NO_PUBLIC_ADDRESS)

        val row = dao.rows["bc" to key]!!
        assertThat(row.remoteId).isNull()
        assertThat(row.losslessMissAt).isEqualTo(now - 86_400_000L)
        assertThat(row.aacMissAt).isNull()
    }

    @Test fun `forgetting an add-on forgets its songs refused by this network's DNS`() = runTest {
        val client = searching(exact)
        val refs = refs(client)
        val served = refs.stream(addon(), song, AddonRefs.Pass.LOSSLESS, losslessReq) as AddonRefs.Streamed.Ok
        refs.refusedOnTheWire(served.info.url, MediaRefusal.NO_PUBLIC_ADDRESS)

        refs.forget("bc")

        assertThat(refs.stream(addon(), song, AddonRefs.Pass.LOSSLESS, losslessReq)).isInstanceOf(AddonRefs.Streamed.Ok::class.java)
    }

    // ------------------------------------------------------------------------------------------ failures that are not misses (review-882 M3)

    @Test fun `a search that answers 404 is not a miss - nothing is remembered against the song`() = runTest {
        // What a path-token server answers for a mistyped or lapsed token, or mid-deploy.
        val client: HttpAddonClient = mockk {
            coEvery { search(any(), any(), any(), any()) } returns notFound
        }
        val refs = refs(client)
        assertThat(refs.find(addon(), song, AddonRefs.Pass.LOSSLESS)).isEqualTo(AddonRefs.Found.Unavailable)
        assertThat(dao.rows).isEmpty()
        refs.find(addon(), song, AddonRefs.Pass.LOSSY)
        coVerify(exactly = 2) { client.search(any(), any(), any(), any()) }
    }

    // ------------------------------------------------------------------------------------------ downloads never search an add-on the user added (review-882 J3)

    @Test fun `a download from an add-on the user added does not search - nothing is asked and nothing remembered`() = runTest {
        val client = searching(exact)
        val refs = refs(client)
        val mine = addon()
        assertThat(AddonRefs.matchesBySearch(mine, download = true)).isFalse()
        val r = refs.stream(mine, song, AddonRefs.Pass.LOSSLESS, losslessReq, paced = true, download = true, bySearch = AddonRefs.matchesBySearch(mine, download = true))
        assertThat(r).isEqualTo(AddonRefs.Streamed.Unavailable)
        coVerify(exactly = 0) { client.search(any(), any(), any(), any()) }
        coVerify(exactly = 0) { client.stream(any(), any(), any(), any()) }
        assertThat(dao.rows).isEmpty()
    }

    @Test fun `a download uses the ref a play made, and a play does search`() = runTest {
        val client = searching(exact)
        val refs = refs(client)
        val mine = addon()
        assertThat(AddonRefs.matchesBySearch(mine, download = false)).isTrue()
        // The play: one search, kept.
        assertThat(refs.stream(mine, song, AddonRefs.Pass.LOSSLESS, losslessReq)).isInstanceOf(AddonRefs.Streamed.Ok::class.java)
        // The download: the ref, no second search.
        val r = refs.stream(mine, song, AddonRefs.Pass.LOSSLESS, losslessReq, paced = true, download = true, bySearch = false)
        assertThat((r as AddonRefs.Streamed.Ok).ref.remoteId).isEqualTo("42")
        coVerify(exactly = 1) { client.search(any(), any(), any(), any()) }
    }

    @Test fun `a download from an add-on the user added still asks its resolve`() = runTest {
        val client: HttpAddonClient = mockk {
            coEvery { resolve(any(), any(), any()) } returns AddonResult.Ok(exact)
            coEvery { stream(any(), "42", any(), any()) } returns AddonResult.Ok(flac)
        }
        val mine = addon(resources = listOf("search", "resolve", "stream"))
        val r = refs(client).stream(mine, song, AddonRefs.Pass.LOSSLESS, losslessReq, paced = true, download = true, bySearch = false)
        assertThat(r).isInstanceOf(AddonRefs.Streamed.Ok::class.java)
        coVerify(exactly = 1) { client.resolve(any(), any(), true) }
        coVerify(exactly = 0) { client.search(any(), any(), any(), any()) }
    }

    @Test fun `a default add-on's download may search`() {
        assertThat(AddonRefs.matchesBySearch(addon().copy(userAdded = false), download = true)).isTrue()
    }

    @Test fun `an ISRC-only resolver's miss on a download is not remembered - the play that may search still does`() = runTest {
        val client: HttpAddonClient = mockk {
            coEvery { resolve(any(), any(), any()) } returns AddonResult.Ok(null)
            coEvery { search(any(), any(), any(), any()) } returns AddonResult.Ok(SearchResponse(tracks = listOf(exact)))
        }
        val refs = refs(client)
        val eclipse = addon(resources = listOf("search", "resolve", "stream"), dialect = AddonDialect.ECLIPSE, eclipse = EclipseTraits(resolveViaIsrc = true))
        // The download: /resolve-isrc misses, and it may not search.
        assertThat(refs.find(eclipse, song, AddonRefs.Pass.LOSSLESS, paced = true, bySearch = false)).isEqualTo(AddonRefs.Found.Unavailable)
        assertThat(dao.rows).isEmpty()
        coVerify(exactly = 0) { client.search(any(), any(), any(), any()) }
        // The play: asks again, and its search finds the song.
        assertThat(refs.find(eclipse, song, AddonRefs.Pass.LOSSLESS)).isInstanceOf(AddonRefs.Found.Hit::class.java)
    }

    /** review-882 round 2, n8: nothing may be stamped for such a miss, so every retry pass asked `/resolve-isrc` again. */
    @Test fun `an ISRC-only resolver's miss on a download is remembered for downloads, in memory - a play still asks and searches`() = runTest {
        val client: HttpAddonClient = mockk {
            coEvery { resolve(any(), any(), any()) } returns AddonResult.Ok(null)
            coEvery { search(any(), any(), any(), any()) } returns AddonResult.Ok(SearchResponse(tracks = listOf(exact)))
        }
        val refs = refs(client)
        val eclipse = addon(resources = listOf("search", "resolve", "stream"), dialect = AddonDialect.ECLIPSE, eclipse = EclipseTraits(resolveViaIsrc = true))
        suspend fun download() = refs.find(eclipse, song, AddonRefs.Pass.LOSSLESS, paced = true, bySearch = false)

        // Three retry passes: asked once.
        repeat(3) { assertThat(download()).isEqualTo(AddonRefs.Found.Unavailable) }
        coVerify(exactly = 1) { client.resolve(any(), any(), any()) }
        assertThat(dao.rows).isEmpty()
        // As long as a miss lasts, and no longer.
        now += TidalClient.MISS_TTL_MS
        download()
        coVerify(exactly = 2) { client.resolve(any(), any(), any()) }
        // Gone with the add-on.
        refs.forget(eclipse.id)
        download()
        coVerify(exactly = 3) { client.resolve(any(), any(), any()) }

        // The play is closed by none of it: it asks, and its search finds the song.
        assertThat(refs.find(eclipse, song, AddonRefs.Pass.LOSSLESS)).isInstanceOf(AddonRefs.Found.Hit::class.java)
        coVerify(exactly = 4) { client.resolve(any(), any(), any()) }
        coVerify(exactly = 1) { client.search(any(), any(), any(), any()) }
    }

    // ------------------------------------------------------------------------------------------ no match on words alone (review-882 J4)

    private val noLength = Track(id = "55", title = "Digital Love", artist = "Daft Punk")

    @Test fun `a row that states no length is no match for a song whose length is known`() = runTest {
        val refs = refs(searching(noLength))
        assertThat(refs.find(addon(), song.copy(isrc = null), AddonRefs.Pass.LOSSLESS)).isEqualTo(AddonRefs.Found.Miss)
    }

    @Test fun `a row without a length is placed by the song's own ISRC, and one with a length is matched as before`() = runTest {
        val byIsrc = refs(searching(noLength.copy(isrc = "GBDUW0000059"))).find(addon(), song, AddonRefs.Pass.LOSSLESS)
        assertThat((byIsrc as AddonRefs.Found.Hit).ref.remoteId).isEqualTo("55")
        val other = refs(searching(noLength, twin)).find(addon("other"), song.copy(isrc = null), AddonRefs.Pass.LOSSLESS)
        assertThat((other as AddonRefs.Found.Hit).ref.remoteId).isEqualTo("41")
    }

    /** review-882 round 2, J4's last gap: words alone are not a match, whatever is known of the song's own length. */
    @Test fun `a row that states no length is no match either for a song whose own length is not trusted, or not known`() = runTest {
        // A YouTube row's length is the video's.
        val video = song.copy(isrc = null, durationReliable = false)
        assertThat(refs(searching(noLength)).find(addon(), video, AddonRefs.Pass.LOSSLESS)).isEqualTo(AddonRefs.Found.Miss)
        val unknown = TrackQuery(artist = "Daft Punk", title = "Digital Love")
        assertThat(refs(searching(noLength)).find(addon("other"), unknown, AddonRefs.Pass.LOSSLESS)).isEqualTo(AddonRefs.Found.Miss)
        // One rule for every add-on that is matched by search, a default one included.
        val default = addon("default").copy(userAdded = false)
        assertThat(refs(searching(noLength)).find(default, video, AddonRefs.Pass.LOSSLESS)).isEqualTo(AddonRefs.Found.Miss)

        // A row that states its length is matched as before, the video's own length held loosely against it.
        val withLength = refs(searching(noLength, twin)).find(addon("third"), video, AddonRefs.Pass.LOSSLESS)
        assertThat((withLength as AddonRefs.Found.Hit).ref.remoteId).isEqualTo("41")
        val forUnknown = refs(searching(twin)).find(addon("fourth"), unknown, AddonRefs.Pass.LOSSLESS)
        assertThat((forUnknown as AddonRefs.Found.Hit).ref.remoteId).isEqualTo("41")
        // And the song's own ISRC still places a row without one.
        val byIsrc = refs(searching(noLength.copy(isrc = "GBDUW0000059"))).find(addon("fifth"), song.copy(durationReliable = false), AddonRefs.Pass.LOSSLESS)
        assertThat((byIsrc as AddonRefs.Found.Hit).ref.remoteId).isEqualTo("55")
    }

    private fun eclipseResolver(item: Track?): Pair<HttpAddonClient, InstalledAddon> {
        val client: HttpAddonClient = mockk { coEvery { resolve(any(), any(), any()) } returns AddonResult.Ok(item) }
        return client to addon(resources = listOf("resolve", "stream"), dialect = AddonDialect.ECLIPSE, eclipse = EclipseTraits())
    }

    @Test fun `an Eclipse resolve item is checked like a search row - another song, or one with no length, is refused`() = runTest {
        // "Top hit" resolvers answer something for everything.
        for (wrong in listOf(cover, noLength, exact.copy(isrc = null, durationMs = 30_000L))) {
            dao.rows.clear()
            val (client, eclipse) = eclipseResolver(wrong)
            assertThat(refs(client).find(eclipse, song.copy(isrc = null), AddonRefs.Pass.LOSSLESS)).isEqualTo(AddonRefs.Found.Miss)
            // Remembered as a miss, like an answer of no item; never kept as the song's ref.
            assertThat(dao.rows.values.single().remoteId).isNull()
        }
    }

    @Test fun `an Eclipse resolve item that is the song is kept, at the matcher's own score`() = runTest {
        val (client, eclipse) = eclipseResolver(twin)
        val found = refs(client).find(eclipse, song.copy(isrc = null), AddonRefs.Pass.LOSSLESS)
        assertThat((found as AddonRefs.Found.Hit).ref.remoteId).isEqualTo("41")
        assertThat(found.ref.confidence).isWithin(0.001f).of(1f)
    }

    @Test fun `a v1 resolve item is the song by contract and is not second-guessed`() = runTest {
        val client: HttpAddonClient = mockk { coEvery { resolve(any(), any(), any()) } returns AddonResult.Ok(noLength) }
        val found = refs(client).find(addon(resources = listOf("resolve", "stream")), song.copy(isrc = null), AddonRefs.Pass.LOSSLESS)
        assertThat((found as AddonRefs.Found.Hit).ref.confidence).isEqualTo(AddonRefs.RESOLVE_CONFIDENCE)
    }

    // ------------------------------------------------------------------------------------------ an unlabelled lossy answer (review-882 J5)

    /** `{"url": …}`: not lossless, no bit rate, no quality word. Its `low` is only the reader's last resort. */
    private val bare = StreamInfo(url = "https://cdn.example/a", manifest = Delivery.NONE, tier = Tier.LOW, cors = false)
    private val lowReq = StreamRequest(Tier.LOW, Tier.LOW, listOf(Delivery.NONE, Delivery.DASH))

    private fun eclipseSearcher() = addon(dialect = AddonDialect.ECLIPSE, eclipse = EclipseTraits())

    @Test fun `an unlabelled answer held from the lossless pass serves the lossy pass at 320`() = runTest {
        val client: HttpAddonClient = mockk {
            coEvery { search(any(), any(), any(), any()) } returns AddonResult.Ok(SearchResponse(tracks = listOf(exact)))
            coEvery { stream(any(), any(), losslessReq, any()) } returns AddonResult.Failed(
                AddonFailure.Refused(ErrorCode.QUALITY_UNAVAILABLE, 200, null, null, held = HeldStream(bare)),
            )
        }
        val refs = refs(client)
        assertThat(refs.stream(eclipseSearcher(), song, AddonRefs.Pass.LOSSLESS, losslessReq)).isEqualTo(AddonRefs.Streamed.Miss)
        val lossy = refs.stream(eclipseSearcher(), song, AddonRefs.Pass.LOSSY, highReq)
        assertThat((lossy as AddonRefs.Streamed.Ok).info).isEqualTo(bare)
        coVerify(exactly = 1) { client.stream(any(), any(), any(), any()) }
    }

    @Test fun `an unlabelled answer refused under Data saver is not remembered - it plays at 320 the same day`() = runTest {
        val client: HttpAddonClient = mockk {
            coEvery { search(any(), any(), any(), any()) } returns AddonResult.Ok(SearchResponse(tracks = listOf(exact)))
            coEvery { stream(any(), any(), lowReq, any()) } returns AddonResult.Failed(
                AddonFailure.Refused(ErrorCode.QUALITY_UNAVAILABLE, 200, null, null, held = HeldStream(bare)),
            )
            coEvery { stream(any(), any(), highReq, any()) } returns AddonResult.Ok(bare)
        }
        val refs = refs(client)
        assertThat(refs.stream(eclipseSearcher(), song, AddonRefs.Pass.LOSSY, lowReq)).isEqualTo(AddonRefs.Streamed.Miss)
        assertThat(dao.rows["bc" to key]!!.aacMissAt).isNull()
        assertThat(refs.stream(eclipseSearcher(), song, AddonRefs.Pass.LOSSY, highReq)).isInstanceOf(AddonRefs.Streamed.Ok::class.java)
    }

    @Test fun `an answer that says it is under 256 kbps is still a remembered miss at 320`() = runTest {
        val stated = bare.copy(quality = "128kbps")
        val client: HttpAddonClient = mockk {
            coEvery { search(any(), any(), any(), any()) } returns AddonResult.Ok(SearchResponse(tracks = listOf(exact)))
            coEvery { stream(any(), any(), highReq, any()) } returns AddonResult.Failed(
                AddonFailure.Refused(ErrorCode.QUALITY_UNAVAILABLE, 200, null, null, held = HeldStream(stated)),
            )
        }
        val refs = refs(client)
        assertThat(refs.stream(eclipseSearcher(), song, AddonRefs.Pass.LOSSY, highReq)).isEqualTo(AddonRefs.Streamed.Miss)
        assertThat(dao.rows["bc" to key]!!.aacMissAt).isEqualTo(now)
    }

    @Test fun `quality_unavailable stamps the pass, and its held answer serves the lossy pass without asking again`() = runTest {
        val client: HttpAddonClient = mockk {
            coEvery { search(any(), any(), any(), any()) } returns AddonResult.Ok(SearchResponse(tracks = listOf(exact)))
            coEvery { stream(any(), any(), losslessReq, any()) } returns AddonResult.Failed(
                AddonFailure.Refused(ErrorCode.QUALITY_UNAVAILABLE, 200, null, null, held = HeldStream(mp3)),
            )
        }
        val refs = refs(client)
        assertThat(refs.stream(addon(), song, AddonRefs.Pass.LOSSLESS, losslessReq)).isEqualTo(AddonRefs.Streamed.Miss)
        assertThat(dao.rows["bc" to key]!!.losslessMissAt).isEqualTo(now)

        val lossy = refs.stream(addon(), song, AddonRefs.Pass.LOSSY, highReq)
        assertThat((lossy as AddonRefs.Streamed.Ok).info).isEqualTo(mp3)
        // One stream call: the lossy pass used the held answer.
        coVerify(exactly = 1) { client.stream(any(), any(), any(), any()) }
        // The next play's lossless pass skips the add-on (the stamp), and the held answer is gone.
        assertThat(refs.stream(addon(), song, AddonRefs.Pass.LOSSLESS, losslessReq)).isEqualTo(AddonRefs.Streamed.Miss)
        coVerify(exactly = 1) { client.stream(any(), any(), any(), any()) }
    }

    @Test fun `a held answer outside the lossy range is not used`() = runTest {
        val client: HttpAddonClient = mockk {
            coEvery { search(any(), any(), any(), any()) } returns AddonResult.Ok(SearchResponse(tracks = listOf(exact)))
            coEvery { stream(any(), any(), losslessReq, any()) } returns AddonResult.Failed(
                AddonFailure.Refused(ErrorCode.QUALITY_UNAVAILABLE, 200, null, null, held = HeldStream(mp3.copy(tier = Tier.LOW))),
            )
            coEvery { stream(any(), any(), highReq, any()) } returns AddonResult.Ok(mp3)
        }
        val refs = refs(client)
        refs.stream(addon(), song, AddonRefs.Pass.LOSSLESS, losslessReq)
        assertThat((refs.stream(addon(), song, AddonRefs.Pass.LOSSY, highReq) as AddonRefs.Streamed.Ok).info).isEqualTo(mp3)
        coVerify(exactly = 2) { client.stream(any(), any(), any(), any()) }
    }

    /** What Remove calls ([AddonLinks.remove]; `AddonLinksTest` holds Remove itself to it). */
    @Test fun `forget clears one add-on's refs and no other add-on's`() = runTest {
        val client = searching(exact)
        val refs = refs(client)
        refs.find(addon("bc"), song, AddonRefs.Pass.LOSSLESS)
        refs.find(addon("other"), song, AddonRefs.Pass.LOSSLESS)
        dao.upsert(AddonRefEntity(TidalClient.REFS_ADDON_ID, key, remoteId = "t1", resolvedAt = now))
        refs.forget("bc")
        assertThat(dao.rows.keys).containsExactly("other" to key, TidalClient.REFS_ADDON_ID to key)
    }

    // ------------------------------------------------------------------------------------------ cover art (review-882 B1, M7)

    private val art = "https://addon.example/tok/art/1.jpg"
    private val withArt = exact.copy(artwork = listOf(Artwork(art, 640, 640)))

    @Test fun `cover art from an add-on the user added is neither handed out nor kept`() = runTest {
        val found = refs(searching(withArt)).find(addon(), song, AddonRefs.Pass.LOSSLESS)
        assertThat((found as AddonRefs.Found.Hit).ref.artwork).isNull()
        assertThat(dao.rows["bc" to key]!!.remoteId).isEqualTo("42")
        assertThat(dao.rows["bc" to key]!!.artwork).isNull()
    }

    @Test fun `a default add-on's cover art is handed out and kept`() = runTest {
        val official = addon().copy(userAdded = false)
        val found = refs(searching(withArt)).find(official, song, AddonRefs.Pass.LOSSLESS)
        assertThat((found as AddonRefs.Found.Hit).ref.artwork).isEqualTo(art)
        assertThat(dao.rows["bc" to key]!!.artwork).isEqualTo(art)
    }

    @Test fun `art in a row kept earlier is not handed out for an add-on the user added`() = runTest {
        dao.upsert(AddonRefEntity("bc", key, remoteId = "42", artwork = art, confidence = 0.9f, resolvedAt = now))
        // Strict: the ref answers, nothing is asked.
        val found = refs(mockk()).find(addon(), song, AddonRefs.Pass.LOSSLESS)
        assertThat((found as AddonRefs.Found.Hit).ref.artwork).isNull()
        assertThat((refs(mockk()).find(addon().copy(userAdded = false), song, AddonRefs.Pass.LOSSLESS) as AddonRefs.Found.Hit).ref.artwork).isEqualTo(art)
    }

    @Test fun `an add-on that calls itself tidal never shares the Tidal rung's rows`() = runTest {
        dao.upsert(AddonRefEntity(TidalClient.REFS_ADDON_ID, key, remoteId = "tidal-own", resolvedAt = now))
        val client = searching(exact)
        val refs = refs(client)
        val found = refs.find(addon("tidal"), song, AddonRefs.Pass.LOSSLESS)
        assertThat((found as AddonRefs.Found.Hit).ref.remoteId).isEqualTo("42")
        refs.forget("tidal")
        assertThat(dao.rows[TidalClient.REFS_ADDON_ID to key]!!.remoteId).isEqualTo("tidal-own")
    }

    @Test fun `an ISRC-only resolver falls back to search for a song without an ISRC, or one its ISRC misses`() = runTest {
        val client: HttpAddonClient = mockk {
            coEvery { resolve(any(), any(), any()) } returns AddonResult.Ok(null)
            coEvery { search(any(), any(), any(), any()) } returns AddonResult.Ok(SearchResponse(tracks = listOf(exact)))
        }
        val refs = refs(client)
        val eclipse = addon(resources = listOf("search", "resolve", "stream"), dialect = AddonDialect.ECLIPSE, eclipse = EclipseTraits(resolveViaIsrc = true))
        val noIsrc = song.copy(isrc = null)
        assertThat(refs.find(eclipse, noIsrc, AddonRefs.Pass.LOSSLESS)).isInstanceOf(AddonRefs.Found.Hit::class.java)
        coVerify(exactly = 0) { client.resolve(any(), any(), any()) }
        assertThat(refs.find(eclipse, song, AddonRefs.Pass.LOSSLESS)).isInstanceOf(AddonRefs.Found.Hit::class.java)
        coVerify(exactly = 1) { client.resolve(any(), any(), any()) }
        coVerify(exactly = 2) { client.search(any(), any(), any(), any()) }
    }

    @Test fun `a download asks download when the add-on lists it, paced`() = runTest {
        val client: HttpAddonClient = mockk {
            coEvery { resolve(any(), any(), any()) } returns AddonResult.Ok(exact)
            coEvery { download(any(), "42", any(), true) } returns AddonResult.Ok(flac)
        }
        val a = addon(resources = listOf("resolve", "download", "stream"))
        val r = refs(client).stream(a, song, AddonRefs.Pass.LOSSLESS, losslessReq, paced = true, download = true)
        assertThat((r as AddonRefs.Streamed.Ok).ref.confidence).isEqualTo(1f)
        coVerify(exactly = 0) { client.stream(any(), any(), any(), any()) }
    }
}
