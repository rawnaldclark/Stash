package com.stash.core.data.radio

import com.stash.core.model.Track
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Stash engine: [RadioStationGenerator]'s Last.fm similarity graph, wrapped in
 * the [RadioEngine] contract.
 *
 * A wrapper rather than making the generator implement the interface itself: the
 * generator's own tests drive it directly (and its session type is `internal`),
 * so leaving it untouched keeps that suite honest about the algorithm while this
 * class carries the adapter concern. `radioGenerator.start` is called once per
 * station; the returned [RadioStation] keeps calling `nextBatch` on the very same
 * session, so the cursor and no-repeat set live exactly where they always did.
 */
@Singleton
class LastFmRadioEngine @Inject constructor(
    private val generator: RadioStationGenerator,
) : RadioEngine {

    override val id: RadioEngineId = RadioEngineId.STASH

    override suspend fun start(seed: RadioSeed): Pair<RadioStation, List<Track>> {
        val (session, firstBatch) = generator.start(seed)
        val station = object : RadioStation {
            override suspend fun nextBatch(): List<Track> = generator.nextBatch(session)
        }
        return station to firstBatch
    }
}
