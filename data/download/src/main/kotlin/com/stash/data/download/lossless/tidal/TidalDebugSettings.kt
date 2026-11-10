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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
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
 * learned the hard way (#429: a pref that outlives its UI is a silent single point of failure).
 */
@Singleton
class TidalDebugSettings internal constructor(
    private val store: DataStore<Preferences>?,
    /** Whether this build honours the instruments: debuggable builds only. */
    val honored: Boolean,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        context.tidalDebugDataStore,
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
    )

    enum class Wire { AUTO, LEGACY, V1 }

    /** What is set. [origin] null: no override. */
    data class State(val wire: Wire = Wire.AUTO, val origin: String? = null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The stored state as it applies to this build: [State] defaults on a release build. */
    val state: StateFlow<State> = (store?.data ?: kotlinx.coroutines.flow.flowOf(emptyPreferences()))
        .catch { emit(emptyPreferences()) }
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

    suspend fun setWire(wire: Wire) {
        store?.edit { it[WIRE] = wire.name }
    }

    /** Sets the override origin; blank or invalid clears it. Returns what was stored. */
    suspend fun setOrigin(raw: String?): String? {
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
