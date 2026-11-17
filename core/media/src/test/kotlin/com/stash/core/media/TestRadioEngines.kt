package com.stash.core.media

import com.stash.core.data.radio.LastFmRadioEngine
import com.stash.core.data.radio.RadioEngines
import com.stash.core.data.radio.RadioStationGenerator

/**
 * A hand-built player's stations: the Stash engine on top of the
 * [RadioStationGenerator] mock the test already drives, with no preference store.
 *
 * Keeping the generator as the thing tests stub means the station suites did not
 * have to change when the player started depending on [RadioEngines] — they still
 * assert on `start`/`nextBatch` of the same mock, and the real adapter in between
 * is the same code production runs.
 */
internal fun radioEngines(generator: RadioStationGenerator): RadioEngines =
    RadioEngines.single(LastFmRadioEngine(generator))
