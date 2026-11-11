package com.stash.data.download.lossless.addon

import com.stash.core.addons.AddonInstallSigner
import com.stash.core.addons.AddonRateGate
import com.stash.core.addons.AddonTrust
import com.stash.data.download.lossless.AggregatorRateLimiter
import com.stash.data.download.lossless.relay.LosslessConfigFetcher
import com.stash.data.download.lossless.relay.RelaySigner
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Binds `:core:addons`' host ports to the app's existing pieces, so the add-on client paces,
 * pauses and signs exactly as the lossless sources already do.
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
 * First-party = served from the relay the ECDSA-signed lossless config names (today its
 * `tidal_relay_url`): the same rule `RelaySigner.isOwnerRelay` applied before #871, and the
 * one `BitchordClient` applies to the owner's relay. Same scheme, host and port, and a path at
 * or under the relay's. Never decided by anything an add-on says about itself. Phase 2 adds the
 * signed config's first-party add-on list here.
 */
@Singleton
class SignedConfigAddonTrust @Inject constructor(
    private val config: LosslessConfigFetcher,
) : AddonTrust {
    override fun isFirstParty(base: String): Boolean {
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
    }
}

/**
 * [AddonInstallSigner] on [RelaySigner]: the app build, the install signature over the payload,
 * and the supporter token, the headers `BitchordClient` sends the owner's relay on a `/stream`.
 * [HttpAddonClient][com.stash.core.addons.HttpAddonClient] calls it for first-party add-ons only.
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
