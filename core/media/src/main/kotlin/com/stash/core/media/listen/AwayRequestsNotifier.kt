package com.stash.core.media.listen

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.stash.core.media.R
import com.stash.core.model.listen.SessionInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** What a host who is away is told about requests. */
internal interface AwayRequestNotices {
    /** "[count] requests waiting": the one quiet notification; tapping it opens Stash. */
    fun waiting(count: Int)

    /** More (or fewer) wait now: the notification's number follows if it is still showing, and nothing is posted anew. */
    fun update(count: Int)

    /** Nothing waits, the host is back, or the session is over: the notification goes. */
    fun clear()
}

/**
 * The away host's quiet notice (Sessions UX spec §8): while this phone hosts a session from a pocket and requests
 * are waiting, one silent notification says how many. It is posted once, not per request: later requests only change
 * its number, and one the host swiped away stays away. It goes when the host is back in the booth, when nothing
 * waits any more, or when the session ends. With Auto-accept on nothing waits, so nothing is said.
 */
@Singleton
class AwayRequestsNotifier internal constructor(
    /** How many requests wait while this phone hosts and is away; null whenever it isn't hosting or isn't away. */
    private val waiting: Flow<Int?>,
    private val notices: AwayRequestNotices,
    private val scope: CoroutineScope,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        controller: ListenTogetherController,
    ) : this(
        waiting = controller.state.map(::waitingWhileAway).distinctUntilChanged(),
        notices = AwayRequestNotifications(context),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )

    /** Told already, in this run of waiting requests. */
    private var told = false

    /** Call once, at app start. */
    fun start() {
        scope.launch {
            waiting.collect { count ->
                when {
                    count == null || count == 0 -> if (told) { told = false; notices.clear() }
                    !told -> { told = true; notices.waiting(count) }
                    else -> notices.update(count)
                }
            }
        }
    }

    internal companion object {
        /** The waiting requests of a session this phone hosts while away; null when it isn't hosting, or is in the booth. */
        fun waitingWhileAway(state: ListenTogetherState): Int? {
            val room = state as? ListenTogetherState.InRoom ?: return null
            val session = room.session ?: return null
            if (!room.isHost || session.presence != SessionInfo.AWAY) return null
            return room.requests.size
        }
    }
}

/** [AwayRequestNotices] as an Android notification on the station's silent channel. Best effort. */
internal class AwayRequestNotifications(private val context: Context) : AwayRequestNotices {
    override fun waiting(count: Int) = post(count)

    override fun update(count: Int) {
        // Only while it is still showing: one the host swiped away isn't brought back by the next request.
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.activeNotifications.any { it.id == ID }) post(count)
    }

    override fun clear() = NotificationManagerCompat.from(context).cancel(ID)

    private fun post(count: Int) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        ensureChannel()
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { PendingIntent.getActivity(context, ID, it, flags) }
        val n = NotificationCompat.Builder(context, AutoLiveNotifications.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(context.resources.getQuantityString(R.plurals.away_requests_title, count, count))
            .setContentText(context.getString(R.string.away_requests_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        try {
            nm.notify(ID, n)
        } catch (e: SecurityException) {
            // Android 13+ without the notification permission: the requests are in the booth when the host is back.
            Log.w(TAG, "the away notice wasn't shown: no permission")
        }
    }

    /** The auto-live notice's channel ("Your station", silent): both are the station speaking quietly to its host. */
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(AutoLiveNotifications.CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(AutoLiveNotifications.CHANNEL_ID, context.getString(R.string.auto_live_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private companion object {
        const val TAG = "AwayRequests"
        const val ID = 9491
    }
}
