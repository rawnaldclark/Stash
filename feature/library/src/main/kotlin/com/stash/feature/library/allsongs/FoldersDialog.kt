package com.stash.feature.library.allsongs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stash.core.data.db.entity.ScanFolderEntity

/** The folders "On this device" scans: add, remove, rescan. */
@Composable
internal fun FoldersDialog(
    folders: List<ScanFolderEntity>,
    scanning: Boolean,
    onAddFolder: () -> Unit,
    onRemoveFolder: (ScanFolderEntity) -> Unit,
    onRescan: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scan folders") },
        text = {
            Column {
                if (folders.isEmpty()) {
                    Text("No folders yet. Songs in the folders you add (and their subfolders) show up here.")
                }
                folders.forEach { folder ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = folder.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { onRemoveFolder(folder) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove ${folder.displayName}")
                        }
                    }
                }
                if (folders.isNotEmpty()) {
                    TextButton(onClick = onRescan, enabled = !scanning) { Text("Rescan now") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAddFolder) {
                Icon(Icons.Filled.CreateNewFolder, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add folder")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}