package com.stash.core.data.share

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot handoff for an incoming `stash://track` share link: MainActivity
 * parses the link and deposits a search query; SearchViewModel consumes it
 * when the deep-link navigation lands on the Search tab. Survives the
 * cold-start gap between intent parsing and ViewModel creation without
 * threading nav arguments through the typed route graph.
 */
@Singleton
class SharedTrackLinkHolder @Inject constructor() {

    private val pending = AtomicReference<String?>(null)

    fun set(query: String) {
        pending.set(query)
    }

    /** Returns the pending query at most once. */
    fun consume(): String? = pending.getAndSet(null)
}
