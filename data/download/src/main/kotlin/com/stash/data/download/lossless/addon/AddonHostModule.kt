package com.stash.data.download.lossless.addon

import android.content.Context
import android.content.pm.ApplicationInfo
import com.stash.core.addons.AddonHostOptions
import com.stash.core.addons.AddonInstallSigner
import com.stash.core.addons.AddonManifestStore
import com.stash.core.addons.AddonMediaRefusals
import com.stash.core.addons.MediaRefusal
import com.stash.core.addons.AddonRateGate
import com.stash.core.addons.AddonTrust
import com.stash.core.addons.DefaultAddonEntry
import com.stash.core.addons.DefaultAddons
import com.stash.core.addons.HttpAddonClient
import com.stash.core.addons.UserAddons
import com.stash.data.download.lossless.AggregatorRateLimiter
import com.stash.data.download.lossless.relay.LosslessConfigFetcher
import com.stash.data.download.lossless.relay.RelaySigner
import com.stash.data.download.lossless.tidal.TidalDebugSettings
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Binds `:core:addons`' host ports and registry inputs to the app's existing pieces, so the add-on
 * client paces, pauses and signs exactly as the lossless sources already do, and the registry
 * reads the signed config's defaults and the user's own add-ons.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AddonHostModule {
    @Binds @Singleton
    abstract fun bindRateGate(impl: RateLimiterAddonGate): AddonRateGate

    @Binds @Singleton
    abstract fun bindTrust(impl: SignedConfigAddonTrust): AddonTrust

    @Binds @Singleton
    abstract fun bindSigner(impl: RelayAddonSigner): AddonInstallSigner

    @Binds @Singleton
    abstract fun bindDefaultAddons(impl: SignedConfigDefaultAddons): DefaultAddons

    @Binds @Singleton
    abstract fun bindUserAddons(impl: EncryptedUserAddons): UserAddons

    @Binds @Singleton
    abstract fun bindManifestStore(impl: DataStoreAddonManifestStore): AddonManifestStore

    @Binds @Singleton
    abstract fun bindHostOptions(impl: BuildAddonHostOptions): AddonHostOptions

    @Binds @Singleton
    abstract fun bindMediaRefusals(impl: RefsMediaRefusals): AddonMediaRefusals
}

/**
 * [AddonMediaRefusals] on [AddonRefs] (review-882 round 2, F7): a stream the media client's guard
 * refused to fetch loses its ref, and its song is left to the next source
 * ([AddonRefs.refusedOnTheWire]).
 *
 * Done on the fetching thread and waited for, briefly: the fetch's failure reaches the player
 * only after this returns, so the player's retry of the song already finds the ref gone. That
 * thread is OkHttp's or the downloader's, never the main thread. [refs] is lazy because the
 * add-on client is built with this and [AddonRefs] with the client.
 */
@Singleton
class RefsMediaRefusals @Inject constructor(
    private val refs: dagger.Lazy<AddonRefs>,
) : AddonMediaRefusals {
    override fun refused(streamUrl: String, why: MediaRefusal) {
        runBlocking { withTimeoutOrNull(WRITE_TIMEOUT_MS) { refs.get().refusedOnTheWire(streamUrl, why) } }
    }

    private companion object {
        /** A row's write: milliseconds. The limit only keeps a stuck database from holding the fetching thread. */
        const val WRITE_TIMEOUT_MS = 2_000L
    }
}

/**
 * [AddonRateGate] on [AggregatorRateLimiter]: each add-on gets its own bucket and breaker under
 * its `addon_<id>` key, with the limiter's default numbers (1 request / 8 s steady, burst 3).
 * A paced call waits for a token; a foreground one only checks the breaker, as playback does.
 */
@Singleton
class RateLimiterAddonGate @Inject constructor(
    private val limiter: AggregatorRateLimiter,
) : AddonRateGate {
    override suspend fun admit(key: String, paced: Boolean): Boolean =
        if (paced) limiter.acquire(key) else !limiter.stateOf(key).isCircuitBroken

    override suspend fun onAnswer(key: String) = limiter.reportSuccess(key)

    override suspend fun onRateLimited(key: String, retryAfterMs: Long?) = limiter.reportRateLimited(key, retryAfterMs)

    override suspend fun onFailure(key: String) = limiter.reportFailure(key)
}

/**
 * The default add-ons: the signed config's `default_addons` (decision D9: an enabled
 * `first_party` Tidal entry switches the Tidal rung to the v1 wire). On a debug build with an
 * owner server override set ([TidalDebugSettings]), that override's v1 manifest stands in for the
 * config's first-party entries, in memory.
 */
