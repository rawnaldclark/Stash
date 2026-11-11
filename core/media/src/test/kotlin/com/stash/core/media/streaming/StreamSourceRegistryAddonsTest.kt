package com.stash.core.media.streaming

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.stash.addons.contract.AddonKind
import com.stash.addons.contract.Codec
import com.stash.addons.contract.ErrorCode
import com.stash.addons.contract.ResolveQuery
import com.stash.addons.contract.StreamRequest
import com.stash.addons.contract.Delivery
import com.stash.addons.contract.Manifest
import com.stash.addons.contract.StreamCapabilities
import com.stash.addons.contract.StreamInfo
import com.stash.addons.contract.Tier
import com.stash.addons.contract.Track
import com.stash.core.addons.AddonFailure
import com.stash.core.addons.AddonRegistry
import com.stash.core.addons.HeldStream
import com.stash.core.addons.AddonResult
import com.stash.core.addons.HttpAddonClient
import com.stash.core.addons.InstalledAddon
import com.stash.core.data.db.dao.AddonRefDao
import com.stash.core.data.db.entity.AddonRefEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.media.diagnostics.PlaybackDiagnosticsLog
import com.stash.data.download.lossless.LosslessAvailability
import com.stash.data.download.lossless.LosslessQualityTier
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.addon.AddonRefs
import com.stash.data.download.lossless.quota.LosslessQuota
import io.mockk.coEvery
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.Collections

/**
 * The add-on stream rungs (Stash Add-ons v1, phase 1). With an empty registry the chain must be
 * exactly the one the app had: for every setting the chain reads, a hit at each rung (or none),
 * and every kind of walk, the same result, the same calls in the same order (every resolver,
 * every setting read, the Home banner's served/miss signal), the same diagnostics line, and
 * nothing asked of the add-on client. With an add-on on, its rung sits at the lossless position.
 *
 * Walks run on a test dispatcher, so the hedged lanes (JioSaavn and the YouTube fast lane, started
 * alongside the lossless rungs) run in one fixed order and are compared in it too.
 */
class StreamSourceRegistryAddonsTest {

    /** Every call the chain makes into its collaborators, in order. Setting reads start "read ". */
    private val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private fun <T> rec(name: String, value: T): T { calls += name; return value }

    // The settings the chain reads, as each case sets them ([reset] puts back the defaults).
    private var lossless = true
    private var dataSaver = false
    private var preferTidalAac = false
    private var skipQobuz = false
    private var forceQbdlx = false
    private var forceYouTube = false
    private var ownTidalFirst = false
    private var tidalAacFallback = false
    private var allowanceSpent = false
    /** Tidal's answer for the song is not cached: a look-ahead stops before Tidal. */
    private var tidalUncached = false
    /** The rung that has the song; null: every rung misses. */
    private var hitAt: String? = null

    private fun reset() {
        lossless = true; dataSaver = false; preferTidalAac = false; skipQobuz = false; forceQbdlx = false
        forceYouTube = false; ownTidalFirst = false; tidalAacFallback = false; allowanceSpent = false
        tidalUncached = false; hitAt = null
    }

    private fun hit(rung: String): StreamUrl? =
        if (rung == hitAt) StreamUrl(url = "https://$rung.example/a", expiresAtMs = 1L, codec = rung, origin = rung) else null

