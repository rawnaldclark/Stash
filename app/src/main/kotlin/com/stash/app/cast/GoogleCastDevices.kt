package com.stash.app.cast

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.stash.core.media.cast.CastConnection
import com.stash.core.media.cast.CastDevice
import com.stash.core.media.cast.CastDevices
import com.stash.app.R
import com.stash.core.media.cast.CastRemote
import com.stash.core.media.diagnostics.PlaybackDiagnosticsLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [CastDevices] over the Google Cast SDK (spec 2026-10-06 §2).
 *
 * Discovery and connecting go through [MediaRouter] directly — Stash draws its
 * own picker in Compose, because `MediaRouteButton` needs a FragmentActivity
 * and an AppCompat theme. Selecting a cast route makes the SDK start a
 * session; [sessionListener] turns that into [remote].
 *
 * Main thread only. Without Play Services [connection] stays Unavailable and
 * the cast button stays hidden. With them it is Disconnected from the start,
 * but the Cast framework itself only starts when the speaker sheet first scans
 * ([startScan]), so a phone that never casts never runs it.
 */
@Singleton
class GoogleCastDevices @Inject constructor(
    @ApplicationContext private val context: Context,
    private val diagnostics: PlaybackDiagnosticsLog,
) : CastDevices {

    private val _connection = MutableStateFlow<CastConnection>(CastConnection.Unavailable)
    override val connection: StateFlow<CastConnection> = _connection.asStateFlow()

    private val _devices = MutableStateFlow<List<CastDevice>>(emptyList())
    override val devices: StateFlow<List<CastDevice>> = _devices.asStateFlow()

    private val _remote = MutableStateFlow<CastRemote?>(null)
    override val remote: StateFlow<CastRemote?> = _remote.asStateFlow()

    private var castContext: CastContext? = null
    private val router: MediaRouter by lazy { MediaRouter.getInstance(context) }
    private val selector: MediaRouteSelector by lazy {
        MediaRouteSelector.Builder()
            .addControlCategory(CastMediaControlIntent.categoryForCast(StashCastOptionsProvider.RECEIVER_APP_ID))
            .build()
    }
    private var scanning = false

    /**
     * Gives up on a connect that never answers. Nothing else would: a select
     * MediaRouter ignores calls no callback at all, and a speaker that never
     * replies leaves the SDK waiting, so "Connecting…" would stay forever.
     */
    private val handler = Handler(Looper.getMainLooper())
    private val connectTimeout = Runnable { onConnectTimedOut() }

    /** Ends a session that was suspended (network lost) and never came back. */
    private val suspendTimeout = Runnable {
        Log.w(TAG, "suspended session never resumed — ending it")
        diagnostics.recordCast("suspended session never resumed, ended")
        onDisconnected()
        endSessionAndReleaseRoute()
    }

    private val routerCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = publishRoutes()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = publishRoutes()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = publishRoutes()
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            diagnostics.recordCast("session starting")
            setConnection(CastConnection.Connecting(session.deviceName()))
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) {
            diagnostics.recordCast("session started")
            onConnected(session)
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            diagnostics.recordCast("session resumed")
            handler.removeCallbacks(suspendTimeout)
            val current = _remote.value as? GoogleCastRemote
            if (current != null && current.session === session) {
                // Back from a suspend: the speaker kept playing, so keep the same
                // remote and the playback service never leaves cast mode.
                current.onResumed()
                setConnection(CastConnection.Connected(session.deviceName()))
            } else {
                onConnected(session)
            }
        }

        // Drop the remote at "ending", not "ended": by "ended" the receiver has
        // already cleared its status, and the playback service needs the last
        // position to hand playback back to the phone.
        override fun onSessionEnding(session: CastSession) = onDisconnected()
        override fun onSessionEnded(session: CastSession, error: Int) {
            diagnostics.recordCast("session ended (code $error)")
            onDisconnected()
        }
        // A suspend is a brief network loss. The speaker plays on, and the SDK
        // either resumes the session (onSessionResumed) or ends it
        // (onSessionEnded); handing playback back to the phone here would stop
        // the speaker's music on every Wi-Fi hiccup. If neither happens within
        // SUSPEND_TIMEOUT_MS, suspendTimeout ends it, so no half-dead session
        // (and no route MediaRouter still holds as selected) is left behind.
        override fun onSessionSuspended(session: CastSession, reason: Int) {
            Log.i(TAG, "session suspended ($reason); waiting for resume")
            diagnostics.recordCast("session suspended (reason $reason)")
            handler.removeCallbacks(suspendTimeout)
            handler.postDelayed(suspendTimeout, SUSPEND_TIMEOUT_MS)
        }
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            Log.w(TAG, "session start failed: $error")
            diagnostics.recordCast("session start failed (code $error)")
            val name = (_connection.value as? CastConnection.Connecting)?.deviceName
            onDisconnected()
            releaseSelectedRoute()
            name?.let(::showConnectFailed)
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            diagnostics.recordCast("session resume failed (code $error)")
            onDisconnected()
        }
    }

    /** True once [ensureStarted] has asked for the CastContext. */
    private var starting = false

    init {
        // Only a local check here, so the button can show. The Cast framework
        // itself (which looks for speakers on the network now and then while
        // the app is open, once it exists) starts on the first open of the
        // speaker sheet, never just because something played.
        val playServices = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        if (playServices == ConnectionResult.SUCCESS) {
            setConnection(CastConnection.Disconnected)
        } else {
            Log.i(TAG, "no Play Services ($playServices) — Cast stays off")
        }
    }

    /** Starts the Cast framework, once. Until it's ready, scanning waits and connect does nothing. */
    private fun ensureStarted() {
        if (starting || _connection.value == CastConnection.Unavailable) return
        starting = true
        CastContext.getSharedInstance(context, ContextCompat.getMainExecutor(context))
            .addOnSuccessListener { ready(it) }
            .addOnFailureListener {
                Log.w(TAG, "Cast unavailable", it)
                diagnostics.recordCast("Cast failed to start")
                setConnection(CastConnection.Unavailable) // hides the button
            }
    }

    private fun ready(cast: CastContext) {
        castContext = cast
        cast.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
        cast.sessionManager.currentCastSession?.takeIf { it.isConnected }?.let(::onConnected)
        if (scanning) startScan()
    }

    private fun onConnected(session: CastSession) {
        handler.removeCallbacks(connectTimeout)
        handler.removeCallbacks(suspendTimeout)
        (_remote.value as? GoogleCastRemote)?.release()
        _remote.value = GoogleCastRemote(session, diagnostics)
        setConnection(CastConnection.Connected(session.deviceName()))
    }

    private fun onDisconnected() {
        handler.removeCallbacks(connectTimeout)
        handler.removeCallbacks(suspendTimeout)
        val old = _remote.value as? GoogleCastRemote
        old?.freeze()
        _remote.value = null // the service reads the last status from the wrapper, not from here
        old?.release()
        if (castContext != null) setConnection(CastConnection.Disconnected)
    }

    override fun startScan() {
        scanning = true
        if (castContext == null) return ensureStarted() // ready() resumes the scan
        router.addCallback(selector, routerCallback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        publishRoutes()
    }

    override fun stopScan() {
        scanning = false
        if (castContext == null) return
        router.removeCallback(routerCallback)
    }

    override fun connect(deviceId: String) {
        if (castContext == null) return // the speakers listed came from a started Cast
        val route = router.routes.firstOrNull { it.id == deviceId } ?: return
        // MediaRouter ignores selecting the route it already holds as selected,
        // without a callback. That happens when an earlier session ended without
        // releasing it (overnight, after a lost connection): the speaker is still
        // listed, but the select goes nowhere. Release it first.
        diagnostics.recordCast("connect requested")
        if (route.isSelected) {
            diagnostics.recordCast("stale speaker selection released")
            router.unselect(MediaRouter.UNSELECT_REASON_STOPPED)
        }
        setConnection(CastConnection.Connecting(route.name))
        handler.removeCallbacks(connectTimeout)
        handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
        router.selectRoute(route)
    }

    override fun disconnect() {
        if (_connection.value is CastConnection.Connecting) {
            // Cancel: there may be no session yet for the SDK to end.
            diagnostics.recordCast("connect cancelled")
            onDisconnected()
            endSessionAndReleaseRoute()
            return
        }
        castContext?.sessionManager?.endCurrentSession(/* stopCasting = */ true)
    }

    private fun onConnectTimedOut() {
        val name = (_connection.value as? CastConnection.Connecting)?.deviceName ?: return
        Log.w(TAG, "no answer from the speaker after ${CONNECT_TIMEOUT_MS}ms — giving up") // never its name: logcat goes into shared diagnostics
        diagnostics.recordCast("connect timed out after ${CONNECT_TIMEOUT_MS / 1000} s")
        onDisconnected()
        endSessionAndReleaseRoute()
        showConnectFailed(name)
    }

    private fun endSessionAndReleaseRoute() {
        castContext?.sessionManager?.endCurrentSession(/* stopCasting = */ true)
        releaseSelectedRoute()
    }

    /** Hands the selection back to the phone, so the next connect starts clean. */
    private fun releaseSelectedRoute() {
        if (castContext != null && !router.selectedRoute.isDefaultOrBluetooth) {
            router.unselect(MediaRouter.UNSELECT_REASON_STOPPED)
        }
    }

    private fun showConnectFailed(deviceName: String) {
        Toast.makeText(context, context.getString(R.string.cast_connect_failed, deviceName), Toast.LENGTH_LONG).show()
    }

    private fun publishRoutes() {
        _devices.value = router.routes
            .filter { !it.isDefaultOrBluetooth && it.isEnabled && it.matchesSelector(selector) }
            .map { CastDevice(id = it.id, name = it.name) }
            .sortedBy { it.name.lowercase() }
    }

    /** Also tells the diagnostics bundle, in fixed words (never the speaker's name). */
    private fun setConnection(connection: CastConnection) {
        _connection.value = connection
        diagnostics.castConnection = when (connection) {
            CastConnection.Unavailable -> "unavailable"
            CastConnection.Disconnected -> "disconnected"
            is CastConnection.Connecting -> "connecting"
            is CastConnection.Connected -> "connected"
        }
    }

    private fun CastSession.deviceName(): String = castDevice?.friendlyName ?: "Speaker"

    private companion object {
        const val TAG = "GoogleCastDevices"

        /** About what Spotify waits before it gives up on a speaker. */
        const val CONNECT_TIMEOUT_MS = 20_000L

        /** Long enough for a Wi-Fi reconnect or a phone waking from doze. */
        const val SUSPEND_TIMEOUT_MS = 60_000L
    }
}
