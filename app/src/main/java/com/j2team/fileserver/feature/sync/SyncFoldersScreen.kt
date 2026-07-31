package com.j2team.fileserver.feature.sync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.j2team.fileserver.R
import com.j2team.fileserver.core.model.RemoteResource
import com.j2team.fileserver.core.model.ServerProfile
import com.j2team.fileserver.core.network.FileBrowserClient
import com.j2team.fileserver.core.session.SessionRepository
import com.j2team.fileserver.core.ui.AppIcons
import com.j2team.fileserver.core.ui.FolderNavigationRow
import com.j2team.fileserver.feature.settings.FolderIconSet
import com.j2team.fileserver.folderIconResource
import com.j2team.fileserver.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SyncFoldersScreen(
    profile: ServerProfile,
    repository: SessionRepository,
    onOpenRemoteFolder: (String) -> Unit,
    folderIconSet: FolderIconSet,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SyncFolderStore(context.applicationContext) }
    val tokenStore = remember { EncryptedSyncTokenStore(context.applicationContext) }
    val identityStore = remember { ServerIdentityStore(context.applicationContext) }
    val folderIcon = folderIconResource(folderIconSet)
    var folders by remember(profile.id) { mutableStateOf(store.forProfile(profile.id)) }
    var addOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<SyncFolder?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun reload() { folders = store.forProfile(profile.id) }
    LaunchedEffect(profile.id) {
        identityStore.get(profile.id)?.let { currentIdentity ->
            store.migrateProfile(profile.id, currentIdentity)
            store.all().filter { it.identityOrNull() == null }.forEach { legacy ->
                val rawToken = tokenStore.get(legacy.id) ?: return@forEach
                withContext(Dispatchers.IO) { SyncRemoteRepository(FileBrowserClient(), rawToken).identity(profile) }
                    .onSuccess { identity ->
                        if (identity == currentIdentity) {
                            identityStore.put(legacy.profileId, identity)
                            store.save(legacy.copy(serverId = identity.serverId, userId = identity.userId))
                        }
                    }
            }
        }
        store.forProfile(profile.id).filter { tokenStore.get(it.id) == null }.forEach { folder ->
            repository.createSyncToken(profile, folder.remotePath).onSuccess { token -> tokenStore.put(folder.id, token) }
        }
        FolderSyncService.refresh(context)
        while (true) { delay(2_000); reload() }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(onClick = { addOpen = true }) { Text(stringResource(R.string.add_sync_folder)) }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (folders.isEmpty()) Text(stringResource(R.string.no_sync_folders), color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(folders, key = { it.id }) { folder ->
                SyncFolderRow(
                    folder = folder,
                    folderIcon = folderIcon,
                    onOpen = { onOpenRemoteFolder(folder.remotePath) },
                    onCopy = {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("Cloud path", folder.remotePath))
                    },
                    onToggle = { enabled ->
                        store.save(folder.copy(enabled = enabled, state = if (enabled) SyncState.Idle else SyncState.Disabled, error = null))
                        if (enabled) SyncWorker.runNow(context, folder.id)
                        FolderSyncService.refresh(context)
                        reload()
                    },
                    onSync = { SyncWorker.runNow(context, folder.id) },
                    onDelete = { pendingDelete = folder },
                )
            }
        }
    }

    if (addOpen) AddSyncFolderDialog(
        profile = profile,
        repository = repository,
        folderIcon = folderIcon,
        onDismiss = { addOpen = false },
        onAdded = { localUri, localPath, name, remotePath, token ->
            val created = store.create(profile.id, name, localUri.toString(), localPath, remotePath)
            tokenStore.put(created.id, token)
            SyncWorker.runNow(context, created.id)
            FolderSyncService.refresh(context)
            addOpen = false
            reload()
        },
        onError = { error = it },
    )

    pendingDelete?.let { folder ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_sync_folder_title)) },
            text = { Text(stringResource(R.string.delete_sync_folder_message, folder.name)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val token = tokenStore.get(folder.id)
                        if (token != null) withContext(Dispatchers.IO) { SyncRemoteRepository(FileBrowserClient(), token).revoke(profile) }
                        tokenStore.delete(folder.id)
                        store.delete(folder.id)
                        pendingDelete = null
                        reload()
                        FolderSyncService.refresh(context)
                    }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SyncFolderRow(
    folder: SyncFolder,
    folderIcon: Int,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onSync: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            SyncFolderIcon(folderIcon, folder, Modifier.size(46.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(folder.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).clickable(onClick = onCopy)) {
                    Text(folder.remotePath, style = MaterialTheme.typography.bodySmall, maxLines = 1, softWrap = false)
                }
                Text(formatBytes(folder.totalBytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                folder.error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 2) }
            }
            if (folder.state == SyncState.Syncing) CircularProgressIndicator(Modifier.size(24.dp))
            else IconButton(onClick = onSync, enabled = folder.enabled) { Icon(painterResource(AppIcons.Sync), stringResource(R.string.sync_now)) }
            Switch(checked = folder.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onDelete) { Icon(painterResource(AppIcons.Delete), stringResource(R.string.delete)) }
        }
    }
}