    private val qbdlx: QbdlxStreamResolver = mockk { coEvery { resolve(any()) } coAnswers { rec("qbdlx", hit("qbdlx")) } }
    private val tidal: TidalStreamResolver = mockk {
        coEvery { resolve(any()) } coAnswers { rec("tidal", hit("tidal")) }
        coEvery { resolveLossless(any()) } coAnswers { rec("tidal-lossless", hit("tidal")) }
        coEvery { resolveAacOnly(any()) } coAnswers { rec("tidal-aac", hit("tidal-aac")) }
        coEvery { needsRelay(any()) } coAnswers { rec("tidal?", this@StreamSourceRegistryAddonsTest.tidalUncached) }
        coEvery { needsRelayAacOnly(any()) } coAnswers { rec("tidal-aac?", this@StreamSourceRegistryAddonsTest.tidalUncached) }
    }
    private val jiosaavn: JioSaavnStreamResolver = mockk { coEvery { resolve(any()) } coAnswers { rec("jiosaavn", hit("jiosaavn")) } }
    private val youtube: YouTubeStreamResolver = mockk {
        coEvery { resolve(any(), any()) } coAnswers { rec("youtube:${secondArg<Boolean>()}", hit("youtube")) }
    }
    private val streamingPreference: StreamingPreference = mockk {
        coEvery { isForceQbdlxOnly() } coAnswers { rec("read forceQbdlx", this@StreamSourceRegistryAddonsTest.forceQbdlx) }
        coEvery { isSkipQobuz() } coAnswers { rec("read skipQobuz", this@StreamSourceRegistryAddonsTest.skipQobuz) }
        coEvery { isForceYouTubeFallback() } coAnswers { rec("read forceYouTube", this@StreamSourceRegistryAddonsTest.forceYouTube) }
    }
    private val losslessPrefs: LosslessSourcePreferences = mockk {
        coEvery { enabledNow() } coAnswers { rec("read lossless", this@StreamSourceRegistryAddonsTest.lossless) }
        coEvery { tidalAacFallbackNow() } coAnswers { rec("read tidalAacFallback", this@StreamSourceRegistryAddonsTest.tidalAacFallback) }
    }
    private val policy: StreamQualityPolicy = mockk {
        coEvery { dataSaverApplies() } coAnswers { rec("read dataSaver", this@StreamSourceRegistryAddonsTest.dataSaver) }
        coEvery { preferTidalAac() } coAnswers { rec("read preferTidalAac", this@StreamSourceRegistryAddonsTest.preferTidalAac) }
        coEvery { preferCloudCopies() } coAnswers { rec("read preferCloudCopies", false) }
        coEvery { streamingTier() } coAnswers { rec("read tier", LosslessQualityTier.HI_RES) }
        coEvery { lossyQuality() } coAnswers {
            rec("read lossyQuality", if (this@StreamSourceRegistryAddonsTest.dataSaver) LossyQuality.DATA_SAVER else LossyQuality.FULL)
        }
    }
    /** A real one would answer by the user's accounts; here the case says. */
    private val availability: LosslessAvailability = mockk {
        coEvery { ownTidalFirstNow(any()) } coAnswers { rec("read ownTidalFirst", this@StreamSourceRegistryAddonsTest.ownTidalFirst) }
    }
    private val quota: LosslessQuota = mockk { every { isSpent() } answers { rec("read allowanceSpent", this@StreamSourceRegistryAddonsTest.allowanceSpent) } }
    /** The Home banner's signal. */
    private val health: LosslessSourceHealth = mockk {
        every { recordLosslessServed() } answers { calls += "health:served" }
        every { recordLosslessMiss() } answers { calls += "health:miss" }
    }

    /** Strict: any call to the add-on client fails the test. */
    private val strictClient: HttpAddonClient = mockk()

    /** The refs table in memory, keyed as the real one is. */
    private class RefDao : AddonRefDao {
        val rows = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, AddonRefEntity>()
        override suspend fun get(addonId: String, songKey: String) = rows[addonId to songKey]
        override suspend fun upsert(entity: AddonRefEntity) { rows[entity.addonId to entity.songKey] = entity }
        override suspend fun delete(addonId: String, songKey: String) = if (rows.remove(addonId to songKey) != null) 1 else 0
        override suspend fun deleteFor(addonId: String) = rows.keys.filter { it.first == addonId }.onEach { rows.remove(it) }.size
        override suspend fun deleteOlderThan(addonId: String, before: Long) = 0
    }

    /** [AddonRefs] over [client], with a fresh table. */
    private fun refs(client: HttpAddonClient) = AddonRefs(client, RefDao())

    private class Registry(list: List<InstalledAddon>) : AddonRegistry {
        override val addons: StateFlow<List<InstalledAddon>> = MutableStateFlow(list)
    }

    private fun TestScope.registry(addons: AddonStreamRungs?, log: PlaybackDiagnosticsLog = PlaybackDiagnosticsLog()) = StreamSourceRegistry(
        qbdlx, jiosaavn, youtube, streamingPreference, health, losslessPrefs, StandardTestDispatcher(testScheduler),
        log, tidal, quota, policy, null, null, availability, addons,
    )

