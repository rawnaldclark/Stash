package com.stash.core.media.streaming

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.stash.addons.contract.AddonKind
import com.stash.addons.contract.Codec
import com.stash.addons.contract.Delivery
import com.stash.addons.contract.Manifest
import com.stash.addons.contract.ResolveQuery
import com.stash.addons.contract.StreamCapabilities
import com.stash.addons.contract.StreamInfo
import com.stash.addons.contract.Tier
import com.stash.addons.contract.Track
import com.stash.core.addons.AddonRegistry
import com.stash.core.addons.AddonResult
import com.stash.core.addons.HttpAddonClient
import com.stash.core.addons.InstalledAddon
import com.stash.core.addons.Trust
import com.stash.core.data.db.dao.AddonRefDao
import com.stash.core.data.db.entity.AddonRefEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.media.diagnostics.PlaybackDiagnosticsLog
import com.stash.data.download.lossless.LosslessAvailability
import com.stash.data.download.lossless.LosslessQualityTier
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.PlayOrder
import com.stash.data.download.lossless.addon.AddonRefs
import com.stash.data.download.lossless.quota.LosslessQuota
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.Collections

/**
 * The play order in the streaming chain (add-ons plan phase 4 §2.8, plan test `StreamSourceRegistryPlayOrderTest`):
 *  - the automatic order is the chain the app had before there was an order, over the settings that
 *    shape it (the own-server rule, Prefer Tidal AAC, Data saver, Lossless off) and a hit at each rung,
 *    with one change the owner decided (Q3): the user's add-ons go after Stash's own lossless sources;
 *  - a saved order is followed, with the rules no order can break;
 *  - the lossy pass sits before JioSaavn, in play order;
 *  - an add-on Stash doesn't run can't hold a play past its budget.
 */
class StreamSourceRegistryPlayOrderTest {

    private val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private var lossless = true
    private var dataSaver = false
    private var preferTidalAac = false
    private var ownTidalFirst = false
    private var ownTidalServer = false
    private var tidalAacFallback = false
    private var tidalUncached = false
    private var hitAt: String? = null

    private fun hit(rung: String): StreamUrl? =
        if (rung == hitAt) StreamUrl(url = "https://$rung.example/a", expiresAtMs = 1L, codec = rung, origin = rung) else null

    private val qbdlx: QbdlxStreamResolver = mockk { coEvery { resolve(any()) } coAnswers { calls += "qbdlx"; hit("qbdlx") } }
    private val tidal: TidalStreamResolver = mockk {
        coEvery { resolve(any()) } coAnswers { calls += "tidal"; hit("tidal") }
        coEvery { resolveLossless(any()) } coAnswers { calls += "tidal-lossless"; hit("tidal") }
        coEvery { resolveAacOnly(any()) } coAnswers { calls += "tidal-aac"; hit("tidal-aac") }
        coEvery { needsRelay(any()) } coAnswers { this@StreamSourceRegistryPlayOrderTest.tidalUncached }
        coEvery { needsRelayAacOnly(any()) } coAnswers { this@StreamSourceRegistryPlayOrderTest.tidalUncached }
    }
    private val jiosaavn: JioSaavnStreamResolver = mockk { coEvery { resolve(any()) } coAnswers { calls += "jiosaavn"; hit("jiosaavn") } }
    private val youtube: YouTubeStreamResolver = mockk { coEvery { resolve(any(), any()) } coAnswers { calls += "youtube"; hit("youtube") } }
    private val streamingPreference: StreamingPreference = mockk {
        coEvery { isForceQbdlxOnly() } returns false
        coEvery { isSkipQobuz() } returns false
        coEvery { isForceYouTubeFallback() } returns false
    }
    private val losslessPrefs: LosslessSourcePreferences = mockk {
        coEvery { enabledNow() } coAnswers { this@StreamSourceRegistryPlayOrderTest.lossless }
        coEvery { tidalAacFallbackNow() } coAnswers { this@StreamSourceRegistryPlayOrderTest.tidalAacFallback }
    }
    private val policy: StreamQualityPolicy = mockk {
        coEvery { dataSaverApplies() } coAnswers { this@StreamSourceRegistryPlayOrderTest.dataSaver }
        coEvery { preferTidalAac() } coAnswers { this@StreamSourceRegistryPlayOrderTest.preferTidalAac }
        coEvery { preferCloudCopies() } returns false
        coEvery { streamingTier() } returns LosslessQualityTier.HI_RES
        coEvery { lossyQuality() } coAnswers { if (this@StreamSourceRegistryPlayOrderTest.dataSaver) LossyQuality.DATA_SAVER else LossyQuality.FULL }
    }
    private val availability: LosslessAvailability = mockk {
        coEvery { ownTidalFirstNow(any()) } coAnswers { this@StreamSourceRegistryPlayOrderTest.ownTidalFirst }
        coEvery { ownTidalServerNow() } coAnswers { this@StreamSourceRegistryPlayOrderTest.ownTidalServer }
    }
    private val quota: LosslessQuota = mockk { every { isSpent() } returns false }
    private val health: LosslessSourceHealth = mockk(relaxed = true)

