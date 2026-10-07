package com.stash.feature.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.weblibrary.WebLibraryExportResult
import com.stash.core.data.weblibrary.WebLibraryExporter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where "Export for Stash on the web" is. */
sealed interface WebLibraryExportState {
    data object Idle : WebLibraryExportState
    data object Working : WebLibraryExportState
    data class Done(val result: WebLibraryExportResult) : WebLibraryExportState
    data object Failed : WebLibraryExportState
}

/**
 * Settings › Library & Storage › "Export for Stash on the web": writes the web player's library file to the
 * document the user created with the system file picker ([WebLibraryExporter]). Its own small ViewModel so the
 * big [SettingsViewModel] stays as it is.
 */
@HiltViewModel
class WebLibraryExportViewModel @Inject constructor(
    private val exporter: WebLibraryExporter,
) : ViewModel() {

    private val _state = MutableStateFlow<WebLibraryExportState>(WebLibraryExportState.Idle)
    val state: StateFlow<WebLibraryExportState> = _state.asStateFlow()

    fun export(uri: Uri) {
        if (_state.value is WebLibraryExportState.Working) return
        _state.value = WebLibraryExportState.Working
        viewModelScope.launch {
            _state.value = exporter.export(uri).fold(
                onSuccess = { WebLibraryExportState.Done(it) },
                onFailure = { WebLibraryExportState.Failed },
            )
        }
    }

    /** Closes the result dialog. While the file is being written there is nothing to close. */
    fun dismiss() {
        if (_state.value !is WebLibraryExportState.Working) _state.value = WebLibraryExportState.Idle
    }
}
