package com.stash.core.media.listen

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.stash.core.media.R
import com.stash.core.media.listen.ListenTogetherController.Command
import com.stash.core.model.community.Station
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** What auto-live tells the person outside the app. */
internal interface AutoLiveNotices {
    /** "<station> is on the air", with End session; tapping it opens Stash. */
    fun onAir(station: Station)

    /** The session is over: the notification goes. */
    fun clear()
}

/**
 * Auto-live's quiet notice (Sessions design §6): the first time in a day the station goes on the air by itself, a
 * silent notification says so and offers End session, so nobody is on the air without knowing. Later auto-lives
 * that day say nothing more (the LIVE pill always shows). It goes when the session ends.
 *
 * The day is the phone's own calendar day, and the last day told is kept across restarts.
 */
@Singleton
class AutoLiveNotifier internal constructor(
    private val wentLive: Flow<Station>,
    private val hosting: Flow<Boolean>,
    private val notices: AutoLiveNotices,
    private val lastDay: () -> Long,
    private val saveDay: (Long) -> Unit,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val zone: () -> ZoneId,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        autoLive: SessionAutoLive,
        controller: ListenTogetherController,
    ) : this(
        wentLive = autoLive.wentLive,
        hosting = controller.state.map { it is ListenTogetherState.InRoom && it.isHost && it.session != null }.distinctUntilChanged(),
        notices = AutoLiveNotifications(context),
        lastDay = { prefs(context).getLong(KEY_DAY, NO_DAY) },
        saveDay = { prefs(context).edit().putLong(KEY_DAY, it).apply() },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        clock = System::currentTimeMillis,
        zone = ZoneId::systemDefault,
    )

    /** Call once, at app start. */
    fun start() {
        scope.launch {
            // Auto-live asked to go live: the notice waits until the station is really on the air.
            wentLive.collect { station ->
                pending = station.takeIf { dayOf(clock(), zone()) != lastDay() }
                pendingAt = clock()
            }
        }
        scope.launch {
            hosting.collect { on ->
                val station = pending
                // Only a session that opened right after auto-live asked is auto-live's: one started by hand later isn't.
                if (on && station != null && clock() - pendingAt <= OPENING_MS) {
                    pending = null
                    saveDay(dayOf(clock(), zone()))
                    shown = true
                    notices.onAir(station)
                }
                // Off the air (ended here, from the notification, or by the room): the notice has nothing left to say.
                if (!on && shown) { shown = false; notices.clear() }
            }
        }
    }

    private var pending: Station? = null
    private var pendingAt = 0L
    private var shown = false

    internal companion object {
        const val NO_DAY = Long.MIN_VALUE
        const val OPENING_MS = 60_000L
        private const val KEY_DAY = "notice_day"
        private fun prefs(context: Context) = context.getSharedPreferences("auto_live", Context.MODE_PRIVATE)

        /** The calendar day [nowMs] falls on in [zone], as days since 1970. */
        fun dayOf(nowMs: Long, zone: ZoneId): Long = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().toEpochDay()
    }
}

/** [AutoLiveNotices] as an Android notification on its own silent channel. Best effort: the LIVE pill says it too. */
internal class AutoLiveNotifications(private val context: Context) : AutoLiveNotices {
    override fun onAir(station: Station) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        ensureChannel()
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { PendingIntent.getActivity(context, ID, it, flags) }
        val end = PendingIntent.getBroadcast(
            context, ID, Intent(context, AutoLiveEndReceiver::class.java).setAction(AutoLiveEndReceiver.ACTION_END), flags,
        )
        val text = context.getString(R.string.auto_live_notice_text)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(context.getString(R.string.auto_live_notice_title, station.name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .addAction(0, context.getString(R.string.auto_live_notice_end), end)
            .build()
        try {
            nm.notify(ID, n)
        } catch (e: SecurityException) {
            // Android 13+ without the notification permission: the LIVE pill says it.
            Log.w(TAG, "the auto-live notice wasn't shown: no permission")
        }
    }

    override fun clear() = NotificationManagerCompat.from(context).cancel(ID)

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, context.getString(R.string.auto_live_channel), NotificationManager.IMPORTANCE_LOW))
        }
    }

    internal companion object {
        private const val TAG = "AutoLive"
        const val CHANNEL_ID = "auto_live"
        const val ID = 9490
    }
}

/**
 * The auto-live notification's End session. Not exported: only the app's own PendingIntent reaches it. It ends the
 * session only if this phone is hosting one; otherwise it does nothing.
 */
@AndroidEntryPoint
class AutoLiveEndReceiver : BroadcastReceiver() {
    @Inject lateinit var controller: ListenTogetherController

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_END) return
        val room = controller.state.value
        if (room is ListenTogetherState.InRoom && room.isHost && room.session != null) controller.send(Command.End)
        NotificationManagerCompat.from(context).cancel(AutoLiveNotifications.ID)
    }

    companion object {
        const val ACTION_END = "com.stash.action.END_AUTO_LIVE"
    }
}
