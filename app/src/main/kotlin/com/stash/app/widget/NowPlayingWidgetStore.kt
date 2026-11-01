package com.stash.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.util.AtomicFile
import com.stash.core.media.PlaybackResumer
import com.stash.core.media.widget.NowPlayingWidgetState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** What the widget draws: the song (null = nothing playing), its cover and its colours. */
data class WidgetModel(
    val state: NowPlayingWidgetState?,
    val cover: Bitmap?,
    val accents: WidgetAccents,
) {
    companion object {
        val Empty = WidgetModel(state = null, cover = null, accents = WidgetAccents.Brand)
    }
}

/**
 * The widget's state, kept on disk (`files/now_playing_widget/`: a small JSON file plus the cover as
 * a PNG) so a widget drawn in a fresh process (the launcher restarting, the system re-binding
 * widgets after a reboot, process death) still shows the last song, and in memory as a flow the
 * running Glance composition follows.
 *
 * The first read in a process with no file yet (the update that adds the widget) seeds it from the
 * saved queue, the song the widget's Play resumes, so a newly placed widget shows the last song
 * instead of "Nothing playing".
 *
 * All access is serialized by one mutex and runs on [Dispatchers.IO].
 */
@Singleton
class NowPlayingWidgetStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val coverLoader: WidgetCoverLoader,
    private val resumer: PlaybackResumer,
) {
    @Serializable
    private data class Stored(
        val state: NowPlayingWidgetState? = null,
        /** The artUrl [coverFile] was loaded from; the cover only counts while it equals the state's. */
        val coverKey: String? = null,
        val accents: WidgetAccents? = null,
    )

    private val dir = File(context.filesDir, "now_playing_widget")
    private val stateFile = AtomicFile(File(dir, "state.json"))
    private val coverFile = File(dir, "cover.png")
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    private val model = MutableStateFlow<WidgetModel?>(null)

    // Guarded by mutex.
    private var stored: Stored? = null
    private var coverBitmap: Bitmap? = null

    /** A cover URL that failed in this process: not retried on every redraw (it is on the next song). */
    private var failedCoverKey: String? = null

    /** Every model after the first [current]; the running widget composition collects it. */
    val models: Flow<WidgetModel> = model.filterNotNull()

    /** The model to draw now, loading it (and the cover, if missing) on first use. */
    suspend fun current(): WidgetModel = locked {
        ensureCover()
        emit()
    }

    /**
     * Stores [state]. The cover is fetched now when [fetchCover] (a widget is on the home screen),
     * else on the next [current]. Returns whether anything the widget draws changed.
     */
    suspend fun publish(state: NowPlayingWidgetState, fetchCover: Boolean): Boolean = locked {
        val before = stored!!
        if (before.state == state) return@locked false
        stored = before.copy(state = state)
        if (fetchCover) ensureCover()
        save()
        emit()
        true
    }

    /** The service stopped: the stored song is paused. Returns whether that changed anything. */
    suspend fun markPaused(): Boolean = locked {
        val before = stored!!
        val state = before.state?.takeIf { it.isPlaying } ?: return@locked false
        stored = before.copy(state = state.copy(isPlaying = false))
        save()
        emit()
        true
    }

    /** Whether any Stash widget is on a home screen right now. */
    fun hasPlacedWidgets(): Boolean = try {
        val manager = AppWidgetManager.getInstance(context)
        RECEIVERS.any { manager.getAppWidgetIds(ComponentName(context, it)).isNotEmpty() }
    } catch (e: Exception) {
        false // no widget service on this device (some TV / car builds)
    }

    private suspend fun <T> locked(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            block()
        }
    }

    private suspend fun ensureLoaded() {
        if (stored != null) return
        val loaded = read()
        if (loaded != null) {
            stored = loaded
        } else {
            stored = seedFromSavedQueue()
            save()
        }
        val s = stored!!
        coverBitmap = if (s.coverKey != null && s.coverKey == s.state?.artUrl) decode() else null
    }

    /** Makes sure the cover on hand is the stored song's (loading it once if not). */
    private suspend fun ensureCover() {
        val s = stored!!
        val url = s.state?.artUrl
        if (url == null || (url == s.coverKey && coverBitmap != null) || url == failedCoverKey) return
        val cover = coverLoader.load(url)
        if (cover == null) {
            failedCoverKey = url
            coverBitmap = null
            stored = s.copy(coverKey = null, accents = null)
            coverFile.delete()
        } else {
            coverBitmap = cover.bitmap
            stored = s.copy(coverKey = url, accents = cover.accents)
            writeCover(cover.bitmap)
        }
        save()
    }

    private fun emit(): WidgetModel {
        val s = stored!!
        val coverIsCurrent = s.coverKey != null && s.coverKey == s.state?.artUrl
        val next = WidgetModel(
            state = s.state,
            cover = if (coverIsCurrent) coverBitmap else null,
            accents = if (coverIsCurrent) s.accents ?: WidgetAccents.Brand else WidgetAccents.Brand,
        )
        model.value = next
        return next
    }

    private suspend fun seedFromSavedQueue(): Stored = try {
        val plan = resumer.buildResumePlan()
        val track = plan?.tracks?.getOrNull(plan.startIndex)
        if (track == null || track.title.isBlank()) {
            Stored()
        } else {
            Stored(
                state = NowPlayingWidgetState(
                    title = track.title.trim(),
                    artist = track.artist.trim(),
                    // The cover the service's resumed item shows (toAutoMediaItem).
                    artUrl = (track.albumArtUrl ?: track.albumArtPath)?.takeIf { it.isNotBlank() },
                    isPlaying = false,
                ),
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "seed from the saved queue failed: ${e.javaClass.simpleName}")
        Stored()
    }

    private fun read(): Stored? = try {
        if (!stateFile.baseFile.exists()) null else json.decodeFromString(Stored.serializer(), stateFile.readFully().decodeToString())
    } catch (e: Exception) {
        Log.w(TAG, "widget state unreadable, starting over: ${e.javaClass.simpleName}")
        null
    }

    private fun save() {
        val s = stored ?: return
        dir.mkdirs()
        val out = stateFile.startWrite()
        try {
            out.write(json.encodeToString(Stored.serializer(), s).encodeToByteArray())
            stateFile.finishWrite(out)
        } catch (e: Exception) {
            stateFile.failWrite(out)
            Log.w(TAG, "widget state not saved: ${e.javaClass.simpleName}")
        }
    }

    private fun decode(): Bitmap? = try {
        if (coverFile.exists()) BitmapFactory.decodeFile(coverFile.path) else null
    } catch (e: Exception) {
        null
    }

    private fun writeCover(bitmap: Bitmap) {
        dir.mkdirs()
        val tmp = File(dir, "cover.png.tmp")
        try {
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!tmp.renameTo(coverFile)) {
                coverFile.delete()
                tmp.renameTo(coverFile)
            }
        } catch (e: Exception) {
            tmp.delete()
            Log.w(TAG, "widget cover not saved: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "StashWidget"

        /** Every receiver that hosts [NowPlayingWidget]. */
        val RECEIVERS = listOf(NowPlayingWidgetReceiver::class.java, NowPlayingSquareWidgetReceiver::class.java)
    }
}