@Composable
private fun AddSyncFolderDialog(
    profile: ServerProfile,
    repository: SessionRepository,
    folderIcon: Int,
    onDismiss: () -> Unit,
    onAdded: (Uri, String, String, String, String) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var localUri by remember { mutableStateOf<Uri?>(null) }
    var localName by remember { mutableStateOf("") }
    var localPath by remember { mutableStateOf("") }
    var cloudParent by remember { mutableStateOf("/") }
    var cloudPicker by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val localPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            localUri = uri
            val treeId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrDefault(uri.lastPathSegment.orEmpty())
            localName = treeId.substringAfterLast(':').substringAfterLast('/').ifBlank { "Sync" }
            localPath = "/" + treeId.substringAfter(':', treeId).trim('/')
        }
    }
    val remotePath = (cloudParent.trimEnd('/') + "/" + localName).replace(Regex("/+"), "/")

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.add_sync_folder)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { localPicker.launch(null) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.choose_local_folder)) }
                OutlinedTextField(localPath, {}, readOnly = true, label = { Text(stringResource(R.string.local_folder)) }, modifier = Modifier.fillMaxWidth())
                Button(onClick = { cloudPicker = true }, enabled = localUri != null, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.choose_cloud_parent)) }
                OutlinedTextField(remotePath, {}, readOnly = true, label = { Text(stringResource(R.string.cloud_folder)) }, modifier = Modifier.fillMaxWidth())
                if (busy) CircularProgressIndicator()
            }
        },
        confirmButton = {
            TextButton(enabled = localUri != null && !busy, onClick = {
                val uri = localUri ?: return@TextButton
                busy = true
                scope.launch {
                    val created = withContext(Dispatchers.IO) { repository.createDirectory(profile, remotePath) }
                    if (created.isFailure) {
                        onError(created.exceptionOrNull()?.message ?: "Unable to create cloud folder")
                        busy = false
                        return@launch
                    }
                    val token = withContext(Dispatchers.IO) { repository.createSyncToken(profile, remotePath) }
                    token.onSuccess { onAdded(uri, localPath, localName, remotePath, it) }
                        .onFailure { onError(it.message ?: it.toString()); busy = false }
                }
            }) { Text(stringResource(R.string.add)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
    if (cloudPicker) CloudFolderPicker(profile, repository, cloudParent, folderIcon, { cloudParent = it; cloudPicker = false }, { cloudPicker = false })
}

@Composable
private fun CloudFolderPicker(
    profile: ServerProfile,
    repository: SessionRepository,
    initial: String,
    folderIcon: Int,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf(initial) }
    var folders by remember { mutableStateOf<List<RemoteResource>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    fun load(next: String) {
        loading = true
        scope.launch {
            withContext(Dispatchers.IO) { repository.list(profile, next) }
                .onSuccess { folders = it.filter(RemoteResource::isDirectory); path = next; error = null }
                .onFailure { error = it.message ?: it.toString() }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(path, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.fillMaxWidth().height(380.dp)) {
                if (path != "/") TextButton(onClick = { load(path.substringBeforeLast('/').ifEmpty { "/" }) }) { Text("‹  ${stringResource(R.string.parent_folder)}") }
                if (loading) CircularProgressIndicator()
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                LazyColumn(Modifier.weight(1f)) {
                    items(folders, key = { it.path }) { folder ->
                        FolderNavigationRow(folder.name, folderIcon, folder.name, { load(folder.path) })
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = !loading, onClick = { onSelect(path) }) { Text(stringResource(R.string.select_this_folder)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
