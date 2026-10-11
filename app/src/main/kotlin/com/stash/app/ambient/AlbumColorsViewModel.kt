package com.stash.app.ambient

import androidx.lifecycle.ViewModel
import com.stash.core.ui.ambient.AlbumColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** The scaffold's handle on the app-wide [AlbumColorSource] (activity-scoped, as the scaffold is). */
@HiltViewModel
class AlbumColorsViewModel @Inject constructor(source: AlbumColorSource) : ViewModel() {
    val colors: StateFlow<AlbumColors> = source.colors
}
