package com.stash.data.download.lossless.addon

import android.content.Context
import android.os.Build
import com.stash.core.addons.AddonCatalog
import com.stash.core.addons.AddonTrust
import com.stash.core.addons.BlockReason
import com.stash.core.addons.DefaultAddons
import com.stash.core.addons.HttpAddonClient
import com.stash.core.addons.OfficialEntry
import com.stash.core.addons.Trust
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The official list and the blocklist, from the signed index ([AddonIndexFetcher]) and the signed
 * config (add-ons plan phase 4 §1.6). Nothing an add-on says about itself counts.
 *
 * | Level | Decided by |
 * |---|---|
 * | First-party | [AddonTrust]: an enabled `first_party` default of the signed config, or under its Tidal relay |
 * | Official | any enabled `default_addons` entry, or a `manifest` in the signed index |
 * | Turned off by Stash | its origin (and its id, when the block names one) is in the index's `blocked` |
 * | Not made by Stash | everything else |
 *
 * Official is not first-party: being listed never sends the install signature. The blocklist is
 * for the add-ons a user adds; a default is removed by publishing the config without it, so a
 * default (and anything first-party) is never read as blocked.
 */
@Singleton
class SignedIndexAddonCatalog internal constructor(
    private val fetcher: AddonIndexFetcher,
    private val defaults: DefaultAddons,
    private val trust: AddonTrust,
    /** This app's build (versionCode): an entry with a higher `min_build` is not offered. */
    private val build: Long,
) : AddonCatalog {
    @Inject constructor(
        fetcher: AddonIndexFetcher,
        defaults: DefaultAddons,
        trust: AddonTrust,
        @ApplicationContext context: Context,
    ) : this(fetcher, defaults, trust, buildOf(context))

    override fun trustOf(base: String, id: String?): Trust {
        if (trust.isFirstParty(base)) return Trust.FIRST_PARTY
        if (isDefault(base)) return Trust.OFFICIAL
        val index = fetcher.index.value ?: return Trust.UNOFFICIAL
        if (blockIn(index, base, id) != null) return Trust.BLOCKED
        val listed = index.addons.any { e ->
            e.manifest != null && HttpAddonClient.baseOf(e.manifest)?.let { SignedConfigAddonTrust.sameBase(it, base) } == true
        }
        return if (listed) Trust.OFFICIAL else Trust.UNOFFICIAL
    }

    override fun blockReason(base: String, id: String?): BlockReason? {
        if (trust.isFirstParty(base) || isDefault(base)) return null
        return fetcher.index.value?.let { blockIn(it, base, id) }?.reason
    }

    private fun isDefault(base: String): Boolean = defaults.entries.value.any { e ->
        e.enabled && HttpAddonClient.baseOf(e.manifestUrl)?.let { SignedConfigAddonTrust.sameBase(it, base) } == true
    }

    private fun blockIn(index: AddonIndex, base: String, id: String?): AddonIndex.Block? {
        val origin = AddonIndex.originOf(base) ?: return null
        return index.blockOf(origin, id)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The index's add-ons a user can add on this phone: those with a manifest of their own, for Android, for this build or older. */
    override val official: StateFlow<List<OfficialEntry>> = fetcher.index
        .map { index -> index?.addons.orEmpty().filter { !it.isDefault && it.offeredTo(build) }.map(::entry) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** What the index says about the add-on with manifest id [id] (its name and one line), listed or default; null when it says nothing. */
    fun described(id: String): OfficialEntry? = fetcher.index.value?.addons?.firstOrNull { it.id == id }?.let(::entry)

    private fun entry(e: AddonIndex.Entry) = OfficialEntry(e.id, e.name, e.summary, e.icon, e.manifest)

    /** Every index applied, the kept copy at start included. */
    override val changes: Flow<Any?> = fetcher.index

    override suspend fun awaitLoaded() = fetcher.loadCached()

    private companion object {
        fun buildOf(context: Context): Long = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        }.getOrDefault(0L)
    }
}
