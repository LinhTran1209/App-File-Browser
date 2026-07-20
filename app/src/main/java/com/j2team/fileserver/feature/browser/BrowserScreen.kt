package com.j2team.fileserver.feature.browser

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.j2team.fileserver.AppBar
import com.j2team.fileserver.PreviewScreen
import com.j2team.fileserver.R
import com.j2team.fileserver.folderIconResource
import com.j2team.fileserver.formatBytes
import com.j2team.fileserver.core.model.RemoteResource
import com.j2team.fileserver.core.model.ResourcePermissions
import com.j2team.fileserver.core.model.ServerProfile
import com.j2team.fileserver.core.session.SessionRepository
import com.j2team.fileserver.core.ui.AppIcons
import com.j2team.fileserver.feature.settings.AppSettings
import com.j2team.fileserver.feature.transfers.TransferDirection
import com.j2team.fileserver.feature.transfers.TransferState
import com.j2team.fileserver.feature.transfers.TransferCoordinator
import com.j2team.fileserver.feature.transfers.DownloadConflict
import com.j2team.fileserver.feature.transfers.TransferStore
import com.j2team.fileserver.feature.transfers.attentionCount
import com.j2team.fileserver.feature.transfers.attentionBadge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BrowserScreen(
    profile: ServerProfile?,
    settings: AppSettings,
    transferStore: TransferStore,
    transferCoordinator: TransferCoordinator?,
    sessionRepository: SessionRepository,
    onBack: () -> Unit,
    onTransfers: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var path by remember(profile) { mutableStateOf(profile?.basePath ?: "/") }
    var resources by remember { mutableStateOf<List<RemoteResource>>(emptyList()) }
    var directoryPermissions by remember { mutableStateOf(ResourcePermissions()) }
    var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var listError by remember { mutableStateOf<String?>(null) }
    var mutationError by remember { mutableStateOf<String?>(null) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var actionMenuOpen by remember { mutableStateOf(false) }
    var createFolderOpen by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }
    var deleteConfirmationOpen by remember { mutableStateOf(false) }
    var mutating by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<RemoteResource?>(null) }
    var pendingDownload by remember { mutableStateOf<RemoteResource?>(null) }
    val transferTasks by transferStore.tasks.collectAsState()
    val unavailableDirectory = stringResource(R.string.download_directory_unavailable)
    val notPermittedMessage = stringResource(R.string.action_not_permitted)

    fun refresh() {
        val current = profile ?: return
        loading = true
        scope.launch {
            withContext(Dispatchers.IO) { sessionRepository.listWithPermissions(current, path) }
                .onSuccess { listing ->
                    directoryPermissions = listing.directoryPermissions
                    resources = listing.resources.filter { settings.showHiddenFiles || !it.name.startsWith(".") }
                        .sortedWith(compareByDescending<RemoteResource> { it.isDirectory }.thenBy { it.name.lowercase() })
                    selectedPaths = selectedPaths.intersect(resources.mapTo(mutableSetOf()) { it.path })
                    listError = null
                }
                .onFailure { listError = it.message ?: it.toString() }
            loading = false
        }
    }
    LaunchedEffect(profile, path, settings.showHiddenFiles) { refresh() }

    fun uploadFile(uri: Uri) {
        if (!directoryPermissions.canUpload) {
            mutationError = notPermittedMessage
            return
        }
        scope.launch {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                transferCoordinator?.enqueueFile(uri, path) ?: error("No active server")
            }.onFailure { mutationError = it.message ?: it.toString() }
        }
    }

    val uploadFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && directoryPermissions.canUpload) uploadFile(uri)
        else if (uri != null) mutationError = notPermittedMessage
    }
    val uploadFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null && directoryPermissions.canUpload && directoryPermissions.canCreate) scope.launch {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                transferCoordinator?.enqueueFolder(uri, path) ?: error("No active server")
            }.onSuccess { refresh() }.onFailure { mutationError = it.message ?: it.toString() }
        } else if (uri != null) mutationError = notPermittedMessage
    }

    val selected = resources.filter { it.path in selectedPaths }
    val actions = SelectionPolicy.actions(selected)

    fun download(item: RemoteResource) {
        val treeUri = settings.downloadTreeUri?.let(Uri::parse)
        if (treeUri == null || transferCoordinator == null) { downloadError = unavailableDirectory; return }
        scope.launch {
            runCatching {
                if (transferCoordinator.hasDownloadConflict(treeUri, item.name)) pendingDownload = item
                else transferCoordinator.enqueueDownload(item.path, item.name, item.size, treeUri, DownloadConflict.Replace)
            }.onFailure { downloadError = it.message ?: unavailableDirectory }
        }
    }

    fun resolveDownload(conflict: DownloadConflict) {
        val item = pendingDownload ?: return
        val treeUri = settings.downloadTreeUri?.let(Uri::parse) ?: return
        pendingDownload = null
        scope.launch {
            runCatching { transferCoordinator?.enqueueDownload(item.path, item.name, item.size, treeUri, conflict) }
                .onFailure { downloadError = it.message ?: unavailableDirectory }
        }
    }

    if (preview != null && profile != null) {
        PreviewScreen(profile, preview!!, transferStore, sessionRepository, onBack = { preview = null })
        return
    }

    if (createFolderOpen) {
        AlertDialog(
            onDismissRequest = { if (!mutating) createFolderOpen = false },
            title = { Text(stringResource(R.string.new_folder)) },
            text = { OutlinedTextField(folderName, { folderName = it }, label = { Text(stringResource(R.string.folder_name)) }) },
            confirmButton = {
                TextButton(
                    enabled = !mutating && folderName.isNotBlank() && directoryPermissions.canCreate,
                    onClick = {
                        val current = profile ?: return@TextButton
                        mutating = true
                        scope.launch {
                            withContext(Dispatchers.IO) { sessionRepository.createDirectory(current, BrowserPath.child(path, folderName.trim())) }
                                .onSuccess { folderName = ""; createFolderOpen = false; refresh() }
                                .onFailure { mutationError = it.message ?: it.toString() }
                            mutating = false
                        }
                    },
                ) { Text(stringResource(R.string.create)) }
            },
            dismissButton = { TextButton(onClick = { createFolderOpen = false }, enabled = !mutating) { Text(stringResource(R.string.cancel)) } },
        )
    }

    pendingDownload?.let { item ->
        AlertDialog(
            onDismissRequest = { resolveDownload(DownloadConflict.Cancel) },
            title = { Text("File already exists") },
            text = { Text("Choose how to save ${item.name}.") },
            confirmButton = { TextButton(onClick = { resolveDownload(DownloadConflict.Replace) }) { Text("Replace") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { resolveDownload(DownloadConflict.KeepBoth) }) { Text("Keep both") }
                    TextButton(onClick = { resolveDownload(DownloadConflict.Cancel) }) { Text(stringResource(R.string.cancel)) }
                }
            },
        )
    }

    if (deleteConfirmationOpen) {
        AlertDialog(
            onDismissRequest = { if (!mutating) deleteConfirmationOpen = false },
            title = { Text(stringResource(R.string.confirm_delete_title)) },
            text = { Text(stringResource(R.string.confirm_delete_message, selected.size)) },
            confirmButton = {
                TextButton(
                    enabled = !mutating && actions.canDelete,
                    onClick = {
                        val current = profile ?: return@TextButton
                        mutating = true
                        scope.launch {
                            withContext(Dispatchers.IO) { sessionRepository.delete(current, selected.map { it.path }) }
                                .onSuccess { selectedPaths = emptySet(); deleteConfirmationOpen = false; refresh() }
                                .onFailure {
                                    mutationError = it.message ?: it.toString()
                                    deleteConfirmationOpen = false
                                    refresh()
                                }
                            mutating = false
                        }
                    },
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { deleteConfirmationOpen = false }, enabled = !mutating) { Text(stringResource(R.string.cancel)) } },
        )
    }

    Column(Modifier.fillMaxSize()) {
        if (selected.isEmpty()) {
            AppBar(profile?.displayName ?: stringResource(R.string.app_name), onBack, action = {
                IconButton(onClick = onTransfers, modifier = Modifier.size(48.dp)) {
                    BadgedBox(badge = { if (transferTasks.attentionCount() > 0) Badge { Text(transferTasks.attentionBadge()) } }) {
                        Icon(painterResource(AppIcons.Transfers), stringResource(R.string.transfers), modifier = Modifier.size(28.dp))
                    }
                }
            })
        } else {
            AppBar(stringResource(R.string.selected_count, selected.size), onBack = { selectedPaths = emptySet() }, action = {
                if (actions.canDownload) IconButton(onClick = { selected.forEach(::download) }, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(AppIcons.Download), stringResource(R.string.download))
                }
                if (actions.canDelete) IconButton(onClick = { deleteConfirmationOpen = true }, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(AppIcons.Delete), stringResource(R.string.delete))
                }
            })
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (BrowserPath.normalize(path) != "/") TextButton(onClick = { path = BrowserPath.parent(path) }) { Text("‹") }
            Text(path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val actionLabel = stringResource(R.string.browser_actions)
        val actionLongPressLabel = stringResource(R.string.browser_actions_long_press)
        Box(
            Modifier.fillMaxWidth().height(48.dp)
                .semantics { contentDescription = actionLabel }
                .combinedClickable(
                    onClickLabel = actionLabel,
                    onLongClickLabel = actionLongPressLabel,
                    onClick = { actionMenuOpen = true },
                    onLongClick = { actionMenuOpen = true },
                ),
        ) {
            DropdownMenu(expanded = actionMenuOpen, onDismissRequest = { actionMenuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.new_folder)) },
                    leadingIcon = { Icon(painterResource(AppIcons.NewFolder), null) },
                    enabled = directoryPermissions.canCreate,
                    onClick = { mutationError = null; actionMenuOpen = false; createFolderOpen = true },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.upload_files)) },
                    leadingIcon = { Icon(painterResource(AppIcons.Upload), null) },
                    enabled = directoryPermissions.canUpload,
                    onClick = { mutationError = null; actionMenuOpen = false; uploadFilePicker.launch(arrayOf("*/*")) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.upload_folder)) },
                    leadingIcon = { Icon(painterResource(AppIcons.Upload), null) },
                    enabled = directoryPermissions.canUpload && directoryPermissions.canCreate,
                    onClick = { mutationError = null; actionMenuOpen = false; uploadFolderPicker.launch(null) },
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.list), fontWeight = FontWeight.Medium)
            Text(stringResource(R.string.items_count, resources.size), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        listError?.let { Text(stringResource(R.string.connection_failed, it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        mutationError?.let { message ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                TextButton(onClick = { mutationError = null }) { Text(stringResource(R.string.dismiss)) }
            }
        }
        downloadError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(resources, key = { it.path }) { item ->
                ResourceRow(
                    item = item,
                    settings = settings,
                    selected = item.path in selectedPaths,
                    onClick = {
                        if (selectedPaths.isNotEmpty()) selectedPaths = selectedPaths.toggle(item.path)
                        else if (item.isDirectory) path = item.path else preview = item
                    },
                    onLongClick = { selectedPaths = selectedPaths.toggle(item.path) },
                    onDownload = { download(item) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResourceRow(
    item: RemoteResource,
    settings: AppSettings,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDownload: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().height(72.dp).combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(folderIconResource(settings.folderIconSet)), null, Modifier.size(40.dp), tint = Color.Unspecified)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (item.isDirectory) stringResource(R.string.folder) else formatBytes(item.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (item.isDirectory) Text("›", style = MaterialTheme.typography.titleLarge)
            else if (item.permissions.canDownload) IconButton(onClick = onDownload, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(AppIcons.Download), stringResource(R.string.download))
            }
        }
    }
}

private fun Set<String>.toggle(path: String): Set<String> = if (path in this) this - path else this + path

private class UploadNotPermitted : IllegalStateException()

private fun displayName(resolver: ContentResolver, uri: Uri): String? = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
    if (cursor.moveToFirst()) cursor.getString(0) else null
}

private suspend fun uploadTree(
    context: Context,
    treeUri: Uri,
    profile: ServerProfile,
    parentPath: String,
    sessionRepository: SessionRepository,
    transferStore: TransferStore,
): Result<Unit> = runCatching {
    val resolver = context.contentResolver
    val rootId = DocumentsContract.getTreeDocumentId(treeUri)
    val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
    val rootName = displayName(resolver, rootUri) ?: "folder"
    val remoteRoot = BrowserPath.child(parentPath, rootName)
    val permissions = sessionRepository.currentPermissions(profile).getOrThrow()
    if (!permissions.canUpload || !permissions.canCreate) throw UploadNotPermitted()
    sessionRepository.createDirectory(profile, remoteRoot).getOrThrow()
    uploadChildren(resolver, treeUri, rootId, profile, remoteRoot, sessionRepository, transferStore, context.cacheDir)
}

private suspend fun uploadChildren(
    resolver: ContentResolver,
    treeUri: Uri,
    documentId: String,
    profile: ServerProfile,
    remoteParent: String,
    sessionRepository: SessionRepository,
    transferStore: TransferStore,
    cacheDir: File,
) {
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
    resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
        while (cursor.moveToNext()) {
            val childId = cursor.getString(0)
            val name = cursor.getString(1)
            val mimeType = cursor.getString(2)
            if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                val remoteDirectory = BrowserPath.child(remoteParent, name)
                val permissions = sessionRepository.currentPermissions(profile).getOrThrow()
                if (!permissions.canUpload || !permissions.canCreate) throw UploadNotPermitted()
                sessionRepository.createDirectory(profile, remoteDirectory).getOrThrow()
                uploadChildren(resolver, treeUri, childId, profile, remoteDirectory, sessionRepository, transferStore, cacheDir)
            } else {
                val local = File.createTempFile("upload-", ".part", cacheDir)
                try {
                    val source = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                    resolver.openInputStream(source)?.use { input -> local.outputStream().use(input::copyTo) } ?: error("Unable to read $name")
                    val permissions = sessionRepository.currentPermissions(profile).getOrThrow()
                    if (!permissions.canUpload || !permissions.canCreate) throw UploadNotPermitted()
                    var task = transferStore.enqueue(name, remoteParent, TransferDirection.Upload, local.length())
                    task = transferStore.save(task.copy(state = TransferState.Running))
                    sessionRepository.uploadOnce(profile, remoteParent, local) { sent, _ -> transferStore.update(task.id, sent, TransferState.Running) }.getOrThrow()
                    transferStore.update(task.id, task.totalBytes, TransferState.Completed)
                } finally {
                    local.delete()
                }
            }
        }
    } ?: error("Unable to inspect selected folder")
}
