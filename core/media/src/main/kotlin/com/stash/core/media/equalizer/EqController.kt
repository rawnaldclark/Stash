// EqController.kt
package com.stash.core.media.equalizer

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The single writer of [EqState]. UI emits events here; AudioProcessors
 * read [state] on every buffer.
 *
 * Construction performs a synchronous (`runBlocking`) read from disk so
 * the controller is fully restored before any AudioProcessor is built —
 * the Hilt graph guarantees ordering by declaring controller as a
 * constructor dependency of the processors. This is what kills the
 * legacy init race.
 *
 * Persistence is debounced 200 ms so slider drags don't flood DataStore;
 * [flush] is called from app `onPause` to force an immediate write.
 */
@Singleton
class EqController @Inject constructor(
  private val store: EqStore,
  private val migration: EqMigration,
) {
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private val _state = MutableStateFlow(EqState())
  val state: StateFlow<EqState> = _state.asStateFlow()

  private var pendingWrite: Job? = null
  @Volatile private var initDone = false

  init {
    runBlocking {
      migration.migrateIfNeeded()
      _state.value = store.read()
      initDone = true
    }
  }

  /** Test helper. */
  internal suspend fun awaitInit() {
    while (!initDone) delay(1)
  }

  fun setEnabled(enabled: Boolean) = update { it.copy(enabled = enabled) }

  fun setBandGain(bandIndex: Int, dB: Float) = update { s ->
    val clamped = dB.coerceIn(-12f, 12f)
    val newGains = s.gainsDb.copyOf().also { it[bandIndex] = clamped }
    s.copy(gainsDb = newGains, presetId = "custom")
  }

  fun setPreampDb(dB: Float) = update { it.copy(preampDb = dB.coerceIn(-12f, 12f)) }

  fun setBassBoostDb(dB: Float) = update { it.copy(bassBoostDb = dB.coerceIn(0f, 15f)) }

  fun setPreset(id: String) = update { s ->
    val preset = PresetCatalog.byId(id) ?: s.customPresets.firstOrNull { it.id == id }
    if (preset == null) s
    else s.copy(presetId = id, gainsDb = preset.gainsDb.copyOf(), preampDb = preset.preampDb)
  }

  fun saveCurrentAsPreset(name: String) = update { s ->
    val newPreset = NamedPreset(
      id = "u_" + System.currentTimeMillis(),
      name = name,
      gainsDb = s.gainsDb.copyOf(),
      preampDb = s.preampDb,
    )
    s.copy(customPresets = s.customPresets + newPreset, presetId = newPreset.id)
  }

  fun deleteCustomPreset(id: String) = update { s ->
    s.copy(customPresets = s.customPresets.filterNot { it.id == id })
  }

  /** Force an immediate persist — call from app pause/stop. */
  suspend fun flush() {
    pendingWrite?.cancel()
    store.write(_state.value)
  }

  private fun update(transform: (EqState) -> EqState) {
    _state.value = transform(_state.value)
    pendingWrite?.cancel()
    pendingWrite = scope.launch {
      delay(DEBOUNCE_MS)
      store.write(_state.value)
    }
  }

  companion object {
    private const val DEBOUNCE_MS = 200L
  }
}
