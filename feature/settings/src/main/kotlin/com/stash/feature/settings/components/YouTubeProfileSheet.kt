package com.stash.feature.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.stash.core.data.youtube.YouTubeProfileRepository
import com.stash.core.ui.theme.StashTheme
import com.stash.data.ytmusic.YouTubeProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Settings › Accounts › YouTube Music › Profile (#664): which profile of the Google account to use. */
@HiltViewModel
class YouTubeProfileViewModel @Inject constructor(
    private val repository: YouTubeProfileRepository,
) : ViewModel() {

    data class UiState(
        /** Null while loading or after a failure. */
        val profiles: List<YouTubeProfile>? = null,
        val loading: Boolean = false,
        val failed: Boolean = false,
        /** The Brand page id in use; null = the account's own profile. */
        val currentPageId: String? = null,
        /** Ask which profile to use: right after connecting, when the account has more than one. */
        val askAfterConnect: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun load() {
        _state.update { it.copy(loading = true, failed = false) }
        viewModelScope.launch {
            val profiles = repository.list()
            val current = repository.current().pageId
            _state.update { it.copy(profiles = profiles, loading = false, failed = profiles == null, currentPageId = current) }
        }
    }

    /** Just connected: list the profiles, and ask only when there's a choice to make. */
    fun onConnected() {
        viewModelScope.launch {
            val profiles = repository.list() ?: return@launch
            if (profiles.size > 1) {
                _state.update {
                    it.copy(profiles = profiles, currentPageId = repository.current().pageId, askAfterConnect = true)
                }
            }
        }
    }

    fun askHandled() = _state.update { it.copy(askAfterConnect = false) }

    fun choose(profile: YouTubeProfile) {
        _state.update { it.copy(currentPageId = profile.pageId) }
        viewModelScope.launch { repository.choose(profile) }
    }

    fun chooseById(pageId: String) {
        _state.update { it.copy(currentPageId = pageId) }
        viewModelScope.launch { repository.chooseById(pageId, "Brand Account") }
    }
}

/**
 * The profiles of the signed-in Google account, the one in use ticked. Picking one switches every
 * YouTube Music request to it and syncs its library. Below: a Brand Account by id, for when the
 * list can't be read.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeProfileSheet(
    state: YouTubeProfileViewModel.UiState,
    onLoad: () -> Unit,
    onChoose: (YouTubeProfile) -> Unit,
    onChooseById: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(Unit) { if (state.profiles == null) onLoad() }
    var enteringId by rememberSaveable { mutableStateOf(false) }
    var idText by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = StashTheme.extendedColors.elevatedSurface,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                "Which profile has your music?",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Text(
                "Your Google account's YouTube profiles. Stash syncs the one you pick.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 8.dp),
            )
            val profiles = state.profiles
            when {
                state.loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
                profiles == null -> Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Couldn't load your profiles",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onLoad) { Text("Retry") }
                }
                else -> profiles.forEach { p ->
                    ProfileRow(p, chosen = p.pageId == state.currentPageId) {
                        onChoose(p)
                        onDismiss()
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (!enteringId) {
                TextButton(onClick = { enteringId = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("Profile not listed? Enter its ID")
                }
            } else {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text(
                        "On myaccount.google.com/brandaccounts, open the profile and copy the number from the address.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    val valid = YouTubeProfileRepository.BRAND_ID.matches(idText.trim())
                    OutlinedTextField(
                        value = idText,
                        onValueChange = { idText = it.filter(Char::isDigit) },
                        label = { Text("Brand Account ID") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(
                            onClick = {
                                onChooseById(idText.trim())
                                onDismiss()
                            },
                            enabled = valid,
                        ) { Text("Use this profile") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileRow(profile: YouTubeProfile, chosen: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(StashTheme.extendedColors.glassBackground),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Person, contentDescription = null, tint = StashTheme.extendedColors.textTertiary)
            profile.photoUrl?.let {
                AsyncImage(it, null, Modifier.size(40.dp).clip(CircleShape), contentScale = ContentScale.Crop)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(profile.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(profile.handle, if (profile.pageId != null) "Brand Account" else "Your account").joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (chosen) Icon(Icons.Rounded.Check, contentDescription = "In use", tint = MaterialTheme.colorScheme.primary)
    }
}
