package com.stash.core.data.radio

import com.stash.core.data.prefs.RadioEnginePreference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Picks the engine a station comes from, and is the only place that decides.
 *
 * Two rules make the YouTube Music default safe. First, a chosen engine that is
 * not bound (a build without the extension) is simply absent, so the Stash engine
 * takes over — that keeps P1 behaviour-neutral on its own. Second, a chosen engine
 * that is bound but can't serve right now ([RadioEngine.available]: no linked
 * account, dead cookie) falls back the same way, so a fresh install plays stations
 * instead of showing "couldn't start radio".
 */
@Singleton
class RadioEngines @Inject constructor(
    engines: Set<@JvmSuppressWildcards RadioEngine>,
    private val preference: RadioEnginePreference,
) {

    private val byId: Map<RadioEngineId, RadioEngine> = engines.associateBy { it.id }

    fun of(id: RadioEngineId): RadioEngine? = byId[id]

    /** Every engine this build has, in preference order for a settings list. */
    fun all(): List<RadioEngine> = RadioEngineId.entries.mapNotNull(byId::get)

    /** The engine to build a station with: the chosen one when it can serve, else the Stash engine. */
    suspend fun active(): RadioEngine {
        val chosen = of(preference.current())
        if (chosen != null && chosen.available()) return chosen
        return of(RadioEngineId.STASH) ?: chosen
            ?: error("No radio engine is bound: the Stash engine must always be in the graph")
    }

    companion object {
        /**
         * One engine, no store. Hand-built players and test fixtures use this so a
         * station test keeps driving `RadioStationGenerator` exactly as before.
         */
        fun single(engine: RadioEngine): RadioEngines =
            RadioEngines(setOf(engine), RadioEnginePreference.Stash)
    }
}