    private class RefDao : AddonRefDao {
        val rows = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, AddonRefEntity>()
        override suspend fun get(addonId: String, songKey: String) = rows[addonId to songKey]
        override suspend fun upsert(entity: AddonRefEntity) { rows[entity.addonId to entity.songKey] = entity }
        override suspend fun delete(addonId: String, songKey: String) = if (rows.remove(addonId to songKey) != null) 1 else 0
        override suspend fun deleteFor(addonId: String) = rows.keys.filter { it.first == addonId }.onEach { rows.remove(it) }.size
        override suspend fun deleteOlderThan(addonId: String, before: Long) = 0
    }

    private class Registry(list: List<InstalledAddon>) : AddonRegistry {
        override val addons: StateFlow<List<InstalledAddon>> = MutableStateFlow(list)
    }

    private fun addon(id: String, userAdded: Boolean = false, tiers: List<String> = listOf("lossless", "hires"), metered: Boolean = false) = InstalledAddon(
        base = "https://$id.example/",
        manifest = Manifest(
            stash = 1, id = id, name = id, version = "1", kind = AddonKind.SERVER,
            resources = listOf("resolve", "stream") + if (metered) listOf("quota") else emptyList(),
            stream = StreamCapabilities(cors = false, manifests = listOf("none", "dash"), tiers = tiers),
        ),
        userAdded = userAdded,
    )

    private val flac = StreamInfo(url = "https://cdn.example/a.flac", manifest = Delivery.NONE, tier = Tier.LOSSLESS, cors = false, bitDepth = 16, sampleRate = 44_100)
    private val mp3 = StreamInfo(url = "https://cdn.example/a.mp3", manifest = Delivery.NONE, tier = Tier.HIGH, cors = false, codec = Codec.MP3)

    /** An add-on client that misses every song (so the whole chain is walked) unless [hitAt] names the add-on's rung. */
    private fun client(): HttpAddonClient = mockk {
        coEvery { resolve(any(), any(), any()) } coAnswers {
            val id = firstArg<InstalledAddon>().id
            val lossy = secondArg<ResolveQuery>().minTier == null
            val rung = (if (lossy) "lossy:" else "") + "addon:$id"
            calls += rung
            AddonResult.Ok(if (this@StreamSourceRegistryPlayOrderTest.hitAt == rung) Track(id = "x1", title = "Title", artist = "Artist") else null)
        }
        coEvery { stream(any(), any(), match { it.minTier.isLossless }, any()) } returns AddonResult.Ok(flac)
        coEvery { stream(any(), any(), match { !it.minTier.isLossless }, any()) } answers { AddonResult.Ok(mp3.copy(tier = thirdArg<com.stash.addons.contract.StreamRequest>().tier)) }
        coEvery { reportTooSlow(any()) } coAnswers { calls += "too-slow:${firstArg<String>()}" }
    }

