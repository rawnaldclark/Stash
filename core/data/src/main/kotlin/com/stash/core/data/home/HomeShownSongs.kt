package com.stash.core.data.home

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The songs Home's shelves show right now, by shelf, so another shelf doesn't repeat them: the record's mixes on
 * Home ("From your record", feature/record) publish theirs, and "Because you like" leaves those out. Keys are
 * [BecauseYouLike.songKey] (main act and title, loose). In memory only: each shelf publishes again when it's made.
 */
@Singleton
class HomeShownSongs @Inject constructor() {

    private val bySource = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    /** Each shelf's songs now, by the shelf that published them. */
    val keys: StateFlow<Map<String, Set<String>>> = bySource.asStateFlow()

    /** [source]'s songs now (replacing what it published before), as (title, artist) pairs. */
    fun publish(source: String, songs: Collection<Pair<String, String>>) {
        val keys = songs.mapTo(HashSet()) { (title, artist) -> BecauseYouLike.songKey(title, artist) }
        bySource.update { it + (source to keys) }
    }

    companion object {
        /** The record's mixes on Home ("From your record"). */
        const val RECORD_MIXES = "record_mixes"
    }
}