    private fun track() = TrackEntity(id = 1L, title = "Title", artist = "Artist", album = "Album", durationMs = 200_000L, youtubeId = "abc")

    /** The resolver calls alone: no setting reads, no banner signal. */
    private fun asked() = calls.filterNot { it.startsWith("read ") || it.startsWith("health:") }

    // ------------------------------------------------------------------------------------------ inert

    private data class Mode(val allowYouTube: Boolean, val allowYtDlp: Boolean, val speculative: Boolean)

    /** Foreground, look-ahead, background fill, and the rest of the flag combinations. */
    private val modes = listOf(true, false).flatMap { y -> listOf(true, false).flatMap { d -> listOf(false, true).map { s -> Mode(y, d, s) } } }

    /** Each setting the chain reads, alone and in the pairs that meet in one branch. */
    private val settings: List<Pair<String, () -> Unit>> = listOf(
        "defaults" to {},
        "own Tidal first" to { ownTidalFirst = true },
        "own Tidal first, Tidal AAC when no FLAC" to { ownTidalFirst = true; tidalAacFallback = true },
        "Tidal AAC when no FLAC, Qobuz first" to { tidalAacFallback = true },
        "Prefer Tidal AAC" to { preferTidalAac = true },
        "Prefer Tidal AAC, own Tidal first" to { preferTidalAac = true; ownTidalFirst = true },
        "Data saver" to { dataSaver = true },
        "Data saver, Prefer Tidal AAC" to { dataSaver = true; preferTidalAac = true },
        "Lossless off" to { lossless = false },
        "Lossless off, Prefer Tidal AAC" to { lossless = false; preferTidalAac = true },
        "skip Qobuz (test)" to { skipQobuz = true },
        "force qbdlx (test)" to { forceQbdlx = true },
        "force YouTube (test)" to { forceYouTube = true },
        "allowance spent" to { allowanceSpent = true },
    )

    private val hits = listOf(null, "qbdlx", "tidal", "tidal-aac", "jiosaavn", "youtube")

    private data class Walk(val result: StreamUrl?, val calls: List<String>, val diagnostics: List<String>)

    private suspend fun TestScope.walk(addons: AddonStreamRungs?, mode: Mode): Walk {
        calls.clear()
        val log = PlaybackDiagnosticsLog()
        val r = registry(addons, log).resolve(track(), mode.allowYouTube, mode.allowYtDlp, mode.speculative)
        return Walk(r, calls.toList(), log.recentResolves().map { "${it.trackId} ${it.servedBy} lossless=${it.lossless}" })
    }

    @Test fun `an empty registry leaves every chain as it was, whatever the settings and wherever the song is`() = runTest {
        val none = AddonStreamRungs(Registry(emptyList()), refs(strictClient))
        val served = mutableSetOf<String?>()
        val seen = mutableSetOf<String>()
        for ((setting, apply) in settings) for (at in hits) for (uncached in listOf(false, true)) for (mode in modes) {
            reset(); apply(); hitAt = at; tidalUncached = uncached
            val before = walk(null, mode)
            val after = walk(none, mode)
            assertWithMessage("$setting; song at $at; Tidal uncached $uncached; $mode").that(after).isEqualTo(before)
            served += before.result?.origin
            seen += before.calls
        }
        // Nothing was asked of the add-on client.
        confirmVerified(strictClient)
        // The cases reached what they claim: a serve at every rung, every branch's own calls, and
        // the banner signal both ways.
        assertThat(served).containsExactly(null, "qbdlx", "tidal", "tidal-aac", "jiosaavn", "youtube")
        assertThat(seen).containsAtLeast(
            "tidal-lossless", "tidal-aac", "tidal?", "tidal-aac?", "youtube:false", "youtube:true",
            "read ownTidalFirst", "read tidalAacFallback", "read tier", "read allowanceSpent",
            "health:served", "health:miss",
        )
    }

    @Test fun `an empty registry gives no rungs and reads nothing else`() {
        assertThat(AddonStreamRungs(Registry(emptyList()), refs(strictClient)).rungs()).isEmpty()
    }

    // ------------------------------------------------------------------------------------------ with an add-on on