    private class Saved(initial: List<String>?) {
        val flow = MutableStateFlow(initial)
        suspend fun save(keys: List<String>?) { flow.value = keys }
    }

    private fun TestScope.registry(addons: List<InstalledAddon>, saved: List<String>?, client: HttpAddonClient = client()): StreamSourceRegistry {
        val reg = Registry(addons)
        val order = Saved(saved)
        return StreamSourceRegistry(
            qbdlx, jiosaavn, youtube, streamingPreference, health, losslessPrefs, StandardTestDispatcher(testScheduler),
            PlaybackDiagnosticsLog(), tidal, quota, policy, null, null, availability,
            AddonStreamRungs(reg, AddonRefs(client, RefDao())),
            PlayOrder(order.flow, order::save, reg),
        )
    }

    private fun track() = TrackEntity(id = 1L, title = "Title", artist = "Artist", album = "Album", durationMs = 200_000L, youtubeId = "abc")

    /** The rungs asked, in order; with [lossless] true only the ones ahead of JioSaavn and YouTube (those run side by side). */
    private fun asked(lossyToo: Boolean = false) = if (lossyToo) calls.toList() else calls.filterNot { it == "jiosaavn" || it == "youtube" }

    private suspend fun TestScope.walk(addons: List<InstalledAddon>, saved: List<String>?, speculative: Boolean = false): StreamUrl? {
        calls.clear()
        return registry(addons, saved).resolve(track(), allowYouTube = true, allowYtDlp = true, speculative = speculative)
    }

    // ------------------------------------------------------------------------------------------ automatic = the chain as it was

    private data class Case(val name: String, val set: () -> Unit, val expected: List<String>)

    @Test fun `with nothing saved and no add-on, the chain is the one the app had - every setting that shapes it`() = runTest {
        val cases = listOf(
            Case("defaults", {}, listOf("qbdlx", "tidal")),
            Case("own Tidal first", { ownTidalFirst = true }, listOf("tidal-lossless", "qbdlx")),
            Case("own Tidal first, Tidal AAC when no FLAC", { ownTidalFirst = true; tidalAacFallback = true }, listOf("tidal-lossless", "qbdlx", "tidal")),
            Case("Tidal AAC when no FLAC, Qobuz first", { tidalAacFallback = true }, listOf("qbdlx", "tidal")),
            Case("Prefer Tidal AAC", { preferTidalAac = true }, listOf("tidal-aac", "qbdlx", "tidal")),
            Case("Prefer Tidal AAC, own Tidal first", { preferTidalAac = true; ownTidalFirst = true }, listOf("tidal-aac", "tidal-lossless", "qbdlx")),
            Case("Data saver", { dataSaver = true }, emptyList()),
            Case("Data saver, Prefer Tidal AAC", { dataSaver = true; preferTidalAac = true }, listOf("tidal-aac")),
            Case("Lossless off", { lossless = false }, emptyList()),
        )
        for (case in cases) {
            reset(); case.set()
            walk(emptyList(), saved = null)
            assertWithMessage(case.name).that(asked()).containsExactlyElementsIn(case.expected).inOrder()
            // And the lossy rungs are still reached when nothing lossless has the song.
            assertWithMessage(case.name).that(calls).containsAtLeast("jiosaavn", "youtube")
        }
    }

    @Test fun `with nothing saved, a hit at each rung stops the walk there`() = runTest {
        for ((at, expected) in listOf("qbdlx" to listOf("qbdlx"), "tidal" to listOf("qbdlx", "tidal"))) {
            reset(); hitAt = at
            val r = walk(emptyList(), saved = null)
            assertThat(r!!.origin).isEqualTo(at)
            assertThat(asked()).containsExactlyElementsIn(expected).inOrder()
        }
        reset(); ownTidalFirst = true; hitAt = "tidal"
        assertThat(walk(emptyList(), saved = null)!!.origin).isEqualTo("tidal")
        assertThat(asked()).containsExactly("tidal-lossless")
    }

