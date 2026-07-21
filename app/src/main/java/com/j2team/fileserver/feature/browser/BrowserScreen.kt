package com.j2team.fileserver.feature.browser

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.j2team.fileserver.AppBar
import com.j2team.fileserver.feature.preview.PreviewScreen
import com.j2team.fileserver.feature.preview.PreviewKind
import com.j2team.fileserver.feature.preview.PreviewRouter
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
import java.util.UUID

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
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
    var moveDialogOpen by remember { mutableStateOf(false) }
    var moveDestination by remember(path) { mutableStateOf(path) }
    var moveDirectories by remember { mutableStateOf<List<RemoteResource>>(emptyList()) }
    var moveLoading by remember { mutableStateOf(false) }
    val transferTasks by transferStore.tasks.collectAsState()
    val unavailableDirectory = stringResource(R.string.download_directory_unavailable)
    val notPermittedMessage = stringResource(R.string.action_not_permitted)
    val basePath = BrowserPath.normalize(profile?.basePath ?: "/")

    BackHandler(enabled = preview != null) { preview = null }
    BackHandler(enabled = preview == null) {
        when {
            selectedPaths.isNotEmpty() -> selectedPaths = emptySet()
            BrowserPath.normalize(path) != basePath -> path = BrowserPath.parent(path)
            else -> onBack()
        }
    }

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
    LaunchedEffect(moveDialogOpen, moveDestination, profile) {
        val current = profile ?: return@LaunchedEffect
        if (!moveDialogOpen) return@LaunchedEffect
        val selectedForMove = resources.filter { it.path in selectedPaths }
        moveLoading = true
        withContext(Dispatchers.IO) { sessionRepository.list(current, moveDestination) }
            .onSuccess { listed ->
                moveDirectories = listed.filter { candidate ->
                    candidate.isDirectory && selectedForMove.none { chosen ->
                        candidate.path == chosen.path || candidate.path.startsWith(chosen.path.trimEnd('/') + "/")
                    }
                }.sortedBy { it.name.lowercase() }
            }
            .onFailure { mutationError = it.message ?: it.toString(); moveDirectories = emptyList() }
        moveLoading = false
    }

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
        val images = resources.filter { !it.isDirectory && PreviewRouter.kind(it.name, it.mimeType) == PreviewKind.Image }
        PreviewScreen(
            profile = profile,
            item = preview!!,
            transferStore = transferStore,
            sessionRepository = sessionRepository,
            imageSiblings = images,
            onNavigateImage = { preview = it },
            onBack = { preview = null },
        )
        return
    }

    if (createFolderOpen) {
        AlertDialog(
            onDismissRequest = { if (!mutating) createFolderOpen = false },
            modifier = Modifier.fillMaxWidth(0.82f),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            title = { Text(stringResource(R.string.new_folder)) },
            text = { OutlinedTextField(folderName, { folderName = it }, label = { Text(stringResource(R.string.folder_name)) }, singleLine = true) },
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
            modifier = Modifier.fillMaxWidth(0.82f),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
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

    if (moveDialogOpen) {
        AlertDialog(
            onDismissRequest = { if (!mutating) moveDialogOpen = false },
            modifier = Modifier.fillMaxWidth(0.82f),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            title = { Text(stringResource(R.string.move_selected)) },
            text = {
                Column(Modifier.fillMaxWidth().height(300.dp)) {
                    Text(moveDestination, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (BrowserPath.normalize(moveDestination) != "/") {
                        TextButton(onClick = { moveDestination = BrowserPath.parent(moveDestination) }) {
                            Text("‹  ${stringResource(R.string.destination_folder)}")
                        }
                    }
                    if (moveLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    LazyColumn(Modifier.weight(1f)) {
                        items(moveDirectories, key = { it.path }) { directory ->
                            TextButton(onClick = { moveDestination = directory.path }, modifier = Modifier.fillMaxWidth()) {
                                Icon(painterResource(folderIconResource(settings.folderIconSet)), null, Modifier.size(28.dp), tint = Color.Unspecified)
                                Spacer(Modifier.width(8.dp))
                                Text(directory.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("›")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !mutating && moveDestination.isNotBlank(),
                    onClick = {
                        val current = profile ?: return@TextButton
                        val moving = selected.toList()
                        mutating = true
                        scope.launch {
                            withContext(Dispatchers.IO) { sessionRepository.move(current, moving, moveDestination) }
                                .onSuccess {
                                    selectedPaths = emptySet()
                                    moveDialogOpen = false
                                    refresh()
                                }
                                .onFailure { mutationError = it.message ?: it.toString() }
                            mutating = false
                        }
                    },
                ) { Text(stringResource(R.string.move_here)) }
            },
            dismissButton = { TextButton(onClick = { moveDialogOpen = false }, enabled = !mutating) { Text(stringResource(R.string.cancel)) } },
        )
    }

    Column(Modifier.fillMaxSize()) {
        if (selected.isEmpty()) {
            AppBar(profile?.displayName ?: stringResource(R.string.app_name), onBack = {
                if (BrowserPath.normalize(path) != basePath) path = BrowserPath.parent(path) else onBack()
            }, action = {
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
                if (actions.canMove) IconButton(onClick = { moveDestination = "/"; moveDialogOpen = true }, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(AppIcons.Move), stringResource(R.string.move))
                }
                if (actions.canDelete) IconButton(onClick = { deleteConfirmationOpen = true }, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(AppIcons.Delete), stringResource(R.string.delete))
                }
            })
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (BrowserPath.normalize(path) != "/") {
                TextButton(
                    onClick = { path = BrowserPath.parent(path) },
                    modifier = Modifier.size(48.dp),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text("‹", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Medium)
                }
            } else {
                Spacer(Modifier.size(48.dp))
            }
            Text(path, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            BrowserActionButton(stringResource(R.string.new_folder), AppIcons.NewFolder, directoryPermissions.canCreate, Modifier.weight(1f)) {
                mutationError = null; createFolderOpen = true
            }
            BrowserActionButton(stringResource(R.string.upload_files), AppIcons.Upload, directoryPermissions.canUpload, Modifier.weight(1f)) {
                mutationError = null; uploadFilePicker.launch(arrayOf("*/*"))
            }
            BrowserActionButton(stringResource(R.string.upload_folder), AppIcons.Upload, directoryPermissions.canUpload && directoryPermissions.canCreate, Modifier.weight(1f)) {
                mutationError = null; uploadFolderPicker.launch(null)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(if (settings.gridView) R.string.grid else R.string.list), fontWeight = FontWeight.Medium)
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
        val openItem: (RemoteResource) -> Unit = { item ->
            if (selectedPaths.isNotEmpty()) selectedPaths = selectedPaths.toggle(item.path)
            else if (item.isDirectory) path = item.path else preview = item
        }
        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = ::refresh,
            modifier = Modifier.weight(1f),
        ) {
            if (settings.gridView) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    gridItems(resources, key = { it.path }) { item ->
                        ResourceGridCard(item, settings, profile, sessionRepository, item.path in selectedPaths, { openItem(item) }, { selectedPaths = selectedPaths.toggle(item.path) }, { download(item) })
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(resources, key = { it.path }) { item ->
                        ResourceRow(
                            item = item,
                            settings = settings,
                            profile = profile,
                            sessionRepository = sessionRepository,
                            selected = item.path in selectedPaths,
                            onClick = { openItem(item) },
                            onLongClick = { selectedPaths = selectedPaths.toggle(item.path) },
                            onDownload = { download(item) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserActionButton(label: String, icon: Int, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier.height(76.dp), contentPadding = PaddingValues(4.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(painterResource(icon), null, Modifier.size(26.dp))
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 2)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResourceRow(
    item: RemoteResource,
    settings: AppSettings,
    profile: ServerProfile?,
    sessionRepository: SessionRepository,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDownload: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().height(72.dp)
            .semantics { this.selected = selected }
            .testTag("resource-${item.path}")
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            ResourceVisual(item, settings, profile, sessionRepository, Modifier.size(44.dp))
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResourceGridCard(
    item: RemoteResource,
    settings: AppSettings,
    profile: ServerProfile?,
    sessionRepository: SessionRepository,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDownload: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().height(156.dp)
            .semantics { this.selected = selected }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
    ) {
        Box(Modifier.fillMaxSize().padding(10.dp)) {
            ResourceVisual(item, settings, profile, sessionRepository, Modifier.size(76.dp).align(Alignment.TopCenter))
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
                Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (item.isDirectory) stringResource(R.string.folder) else formatBytes(item.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!item.isDirectory && item.permissions.canDownload) {
                IconButton(onClick = onDownload, modifier = Modifier.align(Alignment.BottomEnd).size(36.dp)) {
                    Icon(painterResource(AppIcons.Download), stringResource(R.string.download), Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun ResourceVisual(
    item: RemoteResource,
    settings: AppSettings,
    profile: ServerProfile?,
    sessionRepository: SessionRepository,
    modifier: Modifier,
) {
    if (item.isDirectory) {
        Icon(painterResource(folderIconResource(settings.folderIconSet)), null, modifier, tint = Color.Unspecified)
        return
    }
    val previewKind = PreviewRouter.kind(item.name, item.mimeType)
    val canThumbnail = previewKind == PreviewKind.Image || previewKind == PreviewKind.Video
    val fallbackIcon = if (previewKind == PreviewKind.Video) AppIcons.Video else AppIcons.File
    if (!canThumbnail || profile == null) {
        Icon(painterResource(fallbackIcon), stringResource(R.string.file), modifier, tint = Color.Unspecified)
        return
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val cacheFile = remember(profile.id, item.path) {
        File(context.cacheDir, "thumbnails/${profile.id}-${UUID.nameUUIDFromBytes(item.path.toByteArray())}.img")
    }
    var bitmap by remember(cacheFile) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(cacheFile) {
        bitmap = withContext(Dispatchers.IO) {
            if (!cacheFile.isFile || cacheFile.length() == 0L) {
                cacheFile.parentFile?.mkdirs()
                sessionRepository.thumbnail(profile, item.path, cacheFile).getOrNull()
            }
            BitmapFactory.decodeFile(cacheFile.path)
        }
    }
    val preview = bitmap
    if (preview != null) {
        Image(
            bitmap = preview.asImageBitmap(),
            contentDescription = item.name,
            modifier = modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop,
        )
    } else {
        Icon(painterResource(fallbackIcon), stringResource(R.string.file), modifier, tint = Color.Unspecified)
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
                    sessionRepository.uploadOnce(profile, remoteParent, local, name) { sent, _ -> transferStore.update(task.id, sent, TransferState.Running) }.getOrThrow()
                    transferStore.update(task.id, task.totalBytes, TransferState.Completed)
                } finally {
                    local.delete()
                }
            }
        }
    } ?: error("Unable to inspect selected folder")
}
