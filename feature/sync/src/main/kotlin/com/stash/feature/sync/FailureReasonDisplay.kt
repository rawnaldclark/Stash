package com.stash.feature.sync

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.SdCardAlert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.stash.core.model.DownloadFailureType

data class FailureReasonDisplay(
    val icon: ImageVector,
    val tint: Color,
    @StringRes val groupTitle: Int,
    @StringRes val shortLabel: Int,
)

/**
 * Display ordering: high-leverage groups (one fix repairs many rows) first.
 * Groups with zero count are filtered out of the UI.
 */
val FailureReasonDisplayOrder: List<DownloadFailureType> = listOf(
    DownloadFailureType.AUTH_EXPIRED,
    DownloadFailureType.STORAGE_FULL,
    DownloadFailureType.STORAGE_ERROR,
    DownloadFailureType.NETWORK,
    DownloadFailureType.RATE_LIMITED,
    DownloadFailureType.PROVIDER_UNAVAILABLE,
    DownloadFailureType.FFMPEG_ERROR,
    DownloadFailureType.UNKNOWN,
)

fun DownloadFailureType.display(): FailureReasonDisplay = when (this) {
    DownloadFailureType.AUTH_EXPIRED -> FailureReasonDisplay(
        icon = Icons.Default.Key,
        tint = Color(0xFFFFAA00),
        groupTitle = R.string.failure_auth_group,
        shortLabel = R.string.failure_auth_short,
    )
    DownloadFailureType.STORAGE_FULL -> FailureReasonDisplay(
        icon = Icons.Default.SdCardAlert,
        tint = Color(0xFFFF8A50),
        groupTitle = R.string.failure_storage_full_group,
        shortLabel = R.string.failure_storage_full_short,
    )
    DownloadFailureType.STORAGE_ERROR -> FailureReasonDisplay(
        icon = Icons.Default.Folder,
        tint = Color(0xFF888888),
        groupTitle = R.string.failure_storage_group,
        shortLabel = R.string.failure_storage_short,
    )
    DownloadFailureType.NETWORK -> FailureReasonDisplay(
        icon = Icons.Default.WifiOff,
        tint = Color(0xFF508CFF),
        groupTitle = R.string.failure_network_group,
        shortLabel = R.string.failure_network_short,
    )
    DownloadFailureType.RATE_LIMITED -> FailureReasonDisplay(
        icon = Icons.Default.HourglassEmpty,
        tint = Color(0xFF4DB6AC),
        groupTitle = R.string.failure_rate_limited_group,
        shortLabel = R.string.failure_rate_limited_short,
    )
    DownloadFailureType.PROVIDER_UNAVAILABLE -> FailureReasonDisplay(
        icon = Icons.Default.CloudOff,
        tint = Color(0xFFAA66CC),
        groupTitle = R.string.failure_provider_group,
        shortLabel = R.string.failure_provider_short,
    )
    DownloadFailureType.FFMPEG_ERROR -> FailureReasonDisplay(
        icon = Icons.Default.Settings,
        tint = Color(0xFFFF5A5A),
        groupTitle = R.string.failure_ffmpeg_group,
        shortLabel = R.string.failure_ffmpeg_short,
    )
    DownloadFailureType.UNKNOWN -> FailureReasonDisplay(
        icon = Icons.AutoMirrored.Filled.Help,
        tint = Color(0xFFAAAAAA),
        groupTitle = R.string.failure_unknown_group,
        shortLabel = R.string.failure_unknown_short,
    )
    DownloadFailureType.NONE,
    DownloadFailureType.NO_MATCH -> error("Not surfaced in FailedDownloadsScreen")
}