    @Test fun `with nothing saved, the user's add-ons come after Stash's own lossless sources, then the defaults`() = runTest {
        // Owner decision Q3. Phase 2 asked a user's add-on ahead of Qobuz on every play.
        val addons = listOf(addon("mine", userAdded = true), addon("builtin"))
        reset()
        walk(addons, saved = null)
        assertThat(asked()).containsExactly("qbdlx", "tidal", "addon:mine", "addon:builtin").inOrder()

        // The own-server rule still applies while the order is automatic, and moves only Tidal.
        reset(); ownTidalFirst = true; tidalAacFallback = true
        walk(addons, saved = null)
        assertThat(asked()).containsExactly("tidal-lossless", "qbdlx", "tidal", "addon:mine", "addon:builtin").inOrder()
    }

    // ------------------------------------------------------------------------------------------ a saved order

    @Test fun `a saved order is followed`() = runTest {
        val addons = listOf(addon("mine", userAdded = true), addon("builtin"))
        reset()
        walk(addons, saved = listOf("addon:mine", "qobuz", "addon:builtin", "tidal"))
        assertThat(asked()).containsExactly("addon:mine", "qbdlx", "addon:builtin", "tidal").inOrder()

        // The chain's first rung is the add-on's: it serves when it has the song.
        reset(); hitAt = "addon:mine"
        val r = walk(addons, saved = listOf("addon:mine", "qobuz", "tidal"))
        assertThat(r!!.origin).isEqualTo("addon:mine")
        assertThat(asked()).containsExactly("addon:mine")
    }

    @Test fun `once the order is saved, the own-server rule no longer moves Tidal`() = runTest {
        reset(); ownTidalFirst = true
        walk(emptyList(), saved = listOf("qobuz", "tidal"))
        assertThat(asked()).containsExactly("qbdlx", "tidal").inOrder()
    }

    @Test fun `Tidal saved ahead of Qobuz is asked for FLAC first, and for its AAC only after Qobuz misses`() = runTest {
        val addons = listOf(addon("mine", userAdded = true))
        reset(); tidalAacFallback = true
        walk(addons, saved = listOf("tidal", "addon:mine", "qobuz"))
        assertThat(asked()).containsExactly("tidal-lossless", "addon:mine", "qbdlx", "tidal").inOrder()

        // "Tidal AAC when no FLAC" off: FLAC only, no second ask.
        reset()
        walk(addons, saved = listOf("tidal", "addon:mine", "qobuz"))
        assertThat(asked()).containsExactly("tidal-lossless", "addon:mine", "qbdlx").inOrder()
    }

    @Test fun `a look-ahead never makes Stash's shared relay search, wherever Tidal is saved`() = runTest {
        // Stash's relay, saved first, nothing cached: the walk stops before it.
        reset(); tidalUncached = true
        assertThat(walk(emptyList(), saved = listOf("tidal", "qobuz"), speculative = true)).isNull()
        assertThat(asked(lossyToo = true)).isEmpty()

        // The user's own server saved first is asked like any first rung.
        reset(); tidalUncached = true; ownTidalServer = true
        walk(emptyList(), saved = listOf("tidal", "qobuz"), speculative = true)
        assertThat(asked()).containsExactly("tidal-lossless", "qbdlx").inOrder()
    }

    @Test fun `with Tidal switched off, a look-ahead goes on to the add-on behind it and to YouTube`() = runTest {
        // review-885 M1. Switched off, the Tidal rung says it has nothing to ask later (`TidalSwitchTest`
        // holds the client to that), so the look-ahead no longer stops before it: the next song is found ahead.
        reset(); tidalUncached = false
        walk(listOf(addon("mine", userAdded = true)), saved = null, speculative = true)
        assertThat(asked()).containsAtLeast("qbdlx", "tidal", "addon:mine").inOrder()
        assertThat(calls).contains("youtube")
    }

