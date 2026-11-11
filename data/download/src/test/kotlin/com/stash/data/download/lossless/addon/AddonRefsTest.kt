package com.stash.data.download.lossless.addon

import com.google.common.truth.Truth.assertThat
import com.stash.addons.contract.AddonKind
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
 * dropping the ref, Remove clearing the add-on's refs, and the held answer of an out-of-range
 * lossless reply reaching the lossy pass of the same play.
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

    @Test fun `a search is matched on the phone, the row with the song's ISRC first, and kept`() = runTest {
        val client = searching(cover, exact)
        val found = refs(client).find(addon(), song, AddonRefs.Pass.LOSSLESS)
        assertThat((found as AddonRefs.Found.Hit).ref.remoteId).isEqualTo("42")
        val row = dao.rows["bc" to key]!!
        assertThat(row.remoteId).isEqualTo("42")
        assertThat(row.confidence).isAtLeast(0.9f)
        coVerify(exactly = 1) { client.search(any(), "Daft Punk Digital Love", any(), any()) }
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
    }

    @Test fun `not_found from the stream drops the ref, and the next play looks again`() = runTest {
        val client: HttpAddonClient = mockk {
            coEvery { search(any(), any(), any(), any()) } returns AddonResult.Ok(SearchResponse(tracks = listOf(exact)))
            coEvery { stream(any(), any(), any(), any()) } returns
                AddonResult.Failed(AddonFailure.Refused(ErrorCode.NOT_FOUND, 404, null, null))
        }
        val refs = refs(client)
        assertThat(refs.stream(addon(), song, AddonRefs.Pass.LOSSLESS, losslessReq)).isEqualTo(AddonRefs.Streamed.Miss)
        assertThat(dao.rows).isEmpty()
        refs.stream(addon(), song, AddonRefs.Pass.LOSSLESS, losslessReq)
        coVerify(exactly = 2) { client.search(any(), any(), any(), any()) }
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

    @Test fun `Remove clears the add-on's refs and no other add-on's`() = runTest {
        val client = searching(exact)
        val refs = refs(client)
        refs.find(addon("bc"), song, AddonRefs.Pass.LOSSLESS)
        refs.find(addon("other"), song, AddonRefs.Pass.LOSSLESS)
        dao.upsert(AddonRefEntity(TidalClient.REFS_ADDON_ID, key, remoteId = "t1", resolvedAt = now))
        refs.forget("bc")
        assertThat(dao.rows.keys).containsExactly("other" to key, TidalClient.REFS_ADDON_ID to key)
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