    private fun addon(
        id: String = "test",
        userAdded: Boolean = false,
        metered: Boolean = false,
        tiers: List<String> = listOf("lossless", "hires"),
    ) = InstalledAddon(
        base = "https://addon.example/",
        manifest = Manifest(
            stash = 1, id = id, name = "Test", version = "1", kind = AddonKind.SERVER,
            resources = listOf("resolve", "stream") + if (metered) listOf("quota") else emptyList(),
            stream = StreamCapabilities(cors = false, manifests = listOf("none", "dash"), tiers = tiers),
        ),
        userAdded = userAdded,
    )

    private val flac = StreamInfo(
        url = "https://cdn.example/a.flac", manifest = Delivery.NONE, tier = Tier.LOSSLESS, cors = false,
        bitDepth = 16, sampleRate = 44_100,
    )

    /** A client whose add-on has [hit] or misses (resolve answers no item). */
    private fun addonClient(hit: Boolean): HttpAddonClient = mockk {
        coEvery { resolve(any(), any(), any()) } coAnswers {
            // The lossless pass asks with a tier floor; the lossy pass without one.
            val pass = if (secondArg<ResolveQuery>().minTier == null) "lossy:" else ""
            calls += "${pass}addon:${firstArg<InstalledAddon>().id}"
            AddonResult.Ok(if (hit) Track(id = "x1", title = "Title", artist = "Artist") else null)
        }
        coEvery { stream(any(), any(), match { it.minTier.isLossless }, any()) } returns AddonResult.Ok(flac)
        coEvery { stream(any(), any(), match { !it.minTier.isLossless }, any()) } answers {
            val asked = thirdArg<StreamRequest>()
            lossyAsked += asked
            AddonResult.Ok(mp3.copy(tier = asked.tier))
        }
    }

    private val mp3 = StreamInfo(url = "https://cdn.example/a.mp3", manifest = Delivery.NONE, tier = Tier.HIGH, cors = false, codec = Codec.MP3)

    /** The lossy requests the add-on was sent. */
    private val lossyAsked = mutableListOf<StreamRequest>()

    @Test fun `a default add-on is asked after Tidal and before the lossy rungs`() = runTest {
        val rungs = AddonStreamRungs(Registry(listOf(addon())), refs(addonClient(hit = true)))
        val r = registry(rungs).resolve(track(), allowYouTube = false, allowYtDlp = true)
        assertThat(r!!.origin).isEqualTo("addon:test")
        assertThat(r.codec).isEqualTo("flac")
        assertThat(r.bitsPerSample).isEqualTo(16)
        assertThat(asked()).containsExactly("qbdlx", "tidal", "addon:test").inOrder()
        // An add-on's serve is lossless: it resets the Home banner's streak.
        assertThat(calls).contains("health:served")
    }

    @Test fun `a user-added add-on is asked after Stash's own lossless sources`() = runTest {
        // Owner decision Q3 (add-ons phase 4 §2.8): it sees only the songs Qobuz and Tidal miss, until the
        // user drags it up. Phase 2 asked it ahead of Qobuz on every play.
        val rungs = AddonStreamRungs(Registry(listOf(addon(id = "mine", userAdded = true))), refs(addonClient(hit = false)))
        registry(rungs).resolve(track(), allowYouTube = false, allowYtDlp = true)
        assertThat(asked()).containsExactly("qbdlx", "tidal", "addon:mine").inOrder()
        assertThat(calls.last()).isEqualTo("health:miss")
    }

    @Test fun `the user's own Tidal server, when it leads, moves alone - a user-added add-on stays after Qobuz`() = runTest {
        ownTidalFirst = true
        val rungs = AddonStreamRungs(Registry(listOf(addon(id = "mine", userAdded = true))), refs(addonClient(hit = false)))
        registry(rungs).resolve(track(), allowYouTube = false, allowYtDlp = true)
        assertThat(asked()).containsExactly("tidal-lossless", "qbdlx", "addon:mine").inOrder()
    }

    @Test fun `a look-ahead stops before a metered add-on`() = runTest {
        val rungs = AddonStreamRungs(Registry(listOf(addon(metered = true))), refs(addonClient(hit = true)))
        val r = registry(rungs).resolve(track(), allowYouTube = true, allowYtDlp = true, speculative = true)
        assertThat(r).isNull()
        // tidal? = the look-ahead's cache check, made while the chain is built.
        assertThat(asked()).containsExactly("tidal?", "qbdlx", "tidal").inOrder()
    }