    @Test fun `with Tidal switched off, a song only a DASH add-on has plays from it, resolved before the player picks its source`() = runTest {
        // review-882 F9, from this branch's side. Switched off in Settings › Add-ons, Stash's Tidal has
        // no route: its rung answers nothing, and "a Tidal server is configured" is false
        // (`TidalSwitchTest`). The player resolved a placeholder before picking its source only while
        // one was configured, so an add-on's DASH answer went to the progressive source and the song
        // was skipped. The switch makes that the everyday case.
        reset(); hitAt = "addon:mine"
        val manifest = StreamInfo(url = "https://cdn.example/a.mpd", manifest = Delivery.DASH, tier = Tier.LOSSLESS, cors = false, bitDepth = 16, sampleRate = 44_100)
        val client = client().also {
            coEvery { it.stream(any(), any(), match { r -> r.minTier.isLossless }, any()) } returns AddonResult.Ok(manifest)
        }
        val mine = addon("mine", userAdded = true)
        calls.clear()
        val played = registry(listOf(mine), saved = null, client = client).resolve(track(), allowYouTube = true, allowYtDlp = true)
        // Tidal's rung was passed (it has nothing to say while it is off) and the add-on's manifest is the answer.
        assertThat(asked()).containsExactly("qbdlx", "tidal", "addon:mine").inOrder()
        assertThat(played!!.origin).isEqualTo("addon:mine")
        assertThat(played.isDash).isTrue()
        assertThat(played.url).isEqualTo("https://cdn.example/a.mpd")

        // The player's rule, with no Tidal server configured: resolve first while such an add-on is on.
        fun rungs(vararg on: InstalledAddon) = AddonStreamRungs(Registry(on.toList()), AddonRefs(client, RefDao()))
        assertThat(AddonPlayback.resolveBeforeSourcePick(tidalConfigured = false, addons = rungs(mine))).isTrue()
        // An add-on that only answers plain files changes nothing, and neither does none at all.
        val plain = mine.copy(manifest = mine.manifest.copy(stream = mine.manifest.stream!!.copy(manifests = listOf("none"))))
        assertThat(AddonPlayback.resolveBeforeSourcePick(tidalConfigured = false, addons = rungs(plain))).isFalse()
        assertThat(AddonPlayback.resolveBeforeSourcePick(tidalConfigured = false, addons = rungs())).isFalse()
    }

    @Test fun `a look-ahead stops at a metered add-on wherever it is saved`() = runTest {
        reset()
        assertThat(walk(listOf(addon("paid", userAdded = true, metered = true)), saved = listOf("qobuz", "addon:paid", "tidal"), speculative = true)).isNull()
        assertThat(asked(lossyToo = true)).containsExactly("qbdlx")
    }

    // ------------------------------------------------------------------------------------------ the lossy pass

    @Test fun `the lossy pass sits before JioSaavn, in play order`() = runTest {
        // Two add-ons that only have lossy audio, added a then b; the user put b first.
        val addons = listOf(addon("a", userAdded = true, tiers = listOf("high")), addon("b", userAdded = true, tiers = listOf("high")))
        reset(); hitAt = "jiosaavn"
        // The hedged lanes start alongside; what matters is that both lossy asks come after the lossless rungs.
        walk(addons, saved = listOf("addon:b", "qobuz", "tidal", "addon:a"))
        assertThat(asked()).containsExactly("qbdlx", "tidal", "lossy:addon:b", "lossy:addon:a").inOrder()

        // Data saver: only the lossy pass of the add-ons runs.
        reset(); dataSaver = true
        walk(addons, saved = listOf("addon:b", "qobuz", "tidal", "addon:a"))
        assertThat(asked()).containsExactly("lossy:addon:b", "lossy:addon:a").inOrder()
    }

