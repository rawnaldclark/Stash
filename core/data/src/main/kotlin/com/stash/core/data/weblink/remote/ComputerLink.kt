package com.stash.core.data.weblink.remote

import kotlinx.coroutines.flow.StateFlow

/** The linked computers this phone is a remote for (Stash on a computer keeps its own queue; [WebRemote] is the socket). */
interface ComputerLink {
    /** Every linked computer whose socket is open and that has said what it plays, by device id. */
    val computers: StateFlow<Map<String, ComputerLive>>

    /** The space's verified computers, id → name, running or not. */
    val linkedComputers: StateFlow<Map<String, String>>

    /** Sends [command] to the computer [device], in order with every other command. */
    fun sendToComputer(device: String, command: ComputerCommand)

    /**
     * [on]: this phone is the remote for a computer, so the socket stays open in the background too (its notification and lock
     * screen drive it from there). False: the socket follows the app's foreground again.
     */
    fun hold(on: Boolean) = Unit
}
