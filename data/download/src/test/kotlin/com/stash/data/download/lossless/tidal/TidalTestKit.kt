package com.stash.data.download.lossless.tidal

import com.stash.addons.contract.Manifest
import com.stash.core.addons.AddonHostOptions
import com.stash.core.addons.AddonInstallSigner
import com.stash.core.addons.AddonRateGate
import com.stash.core.addons.AddonRegistry
import com.stash.core.addons.AddonTrust
import com.stash.core.addons.HttpAddonClient
import com.stash.core.addons.InstalledAddon
import com.stash.core.addons.NoUserAddons
import com.stash.core.data.db.dao.AddonRefDao
import com.stash.core.data.db.entity.AddonRefEntity
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.bitchord.LegacyRelayWire
import com.stash.data.download.lossless.quota.LosslessQuota
import com.stash.data.download.lossless.relay.LosslessConfigFetcher
import com.stash.data.download.lossless.relay.RelaySigner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap

/** In-memory [AddonRefDao] for the Tidal rung: [rows] keyed by song key (addon `tidal` only). */
class FakeAddonRefDao : AddonRefDao {
    val rows = ConcurrentHashMap<String, AddonRefEntity>()

    override suspend fun get(addonId: String, songKey: String): AddonRefEntity? =
        rows[songKey]?.takeIf { it.addonId == addonId }

    override suspend fun upsert(entity: AddonRefEntity) { rows[entity.songKey] = entity }

    override suspend fun deleteOlderThan(addonId: String, before: Long): Int = 0

    override suspend fun delete(addonId: String, songKey: String): Int =
        if (rows[songKey]?.addonId == addonId) { rows.remove(songKey); 1 } else 0

    override suspend fun deleteFor(addonId: String): Int {
        val gone = rows.filterValues { it.addonId == addonId }.keys
        gone.forEach { rows.remove(it) }
        return gone.size
    }
}

/** A `tidal` row as the old `tidal_match_cache` tests wrote it. */
fun tidalRef(key: String, remoteId: String?, resolvedAt: Long, losslessMissAt: Long? = null, aacMissAt: Long? = null) =
    AddonRefEntity(
        addonId = TidalClient.REFS_ADDON_ID,
        songKey = key,
        remoteId = remoteId,
        resolvedAt = resolvedAt,
        losslessMissAt = losslessMissAt,
        aacMissAt = aacMissAt,
    )

/** A registry with a fixed list: [addons] is what tests set. */
class FixedRegistry(list: List<InstalledAddon> = emptyList()) : AddonRegistry {
    val state = MutableStateFlow(list)
    override val addons: StateFlow<List<InstalledAddon>> get() = state
}

/** Gates nothing, trusts [firstParty], signs with a fixed `X-Stash-*` set over the payload. */
class TestAddonHost(private val firstParty: Boolean = true) : AddonRateGate, AddonTrust, AddonInstallSigner {
    val signedPayloads = mutableListOf<String>()
    override suspend fun admit(key: String, paced: Boolean) = true
    override suspend fun onAnswer(key: String) = Unit
    override suspend fun onRateLimited(key: String, retryAfterMs: Long?) = Unit
    override suspend fun onFailure(key: String) = Unit
    override fun isFirstParty(base: String) = firstParty
    override suspend fun sign(builder: Request.Builder, payload: String): Boolean {
        signedPayloads += payload
        builder.header("X-Stash-Install", "install-1").header("X-Stash-Sig", "sig:$payload")
        return true
    }
    override fun adoptServerTime(response: Response) = false
}

/**
 * A [TidalClient] whose wires are real: [LegacyRelayWire] signing with [signer], and
 * [AddonV1Wire] over [addonClient] for the first-party add-on in [registry].
 */
fun tidalClient(
    http: OkHttpClient,
    prefs: LosslessSourcePreferences,
    config: LosslessConfigFetcher,
    dao: AddonRefDao,
    signer: RelaySigner,
    quota: LosslessQuota,
    registry: AddonRegistry = FixedRegistry(),
    addonClient: HttpAddonClient = HttpAddonClient(http, TestAddonHost(), TestAddonHost(), TestAddonHost(), LocalHttp),
    debug: TidalDebugSettings = TidalDebugSettings.off(),
): TidalClient {
    val legacy = LegacyRelayWire(http, signer, quota)
    val wires = TidalWires(config, registry, legacy, AddonV1Wire(addonClient, quota), debug, OwnTidalServer(NoUserAddons, prefs))
    return TidalClient(prefs, dao, quota, wires)
}

/** MockWebServer add-ons are `http://localhost`: SPEC.md 1.1's development exception. */
object LocalHttp : AddonHostOptions {
    override val allowLocalHttp: Boolean = true
}

/** The first-party Tidal add-on at [base], with [manifest]. */
fun tidalAddon(base: String, manifest: Manifest) = InstalledAddon(base = base, manifest = manifest, firstParty = true)