    @Test fun `background fill never asks an add-on`() = runTest {
        val rungs = AddonStreamRungs(Registry(listOf(addon())), refs(addonClient(hit = true)))
        registry(rungs).resolve(track(), allowYouTube = false, allowYtDlp = false)
        assertThat(asked()).isEmpty()
    }

    @Test fun `with the Lossless switch off, or Data saver on, no add-on is asked`() = runTest {
        for (case in listOf<() -> Unit>({ lossless = false }, { dataSaver = true })) {
            reset(); case(); calls.clear()
            val rungs = AddonStreamRungs(Registry(listOf(addon())), refs(addonClient(hit = true)))
            registry(rungs).resolve(track(), allowYouTube = true, allowYtDlp = true)
            assertThat(asked().none { it.startsWith("addon:") }).isTrue()
        }
    }

    @Test fun `an answer that needs request headers is not played`() = runTest {
        val client: HttpAddonClient = mockk {
            coEvery { resolve(any(), any(), any()) } returns AddonResult.Ok(Track(id = "x1", title = "Title", artist = "Artist"))
            coEvery { stream(any(), any(), any(), any()) } returns AddonResult.Ok(flac.copy(headers = mapOf("Referer" to "https://x.example/")))
        }
        val rung = AddonStreamRungs(Registry(listOf(addon())), refs(client)).rungs().single()
        assertThat(rung.resolve(track(), null)).isNull()
    }

    @Test fun `expiry is the add-on's, a minute early, and never past an hour`() {
        val now = 1_000_000_000_000L
        val soon = AddonStreamRung.toStreamUrl(flac.copy(expiresAt = now / 1000 + 600), null, "addon:x", now)
        assertThat(soon!!.expiresAtMs).isEqualTo(now + 540_000)
        val none = AddonStreamRung.toStreamUrl(flac, null, "addon:x", now)
        assertThat(none!!.expiresAtMs).isEqualTo(now + 30 * 60_000)
        val far = AddonStreamRung.toStreamUrl(flac.copy(expiresAt = now / 1000 + 86_400), null, "addon:x", now)
        assertThat(far!!.expiresAtMs).isEqualTo(now + 60 * 60_000)
    }

    @Test fun `an answer already expired is not played, and one within its last minute is never cached`() = runTest {
        val now = 1_000_000_000_000L
        assertThat(AddonStreamRung.toStreamUrl(flac.copy(expiresAt = now / 1000 - 5), null, "addon:x", now)).isNull()
        assertThat(AddonStreamRung.toStreamUrl(flac.copy(expiresAt = now / 1000), null, "addon:x", now)).isNull()
        // Inside the 60 s SPEC.md 5.3 keeps back: good to play now, held to now so nothing caches it.
        val last = AddonStreamRung.toStreamUrl(flac.copy(expiresAt = now / 1000 + 30), null, "addon:x", now)
        assertThat(last!!.expiresAtMs).isEqualTo(now)

        // Through the rung: an expired answer is a miss, not a dead URL handed to the player.
        val client: HttpAddonClient = mockk {
            coEvery { resolve(any(), any(), any()) } returns AddonResult.Ok(Track(id = "x1", title = "Title", artist = "Artist"))
            coEvery { stream(any(), any(), any(), any()) } returns AddonResult.Ok(flac.copy(expiresAt = 1L))
        }
        assertThat(AddonStreamRungs(Registry(listOf(addon())), refs(client)).rungs().single().resolve(track(), null)).isNull()
    }

    // ------------------------------------------------------------------------------------------ the lossy pass (phase 4 §2.4)

    @Test fun `a lossy add-on is asked after the lossless rungs and before JioSaavn, at 320`() = runTest {
        hitAt = "jiosaavn"
        val rungs = AddonStreamRungs(Registry(listOf(addon(tiers = listOf("high")))), refs(addonClient(hit = true)))
        val r = registry(rungs).resolve(track(), allowYouTube = true, allowYtDlp = true)
        assertThat(r!!.origin).isEqualTo("addon:test")
        assertThat(r.codec).isEqualTo("mp3")
        assertThat(asked().filterNot { it == "jiosaavn" || it.startsWith("youtube") })
            .containsExactly("qbdlx", "tidal", "lossy:addon:test").inOrder()
        assertThat(lossyAsked.single()).isEqualTo(StreamRequest(Tier.HIGH, Tier.HIGH, listOf(Delivery.NONE, Delivery.DASH)))
        // A lossy serve is never a lossless one: the banner hears the lossless rungs' miss only.
        assertThat(calls).contains("health:miss")
        assertThat(calls).doesNotContain("health:served")
    }

