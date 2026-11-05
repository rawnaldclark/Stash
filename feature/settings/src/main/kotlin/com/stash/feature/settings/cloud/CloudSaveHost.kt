package com.stash.feature.settings.cloud

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.stash.core.data.repository.MusicRepository
import com.stash.core.model.CloudProvider
import com.stash.core.ui.components.CloudSaveTarget
import com.stash.core.ui.components.LocalSaveToCloud
import com.stash.data.cloud.CloudLibrary
import com.stash.data.cloud.upload.CloudUploads
import com.stash.data.cloud.upload.SavedElsewhere
import com.stash.feature.settings.R
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * A save's toast: [message] in words already ([CloudUploads.message]), then where songs stayed, whose
 * words live in string resources.
 */
data class CloudSaveToast(val message: String, val elsewhere: List<SavedElsewhere> = emptyList())

/** A [CloudSaveToast] in words: "Saving 2 songs. 3 songs are waiting on ron@gmail.com." */
internal fun CloudSaveToast.text(context: Context): String {
    val resources = context.resources
    val stayed = elsewhere.flatMap { place ->
        listOfNotNull(
            resources.getQuantityString(R.plurals.settings_cloud_gdrive_waiting_on, place.waiting, place.waiting, place.account).takeIf { place.waiting > 0 },
            resources.getQuantityString(R.plurals.settings_cloud_gdrive_already_in, place.inDrive, place.inDrive, place.account).takeIf { place.inDrive > 0 },
        )
    }
    return (listOf(message) + stayed).filter { it.isNotEmpty() }.joinToString(" ")
}

/**
 * Behind every "Save to Google Drive" menu entry: whether there is a Drive to save to, and the
 * save itself (a song, an album's or a playlist's downloads), with the toast that says what
 * happened ("Saving 9 songs to Google Drive. 3 songs aren't downloaded yet.").
 */
@HiltViewModel
class CloudSaveViewModel @Inject constructor(
    private val uploads: CloudUploads,
    private val music: MusicRepository,
    cloud: CloudLibrary,
) : ViewModel() {

    /** True while a Google Drive account is connected in a build that has Google Drive. */
    val connected: StateFlow<Boolean> = cloud.accounts
        .map { list -> uploads.available && list.any { it.provider == CloudProvider.GOOGLE_DRIVE } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _messages = MutableSharedFlow<CloudSaveToast>(extraBufferCapacity = 4)
    val messages: SharedFlow<CloudSaveToast> = _messages.asSharedFlow()

    fun save(target: CloudSaveTarget) {
        viewModelScope.launch {
            val message = try {
                uploads.save(trackIds(target)).let { CloudSaveToast(CloudUploads.message(it), it.elsewhere) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("CloudSave", "save to Drive failed: ${e.javaClass.simpleName}")
                CloudSaveToast("Couldn't save to Google Drive. Try again.")
            }
            _messages.tryEmit(message)
        }
    }

    /** Every song of the target, downloaded or not: the uploader counts the ones that aren't. */
    private suspend fun trackIds(target: CloudSaveTarget): List<Long> = when (target) {
        is CloudSaveTarget.Songs -> target.trackIds
        is CloudSaveTarget.Album -> music.getTracksByAlbum(target.name, target.artist).first().map { it.id }
        is CloudSaveTarget.Playlist -> music.getTracksByPlaylist(target.playlistId).first().map { it.id }
    }
}

/**
 * Provides [LocalSaveToCloud] to [content] while Google Drive is connected (so the menus show
 * "Save to Google Drive"), and toasts what each save did.
 */
@Composable
fun CloudSaveHost(
    viewModel: CloudSaveViewModel = hiltViewModel(),
    content: @Composable () -> Unit,
) {
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { Toast.makeText(context, it.text(context), Toast.LENGTH_LONG).show() }
    }
    val save = remember(viewModel) { viewModel::save }
    CompositionLocalProvider(LocalSaveToCloud provides if (connected) save else null) { content() }
}
