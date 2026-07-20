package com.j2team.fileserver.feature.transfers

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
        if (visible.isEmpty()) {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.no_transfers), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visible, key = { it.id }) { task ->
                    TransferRow(task, canRetry = coordinator?.canRetry(task) == true, onRetry = { coordinator?.retry(task) }, onDismiss = { store.remove(task.id) })
                }
            }
        }
    }
}

@Composable
private fun TransferRow(task: TransferTask, canRetry: Boolean, onRetry: () -> Unit, onDismiss: () -> Unit) {
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
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
            }
        }
    }
}
