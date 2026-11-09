package com.stash.feature.sync

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.stash.core.model.DownloadFailureType

data class FailureReasonDisplay(
    val icon: ImageVector,
    val tint: Color,
    @param:StringRes val groupTitleRes: Int,
    @param:StringRes val shortLabelRes: Int,
    val groupTitle: String = "",
    val shortLabel: String = "",
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

fun DownloadFailureType.display(): FailureReasonDisplay = when (this) {
    DownloadFailureType.AUTH_EXPIRED -> FailureReasonDisplay(
        icon = Icons.Default.Key,
        tint = Color(0xFFFFAA00),
        groupTitleRes = R.string.sync_failure_reason_auth_expired_title,
        shortLabelRes = R.string.sync_failure_reason_auth_expired_short,
        groupTitle = "Sign-in expired",
        shortLabel = "Sign-in expired",
    )
    DownloadFailureType.STORAGE_ERROR -> FailureReasonDisplay(
        icon = Icons.Default.Folder,
        tint = Color(0xFF888888),
        groupTitleRes = R.string.sync_failure_reason_storage_error_title,
        shortLabelRes = R.string.sync_failure_reason_storage_error_short,
        groupTitle = "Storage unreachable",
        shortLabel = "Storage error",
    )
    DownloadFailureType.NETWORK -> FailureReasonDisplay(
        icon = Icons.Default.WifiOff,
        tint = Color(0xFF508CFF),
        groupTitleRes = R.string.sync_failure_reason_network_title,
        shortLabelRes = R.string.sync_failure_reason_network_short,
        groupTitle = "Network errors",
        shortLabel = "Network error",
    )
    DownloadFailureType.PROVIDER_UNAVAILABLE -> FailureReasonDisplay(
        icon = Icons.Default.CloudOff,
        tint = Color(0xFFAA66CC),
        groupTitleRes = R.string.sync_failure_reason_provider_unavailable_title,
        shortLabelRes = R.string.sync_failure_reason_provider_unavailable_short,
        groupTitle = "Source unavailable",
        shortLabel = "Source unavailable",
    )
    DownloadFailureType.FFMPEG_ERROR -> FailureReasonDisplay(
        icon = Icons.Default.Settings,
        tint = Color(0xFFFF5A5A),
        groupTitleRes = R.string.sync_failure_reason_ffmpeg_error_title,
        shortLabelRes = R.string.sync_failure_reason_ffmpeg_error_short,
        groupTitle = "Encoding errors",
        shortLabel = "Encoding error",
    )
    DownloadFailureType.UNKNOWN -> FailureReasonDisplay(
        icon = Icons.AutoMirrored.Filled.Help,
        tint = Color(0xFFAAAAAA),
        groupTitleRes = R.string.sync_failure_reason_unknown_title,
        shortLabelRes = R.string.sync_failure_reason_unknown_short,
        groupTitle = "Other errors",
        shortLabel = "Unknown error",
    )
    DownloadFailureType.NONE,
    DownloadFailureType.NO_MATCH -> error("Not surfaced in FailedDownloadsScreen")
}