    @Test fun `with Data saver on, or the Lossless switch off, only an add-on's lossy pass runs`() = runTest {
        for ((case, low) in listOf<Pair<() -> Unit, Boolean>>({ dataSaver = true } to true, { lossless = false } to false)) {
            reset(); case(); calls.clear(); lossyAsked.clear()
            val rungs = AddonStreamRungs(Registry(listOf(addon(tiers = listOf("lossless", "high")))), refs(addonClient(hit = true)))
            val r = registry(rungs).resolve(track(), allowYouTube = true, allowYtDlp = true)
            assertThat(r!!.origin).isEqualTo("addon:test")
            assertThat(asked()).doesNotContain("addon:test")
            assertThat(asked()).contains("lossy:addon:test")
            // 320 asks `high` only; Data saver `low` only.
            val tier = if (low) Tier.LOW else Tier.HIGH
            assertThat(lossyAsked.single()).isEqualTo(StreamRequest(tier, tier, listOf(Delivery.NONE, Delivery.DASH)))
        }
    }

    @Test fun `an add-on with lossless and lossy tiers is asked in both passes, lossless first`() = runTest {
        val client: HttpAddonClient = mockk {
            coEvery { resolve(any(), any(), any()) } coAnswers {
                calls += if (secondArg<ResolveQuery>().minTier == null) "lossy:addon:test" else "addon:test"
                AddonResult.Ok(Track(id = "x1", title = "Title", artist = "Artist"))
            }
            // No lossless copy of this song: out of range, held for the lossy pass.
            coEvery { stream(any(), any(), match { it.minTier.isLossless }, any()) } coAnswers {
                calls += "stream lossless"
                AddonResult.Failed(AddonFailure.Refused(ErrorCode.QUALITY_UNAVAILABLE, 200, null, null, held = HeldStream(mp3)))
            }
            coEvery { stream(any(), any(), match { !it.minTier.isLossless }, any()) } coAnswers {
                calls += "stream lossy"
                AddonResult.Ok(mp3)
            }
        }
        val rungs = AddonStreamRungs(Registry(listOf(addon(tiers = listOf("lossless", "high")))), refs(client))
        val r = registry(rungs).resolve(track(), allowYouTube = true, allowYtDlp = true)
        assertThat(r!!.codec).isEqualTo("mp3")
        // One resolve (the ref is kept) and one stream: the lossy pass used the held answer.
        assertThat(asked().filterNot { it == "jiosaavn" || it.startsWith("youtube") })
            .containsExactly("qbdlx", "tidal", "addon:test", "stream lossless").inOrder()
    }

    @Test fun `background fill never asks the lossy pass`() = runTest {
        val rungs = AddonStreamRungs(Registry(listOf(addon(tiers = listOf("high")))), refs(addonClient(hit = true)))
        assertThat(registry(rungs).resolve(track(), allowYouTube = false, allowYtDlp = false)).isNull()
        assertThat(asked()).isEmpty()
    }

    @Test fun `a look-ahead stops before a metered lossy add-on, after the lossless miss is heard`() = runTest {
        val rungs = AddonStreamRungs(Registry(listOf(addon(metered = true, tiers = listOf("high")))), refs(addonClient(hit = true)))
        val r = registry(rungs).resolve(track(), allowYouTube = true, allowYtDlp = true, speculative = true)
        assertThat(r).isNull()
        assertThat(asked()).containsExactly("tidal?", "qbdlx", "tidal").inOrder()
        assertThat(calls).contains("health:miss")
    }

    @Test fun `a lossy answer that names no codec is never labelled flac`() {
        val url = AddonStreamRung.toStreamUrl(mp3.copy(codec = null), null, "addon:x", 1_000L)
        assertThat(url!!.codec).isEqualTo("aac")
        assertThat(AddonStreamRung.toStreamUrl(flac, null, "addon:x", 1_000L)!!.codec).isEqualTo("flac")
    }
}