@Singleton
class SignedConfigDefaultAddons @Inject constructor(
    config: LosslessConfigFetcher,
    debug: TidalDebugSettings,
) : DefaultAddons {
    override val entries: StateFlow<List<DefaultAddonEntry>> =
        combine(config.defaultAddons, config.tidalRelay, debug.state) { list, relay, _ ->
            val override = debug.addonEntry(relay) ?: return@combine list
            listOf(override) + list.filterNot { it.firstParty }
        }.stateIn(CoroutineScope(SupervisorJob() + Dispatchers.IO), SharingStarted.Eagerly, config.defaultAddons.value)
}

/**
 * First-party = Stash's own, decided only by the ECDSA-signed lossless config, never by anything an
 * add-on says about itself:
 *  - served from the relay the config names (`tidal_relay_url`): the rule `RelaySigner.isOwnerRelay`
 *    applied before #871 (same scheme, host and port, and a path at or under the relay's);
 *  - or the base of an enabled `default_addons` entry marked `first_party` (phase 2), the debug
 *    override's stand-in included.
 * Only first-party add-ons get the install signature and the supporter token.
 */
@Singleton
class SignedConfigAddonTrust @Inject constructor(
    private val config: LosslessConfigFetcher,
    /** [SignedConfigDefaultAddons] in the app (bound in [AddonHostModule]). */
    private val defaults: DefaultAddons,
) : AddonTrust {
    override fun isFirstParty(base: String): Boolean {
        val listed = defaults.entries.value.any { e ->
            e.enabled && e.firstParty && HttpAddonClient.baseOf(e.manifestUrl)?.let { sameBase(it, base) } == true
        }
        if (listed) return true
        val relay = config.tidalRelay.value?.takeIf { !it.userOwned }?.base ?: return false
        return isUnder(base, relay)
    }

    internal companion object {
        /** True when [base] is [root] or a path under it, on the same origin. */
        fun isUnder(base: String, root: String): Boolean {
            val b = base.toHttpUrlOrNull() ?: return false
            val r = root.toHttpUrlOrNull() ?: return false
            if (b.scheme != r.scheme || b.host != r.host || b.port != r.port) return false
            val rootPath = r.encodedPath.trimEnd('/')
            val path = b.encodedPath.trimEnd('/')
            return path == rootPath || path.startsWith("$rootPath/")
        }

        /** The same base, compared as URLs (host case and default ports normalised). */
        fun sameBase(a: String, b: String): Boolean {
            val x = a.toHttpUrlOrNull() ?: return false
            val y = b.toHttpUrlOrNull() ?: return false
            return x.scheme == y.scheme && x.host == y.host && x.port == y.port && x.encodedPath == y.encodedPath
        }
    }
}

/**
 * [AddonInstallSigner] on [RelaySigner]: the app build, the install signature over the payload,
 * and the supporter token, the headers the Tidal rung sends the owner's relay on a `/stream`.
 * [HttpAddonClient] calls it for first-party add-ons only.
 */
@Singleton
class RelayAddonSigner @Inject constructor(
    private val signer: RelaySigner,
) : AddonInstallSigner {
    /** Test seam: the clock the signature's timestamp is read from. */
    internal var nowMs: () -> Long = System::currentTimeMillis

    override suspend fun sign(builder: Request.Builder, payload: String): Boolean {
        signer.appBuild(builder)
        val signed = signer.sign(builder, payload, nowMs())
        signer.entitle(builder)
        return signed
    }

    override fun adoptServerTime(response: Response): Boolean = signer.adoptServerTime(response, nowMs())
}

/**
 * SPEC.md 1.1's development exception (`http:` and `https:` on 127.0.0.1, localhost, [::1]) on
 * debuggable builds only: an add-on under `wrangler dev` on the PC, reached over `adb reverse`.
 * Release: https only, and the base rules for add-ons the user added. And the User-Agent.
 */
@Singleton
class BuildAddonHostOptions @Inject constructor(
    @ApplicationContext context: Context,
) : AddonHostOptions {
    override val allowLocalHttp: Boolean = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /** `Stash/<versionName>` (add-ons plan phase 4 §2.3), read once from the package. */
    override val userAgent: String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()?.takeIf { it.isNotBlank() && it.all { c -> c in '!'..'~' } }
        ?.let { "${AddonHostOptions.DEFAULT_USER_AGENT}/$it" } ?: AddonHostOptions.DEFAULT_USER_AGENT
}
