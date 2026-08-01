package com.j2team.fileserver.feature.browser

import android.content.Context
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.j2team.fileserver.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Multi-directory selection layered on top of Android's single-tree permission picker. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LocalFolderBatchPicker(
    context: Context,
    grantedTreeUri: Uri,
    @DrawableRes folderIcon: Int,
    onDismiss: () -> Unit,
    onConfirm: (List<DocumentFile>) -> Unit,
) {
    val root = remember(grantedTreeUri) { DocumentFile.fromTreeUri(context, grantedTreeUri) }
    var stack by remember(grantedTreeUri) { mutableStateOf(root?.let(::listOf).orEmpty()) }
    var directories by remember { mutableStateOf<List<DocumentFile>>(emptyList()) }
    var selected by remember(grantedTreeUri) { mutableStateOf<Map<String, DocumentFile>>(emptyMap()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val current = stack.lastOrNull()

    LaunchedEffect(current?.uri) {
        val folder = current ?: return@LaunchedEffect
        loading = true
        runCatching {
            withContext(Dispatchers.IO) {
                folder.listFiles().filter(DocumentFile::isDirectory).sortedBy { it.name.orEmpty().lowercase() }
            }
        }.onSuccess {
            directories = it
            error = null
        }.onFailure {
            directories = emptyList()
            error = it.message ?: it.toString()
        }
        loading = false
    }

    fun toggle(folder: DocumentFile) {
        val key = folder.uri.toString()
        selected = if (key in selected) {
            selected - key
        } else {
            selected + (key to folder)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.upload_folders_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stack.joinToString(" / ") { it.name.orEmpty() }.ifBlank { "/" },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (stack.size > 1) {
                    TextButton(onClick = { stack = stack.dropLast(1) }) {
                        Text("‹  ${stringResource(R.string.parent_folder)}")
                    }
                }
                current?.let { folder ->
                    FolderChoiceRow(
                        name = stringResource(R.string.select_current_folder, folder.name ?: "/"),
                        folderIcon = folderIcon,
                        selected = folder.uri.toString() in selected,
                        onClick = { toggle(folder) },
                        onToggle = { toggle(folder) },
                    )
                }
                when {
                    loading -> Text(stringResource(R.string.loading_folders))
                    error != null -> Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    directories.isEmpty() -> Text(stringResource(R.string.no_subfolders))
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                        items(directories, key = { it.uri.toString() }) { folder ->
                            FolderChoiceRow(
                                name = folder.name ?: stringResource(R.string.folder),
                                folderIcon = folderIcon,
                                selected = folder.uri.toString() in selected,
                                onClick = { stack = stack + folder },
                                onToggle = { toggle(folder) },
                            )
                        }
                    }
                }
                Text(stringResource(R.string.folder_multi_select_hint), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.folders_selected, selected.size), style = MaterialTheme.typography.labelLarge)
            }
        },
        confirmButton = {
            TextButton(enabled = selected.isNotEmpty(), onClick = { onConfirm(selected.values.toList()) }) {
                Text(stringResource(R.string.upload_selected_folders))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderChoiceRow(
    name: String,
    @DrawableRes folderIcon: Int,
    selected: Boolean,
    onClick: () -> Unit,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onToggle)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(folderIcon), null, Modifier.size(30.dp), tint = Color.Unspecified)
        Spacer(Modifier.width(10.dp))
        Text(name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
    }
}
