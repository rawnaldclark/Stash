package com.stash.data.download.lossless.tidal

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stash.core.addons.DefaultAddonEntry
import com.stash.data.download.lossless.relay.TidalRelay
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

private val Context.tidalDebugDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "tidal_debug",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * DEBUG BUILDS ONLY: the developer instruments for the Tidal rung's two wires (plan §5.4), set in
 * Settings › Playback on a debuggable build.
 *
 *  - [Wire]: Auto (the signed config decides, decision D9), or force the legacy relay or the v1
 *    add-on.
 *  - An **owner server override**: an origin (`https://staging.example`, or
 *    `http://127.0.0.1:8797` for `wrangler dev` over `adb reverse`) that stands in, in memory, for
 *    the signed config's `tidal_relay_url` (legacy wire, signing kept) and for the first-party
 *    Tidal default add-on (`<origin>/v1/<config token>/manifest.json`, v1 wire). The token and the
 *    relay key stay the live config's: only the origin is typed, never a secret.
 *
 * A release build ignores both whatever is stored ([honored]), the rule the force-source toggles
 * learned the hard way (#429: a pref that outlives its UI is a silent single point of failure),
 * and stores nothing when asked to.
 *
 * Reading a staging run's logs: [state] is Auto with no override until the store has been read, a
 * moment after a cold start, so the very first lookups of a debug build's process can go to the
 * live server on the wire the config picks (review-881 N8; left as it is, a debug-only window).
 */
@Singleton
class TidalDebugSettings internal constructor(
    private val store: DataStore<Preferences>?,
    /** Whether this build honours the instruments: debuggable builds only. */
    val honored: Boolean,
    /** Where [state] follows the store: its own IO scope in the app; a test passes its own. */
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** Says that the store couldn't be read: once. */
    private val warn: (String) -> Unit = { Log.w(TAG, it) },
) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        context.tidalDebugDataStore,
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
    )

    enum class Wire { AUTO, LEGACY, V1 }

    /** What is set. [origin] null: no override. */
    data class State(val wire: Wire = Wire.AUTO, val origin: String? = null)

    /**
     * The stored state as it applies to this build: [State] defaults on a release build. A store
     * that can't be read reads as nothing set, for the moment only: it is read again, after a pause
     * that grows to a minute, and followed from then on. A `catch` here ended the flow, so one failed
     * read left the defaults in place until the process died (seen as a CI timeout of
     * `TidalWiresRouteTest`, review-888 round 2).
     */
    val state: StateFlow<State> = (store?.data ?: kotlinx.coroutines.flow.flowOf(emptyPreferences()))
        .retryWhen { cause, attempt ->
            if (attempt == 0L) warn("debug settings unreadable (${cause.javaClass.simpleName}); defaults until they read again")
            emit(emptyPreferences())
            delay(retryDelayMs(attempt))
            true
        }
        .map { p ->
            if (!honored) {
                State()
            } else {
                State(
                    wire = p[WIRE]?.let { w -> Wire.entries.firstOrNull { it.name == w } } ?: Wire.AUTO,
                    origin = p[ORIGIN]?.let(::normaliseOrigin),
                )
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, State())

    /** Forces a wire. Nothing is stored on a build that would not honour it. */
    suspend fun setWire(wire: Wire) {
        if (!honored) return
        store?.edit { it[WIRE] = wire.name }
    }

    /** Sets the override origin; blank or invalid clears it. Returns what was stored: nothing on a build that would not honour it. */
    suspend fun setOrigin(raw: String?): String? {
        if (!honored) return null
        val origin = raw?.let(::normaliseOrigin)
        store?.edit { if (origin == null) it.remove(ORIGIN) else it[ORIGIN] = origin }
        if (origin != null) Log.i(TAG, "owner server override set (debug build)") else Log.i(TAG, "owner server override cleared")
        return origin
    }

    /** The relay the legacy wire uses: the override's origin with the config's token, else [configRelay]. */
    fun relay(configRelay: TidalRelay?): TidalRelay? {
        val origin = state.value.origin ?: return configRelay
        return TidalRelay(origin, configRelay?.token, userOwned = false)
    }

    /** The first-party Tidal default add-on the override stands for, or null (no override, or no token to build it with). */
    fun addonEntry(configRelay: TidalRelay?): DefaultAddonEntry? {
        val origin = state.value.origin ?: return null
        val token = configRelay?.token?.takeIf { it.isNotBlank() } ?: return null
        val url = origin.toHttpUrlOrNull()?.newBuilder()?.addPathSegment("v1")?.addPathSegment(token)?.addPathSegment("manifest.json")?.build()
            ?: return null
        return DefaultAddonEntry(url.toString(), enabled = true, firstParty = true)
    }

    companion object {
        private const val TAG = "TidalDebug"
        private val WIRE = stringPreferencesKey("wire")
        private val ORIGIN = stringPreferencesKey("owner_origin")

        /**
         * [client] for an `http:` base, which only the owner server override can give (local
         * development hosts on a debug build): the app's shared client speaks TLS only. Any other
         * base gets [client] itself. Android's network security config still allows cleartext to
         * 127.0.0.1 and localhost on debug builds only.
         */
        fun clientFor(client: okhttp3.OkHttpClient, url: okhttp3.HttpUrl): okhttp3.OkHttpClient =
            if (url.isHttps) {
                client
            } else {
                client.newBuilder().connectionSpecs(listOf(okhttp3.ConnectionSpec.MODERN_TLS, okhttp3.ConnectionSpec.CLEARTEXT)).build()
            }

        /** The pause before the store is read again after its [attempt]th failed read: 1 s, doubling, at most a minute. */
        internal fun retryDelayMs(attempt: Long): Long = minOf(1_000L shl attempt.coerceAtMost(6L).toInt(), 60_000L)

        /** For tests and release-like wiring: nothing stored, nothing honoured. */
        fun off(): TidalDebugSettings = TidalDebugSettings(null, honored = false)

        /**
         * A bare origin: https anywhere, or http on 127.0.0.1 / localhost (SPEC.md 1.1's
         * development hosts, reached over `adb reverse`). No path, query, fragment or credentials.
         */
        fun normaliseOrigin(raw: String): String? {
            val u = raw.trim().trimEnd('/').toHttpUrlOrNull() ?: return null
            if (u.username.isNotEmpty() || u.password.isNotEmpty() || u.query != null || u.fragment != null) return null
            if (u.encodedPath != "/") return null
            val local = u.host == "127.0.0.1" || u.host == "localhost"
            if (u.scheme != "https" && !(u.scheme == "http" && local)) return null
            return "${u.scheme}://${u.host}${if (u.port != okhttp3.HttpUrl.defaultPort(u.scheme)) ":${u.port}" else ""}"
        }
    }
}
