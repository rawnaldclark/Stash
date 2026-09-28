package com.stash.core.media.diagnostics

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.stash.core.data.diagnostics.DiagnosticsContributor
import com.stash.core.data.prefs.AutoplayRadioPreference
import com.stash.core.data.prefs.CrossfadePreference
import com.stash.core.media.equalizer.EqStore
import com.stash.core.media.equalizer.LoudnessStore
import com.stash.core.media.equalizer.PresetCatalog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * The diagnostics bundle's "Playback" section: what a "this song won't play / it skips /
 * it stops" report needs, and what the log tail rarely still holds:
 *
 *  - the audio settings that change what the player does: crossfade, equalizer, loudness
 *    normalization and autoplay radio (quality and sources belong to the "Lossless" section)
 *  - the outputs Android sends media to right now
 *  - the last resolves this app run: which source served each track, and how long it took
 *  - the last playback errors this app run: the error, what the player did about it, and
 *    where the stream came from
 *
 * Track ids only, never a title, artist, URL or path: [PlaybackDiagnosticsLog] doesn't
 * take them in. Every block is fault-isolated, so one failing read costs one line.
 */
@Singleton
class PlaybackDiagnosticsContributor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val crossfade: CrossfadePreference,
    private val autoplayRadio: AutoplayRadioPreference,
    private val eqStore: EqStore,
    private val loudnessStore: LoudnessStore,
    private val log: PlaybackDiagnosticsLog,
) : DiagnosticsContributor {

    override val title: String = "Playback"

    override suspend fun section(): String = buildString {
        appendLine(block("settings") { settingsBlock() })
        appendLine(block("output") { outputBlock() })
        appendLine(block("recent resolves") { resolvesBlock() })
        append(block("recent playback errors") { errorsBlock() })
    }

    private inline fun block(name: String, body: () -> String): String =
        runCatching(body).getOrElse { "[$name unavailable: ${it.message}]" }

    private suspend fun settingsBlock(): String {
        val eq = eqStore.read()
        return buildString {
            appendLine("Crossfade:              " + if (crossfade.enabled.first()) "on · ${crossfade.durationMs.first() / 1000} s" else "off")
            appendLine("Equalizer:              " + if (eq.enabled) "on · ${presetName(eq.presetId)}" else "off")
            appendLine("Loudness normalization: " + onOff(loudnessStore.read().enabled))
            append("Autoplay radio:         " + onOff(autoplayRadio.enabled.first()))
        }
    }

    /** A built-in preset by name. A saved preset's name is the user's own words, so it stays out. */
    private fun presetName(id: String): String =
        PresetCatalog.byId(id)?.name ?: if (id == "custom") "Custom" else "a saved preset"

    private fun outputBlock(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return "Output now:             n/a (Android <13)"
        val audio = context.getSystemService(AudioManager::class.java)
            ?: return "Output now:             unknown (no audio service)"
        val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        // Types only: a device's product name and address can be the user's ("Sam's Buds", a MAC).
        val names = audio.getAudioDevicesForAttributes(media).map { deviceName(it.type) }.distinct()
        return "Output now:             " + names.ifEmpty { listOf("none reported") }.joinToString(", ")
    }

    /** [AudioDeviceInfo] types in plain words; one this list doesn't know prints its number. */
    private fun deviceName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired headphones"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth a2dp"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth sco (call audio)"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "bluetooth le headset"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "bluetooth le speaker"
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> "bluetooth le broadcast"
        AudioDeviceInfo.TYPE_HEARING_AID -> "hearing aid"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "usb headset"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "usb"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "usb accessory"
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> "hdmi"
        AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_AUX_LINE -> "line out"
        AudioDeviceInfo.TYPE_DOCK, AudioDeviceInfo.TYPE_DOCK_ANALOG -> "dock"
        AudioDeviceInfo.TYPE_BUS -> "car (bus)"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "remote submix (cast or capture)"
        else -> "type $type"
    }

    private fun resolvesBlock(): String {
        val rows = log.recentResolves()
        if (rows.isEmpty()) return "Recent resolves: none this app run"
        val now = log.clock()
        return "Recent resolves, this app run (newest first):\n" + rows.joinToString("\n") {
            "  ${ago(now - it.atMs)} · track ${it.trackId} · ${it.servedBy} · ${it.elapsedMs} ms · " +
                "lossless ${onOff(it.lossless)}"
        }
    }

    private fun errorsBlock(): String {
        val rows = log.recentErrors()
        if (rows.isEmpty()) return "Recent playback errors: none this app run"
        val now = log.clock()
        return "Recent playback errors, this app run (newest first):\n" + rows.joinToString("\n") {
            "  ${ago(now - it.atMs)} · track ${it.trackId ?: "?"} · ${it.code}" +
                (it.httpStatus?.let { status -> " (HTTP $status)" } ?: "") +
                " · ${it.branch} · origin=${it.origin ?: "none"} · scheme=${it.scheme ?: "none"}"
        }
    }

    private fun ago(ms: Long): String {
        val s = ms.coerceAtLeast(0) / 1000
        return when {
            s < 60 -> "$s s ago"
            s < 3600 -> "${s / 60} min ago"
            else -> "${s / 3600} h ago"
        }
    }

    private fun onOff(v: Boolean) = if (v) "on" else "off"
}
