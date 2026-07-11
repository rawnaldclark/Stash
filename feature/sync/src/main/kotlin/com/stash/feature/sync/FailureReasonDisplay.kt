package com.stash.feature.sync

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.stash.core.model.DownloadFailureType
import com.stash.core.ui.R

data class FailureReasonDisplay(
    val icon: ImageVector,
    val tint: Color,
    val groupTitle: String,
    val shortLabel: String,
)

/**
 * Display ordering: high-leverage groups (one fix repairs many rows) first.
 * Groups with zero count are filtered out of the UI.
 */
val FailureReasonDisplayOrder: List<DownloadFailureType> = listOf(
    DownloadFailureType.AUTH_EXPIRED,
    DownloadFailureType.STORAGE_ERROR,
    DownloadFailureType.NETWORK,
    DownloadFailureType.PROVIDER_UNAVAILABLE,
    DownloadFailureType.FFMPEG_ERROR,
    DownloadFailureType.UNKNOWN,
)

@Composable
fun DownloadFailureType.display(): FailureReasonDisplay = when (this) {
    DownloadFailureType.AUTH_EXPIRED -> FailureReasonDisplay(
        icon = Icons.Default.Key,
        tint = Color(0xFFFFAA00),
        groupTitle = stringResource(R.string.error_signin_expired_group),
        shortLabel = stringResource(R.string.error_signin_expired_short),
    )
    DownloadFailureType.STORAGE_ERROR -> FailureReasonDisplay(
        icon = Icons.Default.Folder,
        tint = Color(0xFF888888),
        groupTitle = stringResource(R.string.error_storage_unreachable_group),
        shortLabel = stringResource(R.string.error_storage_unreachable_short),
    )
    DownloadFailureType.NETWORK -> FailureReasonDisplay(
        icon = Icons.Default.WifiOff,
        tint = Color(0xFF508CFF),
        groupTitle = stringResource(R.string.error_network_errors_group),
        shortLabel = stringResource(R.string.error_network_errors_short),
    )
    DownloadFailureType.PROVIDER_UNAVAILABLE -> FailureReasonDisplay(
        icon = Icons.Default.CloudOff,
        tint = Color(0xFFAA66CC),
        groupTitle = stringResource(R.string.error_source_unavailable_group),
        shortLabel = stringResource(R.string.error_source_unavailable_short),
    )
    DownloadFailureType.FFMPEG_ERROR -> FailureReasonDisplay(
        icon = Icons.Default.Settings,
        tint = Color(0xFFFF5A5A),
        groupTitle = stringResource(R.string.error_encoding_errors_group),
        shortLabel = stringResource(R.string.error_encoding_errors_short),
    )
    DownloadFailureType.UNKNOWN -> FailureReasonDisplay(
        icon = Icons.Default.Help,
        tint = Color(0xFFAAAAAA),
        groupTitle = stringResource(R.string.error_other_errors_group),
        shortLabel = stringResource(R.string.error_unknown_short),
    )
    DownloadFailureType.NONE,
    DownloadFailureType.NO_MATCH -> error("Not surfaced in FailedDownloadsScreen")
}
