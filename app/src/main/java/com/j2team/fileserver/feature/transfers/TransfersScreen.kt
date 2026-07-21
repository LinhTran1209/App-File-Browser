package com.j2team.fileserver.feature.transfers

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.j2team.fileserver.AppBar
import com.j2team.fileserver.R
import com.j2team.fileserver.formatBytes

@Composable
fun TransfersScreen(store: TransferStore, coordinator: TransferCoordinator?, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val tasks by store.tasks.collectAsState()
    var tab by remember { mutableStateOf(TransferTab.Downloads) }
    val visible = tasks.forTab(tab)
    Column(Modifier.fillMaxSize()) {
        AppBar(stringResource(R.string.transfers), onBack)
        PrimaryTabRow(selectedTabIndex = tab.ordinal) {
            TransferTab.entries.forEach { choice ->
                Tab(selected = tab == choice, onClick = { tab = choice }, text = { Text(if (choice == TransferTab.Downloads) "Downloads" else "Uploads") })
            }
        }
        if (visible.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = {
                    store.clear(
                        if (tab == TransferTab.Downloads) TransferDirection.Download else TransferDirection.Upload,
                    )
                }) { Text(stringResource(R.string.clear_all)) }
            }
        }
        if (visible.isEmpty()) {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.no_transfers), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visible, key = { it.id }) { task ->
                    TransferRow(
                        task,
                        canRetry = coordinator?.canRetry(task) == true,
                        onRetry = { coordinator?.retry(task) },
                        onDismiss = { store.remove(task.id) },
                        onOpen = { openDownloadedItem(context, task) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TransferRow(task: TransferTask, canRetry: Boolean, onRetry: () -> Unit, onDismiss: () -> Unit, onOpen: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (task.state == TransferState.Failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (task.direction == TransferDirection.Download) "↓" else "↑")
                Spacer(Modifier.width(12.dp))
                Text(task.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(task.state.name.lowercase().replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.labelMedium)
            }
            LinearProgressIndicator(progress = { task.progress }, modifier = Modifier.fillMaxWidth())
            Text("${formatBytes(task.transferredBytes)} / ${formatBytes(task.totalBytes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            task.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (task.state == TransferState.Failed || task.state == TransferState.Cancelled) {
                Row { TextButton(onClick = onRetry, enabled = canRetry) { Text("Retry") }; TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) } }
            } else if (task.state == TransferState.Completed) {
                Row {
                    if (task.direction == TransferDirection.Download && task.sourceUri != null) {
                        TextButton(onClick = onOpen) { Text(stringResource(R.string.open_item)) }
                    }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
                }
            }
        }
    }
}

private fun openDownloadedItem(context: Context, task: TransferTask) {
    val tree = task.sourceUri?.let(Uri::parse) ?: return
    val root = DocumentFile.fromTreeUri(context, tree) ?: return
    val target = root.findFile(task.name) ?: root
    val extension = task.name.substringAfterLast('.', "").lowercase()
    val mime = if (target.isDirectory) "resource/folder" else context.contentResolver.getType(target.uri)
        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        ?: "application/octet-stream"
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(target.uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