    // ------------------------------------------------------------------------------------------ the play budget

    @Test fun `an add-on Stash doesn't run can't hold a play past its budget, and one Stash runs is not cut short`() = runTest {
        val slow: HttpAddonClient = mockk {
            coEvery { resolve(any(), any(), any()) } coAnswers {
                calls += "addon:${firstArg<InstalledAddon>().id}"
                delay(20_000)
                AddonResult.Ok(Track(id = "x1", title = "Title", artist = "Artist"))
            }
            coEvery { stream(any(), any(), any(), any()) } returns AddonResult.Ok(flac)
            coEvery { reportTooSlow(any()) } coAnswers { calls += "too-slow:${firstArg<String>()}" }
        }
        reset()
        val mine = addon("mine", userAdded = true)
        assertThat(mine.trust).isEqualTo(Trust.UNOFFICIAL)
        calls.clear()
        val started = currentTime
        val r = registry(listOf(mine), saved = listOf("addon:mine", "qobuz", "tidal"), client = slow)
            .resolve(track(), allowYouTube = false, allowYtDlp = true)
        assertThat(r).isNull()
        // The play moved on after the budget, to the rungs behind it, and the overrun counted against the add-on.
        assertThat(currentTime - started).isEqualTo(AddonStreamRung.UNOFFICIAL_PLAY_BUDGET_MS)
        assertThat(calls).containsExactly("addon:mine", "too-slow:mine", "qbdlx", "tidal").inOrder()
        coVerify(exactly = 1) { slow.reportTooSlow("mine") }

        // One of Stash's own keeps the contract's timeouts: it is waited for.
        calls.clear()
        val served = registry(listOf(addon("builtin")), saved = listOf("addon:builtin", "qobuz", "tidal"), client = slow)
            .resolve(track(), allowYouTube = false, allowYtDlp = true)
        assertThat(served!!.origin).isEqualTo("addon:builtin")
    }

    // ------------------------------------------------------------------------------------------ cover art, by trust

    @Test fun `an add-on of the official list keeps its cover art though the user added it, one Stash doesn't run gives none`() = runTest {
        // review-882 B1 and M7 keep an add-on's art out of the library unless Stash vouches for it. Vouching
        // is the trust level, not who added it: an add-on of the signed index, added by the user, is Official.
        val art = "https://cdn.example/art/1.jpg"
        val withArt: HttpAddonClient = mockk {
            coEvery { resolve(any(), any(), any()) } returns AddonResult.Ok(
                Track(id = "x1", title = "Title", artist = "Artist", artwork = listOf(com.stash.addons.contract.Artwork(art))),
            )
            coEvery { stream(any(), any(), any(), any()) } returns AddonResult.Ok(flac)
        }
        reset()
        val listed = addon("listed", userAdded = true).copy(trust = Trust.OFFICIAL)
        val fromListed = registry(listOf(listed), saved = listOf("addon:listed", "qobuz", "tidal"), client = withArt)
            .resolve(track(), allowYouTube = false, allowYtDlp = true)
        assertThat(fromListed!!.origin).isEqualTo("addon:listed")
        assertThat(fromListed.coverArtUrl).isEqualTo(art)

        val mine = addon("mine", userAdded = true)
        assertThat(mine.trust).isEqualTo(Trust.UNOFFICIAL)
        val fromMine = registry(listOf(mine), saved = listOf("addon:mine", "qobuz", "tidal"), client = withArt)
            .resolve(track(), allowYouTube = false, allowYtDlp = true)
        assertThat(fromMine!!.origin).isEqualTo("addon:mine")
        assertThat(fromMine.coverArtUrl).isNull()
    }

    private fun reset() {
        lossless = true; dataSaver = false; preferTidalAac = false; ownTidalFirst = false; ownTidalServer = false
        tidalAacFallback = false; tidalUncached = false; hitAt = null
    }
}
