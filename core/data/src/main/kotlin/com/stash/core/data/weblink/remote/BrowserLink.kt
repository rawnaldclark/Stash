package com.stash.core.data.weblink.remote

import kotlinx.coroutines.flow.StateFlow

/**
 * What playing this phone's queue on a linked browser needs from the phone's remote socket (Remote v2, spec 2026-11-03 §2.2).
 * [WebRemote] is the one; `core:media`'s `BrowserOutputs` drives browsers through it.
 */
interface BrowserLink {
    /** Every browser whose socket is open and that has said what it plays, by device id. */
    val live: StateFlow<Map<String, BrowserState>>

    /** The space's verified browsers, id → name, open tab or not. */
    val linked: StateFlow<Map<String, String>>

    /** This phone's device id in the space, or null before the socket first opened. */
    val deviceId: String?

    /** [device] plays this phone's queue: the socket stays open (in the background too) until [unpin]. */
    fun pin(device: String)

    fun unpin()

    /** Sends [command] to [device], in order with every other command. */
    fun sendTo(device: String, command: RemoteCommand)

    /** Tells [device] to stop playing this phone's queue, pausing unless [pause] is false. */
    fun release(device: String, pause: Boolean)
}
