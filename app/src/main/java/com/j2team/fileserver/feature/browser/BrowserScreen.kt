package com.j2team.fileserver.feature.browser

import android.content.ContentResolver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import com.j2team.fileserver.feature.preview.fetchSharedVideoThumbnail
import com.j2team.fileserver.R
import com.j2team.fileserver.folderIconResource
import com.j2team.fileserver.formatBytes
import com.j2team.fileserver.core.model.RemoteResource
import com.j2team.fileserver.core.model.DiskUsage
import com.j2team.fileserver.core.model.ResourcePermissions
import com.j2team.fileserver.core.model.ServerProfile
import com.j2team.fileserver.core.session.SessionRepository
import com.j2team.fileserver.core.ui.AppIcons
import com.j2team.fileserver.core.ui.DialogOutlinedTextField
import com.j2team.fileserver.core.ui.FolderNavigationRow
import com.j2team.fileserver.feature.settings.AppSettings
import com.j2team.fileserver.feature.transfers.TransferDirection
import com.j2team.fileserver.feature.transfers.TransferState
import com.j2team.fileserver.feature.transfers.TransferCoordinator
import com.j2team.fileserver.feature.transfers.DownloadConflict
import com.j2team.fileserver.feature.transfers.TransferStore
import com.j2team.fileserver.feature.transfers.attentionCount
import com.j2team.fileserver.feature.transfers.attentionBadge
import com.j2team.fileserver.feature.sync.SyncFolder
import com.j2team.fileserver.feature.sync.SyncFolderIcon
import com.j2team.fileserver.feature.sync.SyncFolderStore
import com.j2team.fileserver.feature.sync.ServerIdentityStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.ln
import kotlin.math.pow
import com.j2team.fileserver.core.cache.AppCacheManager

private data class ArchiveFormat(val algorithm: String, val extension: String)
private val archiveFormats = listOf(
    ArchiveFormat("zip", "zip"),
    ArchiveFormat("tar", "tar"),
    ArchiveFormat("targz", "tar.gz"),
    ArchiveFormat("tarbz2", "tar.bz2"),
    ArchiveFormat("tarxz", "tar.xz"),
    ArchiveFormat("tarlz4", "tar.lz4"),
    ArchiveFormat("tarsz", "tar.sz"),
    ArchiveFormat("tarbr", "tar.br"),
    ArchiveFormat("tarzst", "tar.zst"),
)

private data class PendingArchiveDownload(
    val items: List<RemoteResource>,
    val format: ArchiveFormat,
    val name: String,
)

private enum class DestinationOperation { Copy, Move }

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    profile: ServerProfile?,
    settings: AppSettings,
    transferStore: TransferStore,
    transferCoordinator: TransferCoordinator?,
    sessionRepository: SessionRepository,
    initialPath: String,
    onPathChanged: (String) -> Unit,
    onBack: () -> Unit,
    onTransfers: () -> Unit,
    onServerSettings: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val syncFolderStore = remember { SyncFolderStore(context.applicationContext) }
    var syncFolders by remember(profile?.id) { mutableStateOf(syncFolderStore.forProfile(profile?.id.orEmpty())) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    var path by remember(profile) { mutableStateOf(BrowserPath.normalize(initialPath)) }
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
    var deleteOwners by remember { mutableStateOf<List<String>>(emptyList()) }
    var mutating by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<RemoteResource?>(null) }
    var pendingDownload by remember { mutableStateOf<RemoteResource?>(null) }
    var pendingArchiveDownload by remember { mutableStateOf<PendingArchiveDownload?>(null) }
    var archiveItems by remember { mutableStateOf<List<RemoteResource>>(emptyList()) }
    var archiveDialogOpen by remember { mutableStateOf(false) }
    var pendingFolderUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var folderBatchDialogOpen by remember { mutableStateOf(false) }
    var destinationOperation by remember { mutableStateOf<DestinationOperation?>(null) }
    var destinationPath by remember(path) { mutableStateOf(path) }
    var destinationDirectories by remember { mutableStateOf<List<RemoteResource>>(emptyList()) }
    var destinationLoading by remember { mutableStateOf(false) }
    var sortAscending by remember { mutableStateOf(true) }
    var renameDialogOpen by remember { mutableStateOf(false) }
    var renameValue by remember { mutableStateOf("") }
    var pathDialogOpen by remember { mutableStateOf(false) }
    var pathInput by remember { mutableStateOf("") }
    var pathResolving by remember { mutableStateOf(false) }
    var diskUsage by remember { mutableStateOf<DiskUsage?>(null) }
    var shareDialogOpen by remember { mutableStateOf(false) }
    var shareLinks by remember { mutableStateOf(emptyList<com.j2team.fileserver.core.model.ShareLink>()) }
    var shareLoading by remember { mutableStateOf(false) }
    var shareMutating by remember { mutableStateOf(false) }
    var shareError by remember { mutableStateOf<String?>(null) }
    val pathScrollState = rememberScrollState()
    val transferTasks by transferStore.tasks.collectAsState()
    val unavailableDirectory = stringResource(R.string.download_directory_unavailable)
    val notPermittedMessage = stringResource(R.string.action_not_permitted)
    val pathNotFoundMessage = stringResource(R.string.path_not_found)
    val uploadSizeUnavailable = stringResource(R.string.upload_size_unavailable)
    val uploadQuotaExceededTemplate = stringResource(R.string.upload_quota_exceeded)
    val shareLinkLabel = stringResource(R.string.share_link)
    val basePath = BrowserPath.normalize(profile?.basePath ?: "/")

    LaunchedEffect(profile?.id) {
        while (true) {
            syncFolders = syncFolderStore.forProfile(profile?.id.orEmpty())
            kotlinx.coroutines.delay(2_000)
        }
    }

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
                    resources = listing.resources.filterNot { it.name.startsWith(".") }
                        .sortedByResourceName(sortAscending)
                    selectedPaths = selectedPaths.intersect(resources.mapTo(mutableSetOf()) { it.path })
                    listError = null
                }
                .onFailure { listError = it.message ?: it.toString() }
            loading = false
            launch {
                withContext(Dispatchers.IO) { sessionRepository.diskUsage(current, path) }
                    .onSuccess { diskUsage = it }
                    .onFailure { diskUsage = null }
            }
        }
    }
    LaunchedEffect(profile, path) { refresh() }
    LaunchedEffect(path) { onPathChanged(BrowserPath.normalize(path)) }
    LaunchedEffect(path, pathScrollState.maxValue) {
        pathScrollState.scrollTo(pathScrollState.maxValue)
    }
    LaunchedEffect(destinationOperation, destinationPath, profile) {
        val current = profile ?: return@LaunchedEffect
        if (destinationOperation == null) return@LaunchedEffect
        val selectedForDestination = resources.filter { it.path in selectedPaths }
        destinationLoading = true
        withContext(Dispatchers.IO) { sessionRepository.list(current, destinationPath) }
            .onSuccess { listed ->
                destinationDirectories = listed.filter { candidate ->
                    candidate.isDirectory && selectedForDestination.none { chosen ->
                        candidate.path == chosen.path || candidate.path.startsWith(chosen.path.trimEnd('/') + "/")
                    }
                }.sortedBy { it.name.lowercase() }
            }
            .onFailure { mutationError = it.message ?: it.toString(); destinationDirectories = emptyList() }
        destinationLoading = false
    }

    suspend fun uploadFits(selectedBytes: Long?, label: String): Boolean {
        val current = profile ?: return false
        if (selectedBytes == null || selectedBytes < 0) {
            mutationError = uploadSizeUnavailable
            return false
        }
        val usage = withContext(Dispatchers.IO) { sessionRepository.diskUsage(current, path) }
            .getOrElse {
                mutationError = it.message ?: it.toString()
                return false
            }
        if (!uploadSelectionFits(selectedBytes, usage.total, usage.used)) {
            mutationError = String.format(
                Locale.getDefault(),
                uploadQuotaExceededTemplate,
                label,
                formatBytes((usage.total - usage.used).coerceAtLeast(0)),
            )
            return false
        }
        return true
    }

    fun uploadFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (!directoryPermissions.canUpload) {
            mutationError = notPermittedMessage
            return
        }
        scope.launch {
            runCatching {
                val metadata = withContext(Dispatchers.IO) {
                    uris.map { uri ->
                        displayName(context.contentResolver, uri).orEmpty().ifBlank { "upload.bin" } to
                            selectedDocumentSize(context.contentResolver, uri)
                    }
                }
                val totalBytes = metadata.takeIf { entries -> entries.all { (it.second ?: -1L) >= 0L } }
                    ?.fold(0L) { total, item -> Math.addExact(total, item.second!!) }
                val label = if (metadata.size == 1) metadata.first().first else "${metadata.size} files"
                if (!uploadFits(totalBytes, label)) return@launch
                val coordinator = transferCoordinator ?: error("No active server")
                uris.forEach { uri ->
                    context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    coordinator.enqueueFile(uri, path)
                }
            }.onFailure { mutationError = it.message ?: it.toString() }
        }
    }

    val uploadFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty() && directoryPermissions.canUpload) uploadFiles(uris)
        else if (uris.isNotEmpty()) mutationError = notPermittedMessage
    }
    val uploadFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null && directoryPermissions.canUpload && directoryPermissions.canCreate) {
            if (uri !in pendingFolderUris) pendingFolderUris = pendingFolderUris + uri
            folderBatchDialogOpen = true
        } else if (uri != null) mutationError = notPermittedMessage
    }

    fun uploadFolders(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            runCatching {
                val metadata = withContext(Dispatchers.IO) {
                    uris.map { uri ->
                        val root = DocumentFile.fromTreeUri(context, uri) ?: error(uploadSizeUnavailable)
                        (root.name ?: "folder") to selectedTreeSize(context.contentResolver, root)
                    }
                }
                val totalBytes = metadata.takeIf { entries -> entries.all { (it.second ?: -1L) >= 0L } }
                    ?.fold(0L) { total, item -> Math.addExact(total, item.second!!) }
                val label = if (metadata.size == 1) metadata.first().first else "${metadata.size} folders"
                if (!uploadFits(totalBytes, label)) return@launch
                val coordinator = transferCoordinator ?: error("No active server")
                uris.forEach { uri ->
                    context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    coordinator.enqueueFolder(uri, path)
                }
            }.onSuccess { refresh() }.onFailure { mutationError = it.message ?: it.toString() }
        }
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

    fun archiveName(items: List<RemoteResource>, format: ArchiveFormat): String {
        val stem = if (items.size == 1 && items.first().isDirectory) {
            items.first().name
        } else {
            "download-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}"
        }
        return "$stem.${format.extension}"
    }

    fun enqueueArchive(
        items: List<RemoteResource>,
        format: ArchiveFormat,
        conflict: DownloadConflict = DownloadConflict.Replace,
        requestedName: String? = null,
        checkExisting: Boolean = true,
    ) {
        val treeUri = settings.downloadTreeUri?.let(Uri::parse)
        val coordinator = transferCoordinator
        if (treeUri == null || coordinator == null) { downloadError = unavailableDirectory; return }
        val requestName = requestedName ?: archiveName(items, format)
        scope.launch {
            runCatching {
                if (checkExisting && conflict == DownloadConflict.Replace && coordinator.hasDownloadConflict(treeUri, requestName)) {
                    pendingArchiveDownload = PendingArchiveDownload(items, format, requestName)
                } else {
                    coordinator.enqueueArchiveDownload(
                        remotePaths = items.map { it.path },
                        name = requestName,
                        totalBytes = items.sumOf { it.size.coerceAtLeast(0L) },
                        treeUri = treeUri,
                        algorithm = format.algorithm,
                        conflict = conflict,
                    )
                    selectedPaths = emptySet()
                }
            }.onFailure { downloadError = it.message ?: unavailableDirectory }
        }
    }

    fun resolveArchiveDownload(conflict: DownloadConflict) {
        val pending = pendingArchiveDownload ?: return
        pendingArchiveDownload = null
        if (conflict != DownloadConflict.Cancel) enqueueArchive(pending.items, pending.format, conflict, pending.name, checkExisting = false)
    }

    fun openEnteredPath() {
        val current = profile ?: return
        val target = BrowserPath.normalize(pathInput)
        pathResolving = true
        scope.launch {
            if (target == "/") {
                path = target
                pathDialogOpen = false
                mutationError = null
                pathResolving = false
                return@launch
            }
            val parent = BrowserPath.parent(target)
            withContext(Dispatchers.IO) { sessionRepository.listWithPermissions(current, parent) }
                .onSuccess { listing ->
                    val targetItem = listing.resources.firstOrNull { item ->
                        BrowserPath.normalize(item.path) == target ||
                            runCatching { BrowserPath.child(parent, item.name) == target }.getOrDefault(false)
                    }
                    when {
                        targetItem == null -> mutationError = pathNotFoundMessage
                        targetItem.isDirectory -> {
                            path = target
                            pathDialogOpen = false
                            mutationError = null
                        }
                        else -> {
                            path = parent
                            preview = targetItem
                            pathDialogOpen = false
                            mutationError = null
                        }
                    }
                }
                .onFailure { mutationError = it.message ?: pathNotFoundMessage }
            pathResolving = false
        }
    }

    fun openShareDialog(item: RemoteResource) {
        val current = profile ?: return
        shareDialogOpen = true
        shareLoading = true
        shareError = null
        scope.launch {
            withContext(Dispatchers.IO) { sessionRepository.shares(current, item.path) }
                .onSuccess { shareLinks = it }
                .onFailure { shareError = it.message ?: it.toString() }
            shareLoading = false
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

    if (folderBatchDialogOpen) {
        AlertDialog(
            onDismissRequest = { folderBatchDialogOpen = false; pendingFolderUris = emptyList() },
            title = { Text(stringResource(R.string.upload_folders_title)) },
            text = { Text(stringResource(R.string.folders_selected, pendingFolderUris.size)) },
            confirmButton = {
                TextButton(onClick = {
                    val chosen = pendingFolderUris
                    pendingFolderUris = emptyList()
                    folderBatchDialogOpen = false
                    uploadFolders(chosen)
                }) { Text(stringResource(R.string.upload_selected_folders)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    folderBatchDialogOpen = false
                    uploadFolderPicker.launch(null)
                }) { Text(stringResource(R.string.select_another_folder)) }
            },
        )
    }

    if (archiveDialogOpen) {
        AlertDialog(
            onDismissRequest = { archiveDialogOpen = false; archiveItems = emptyList() },
            title = { Text(stringResource(R.string.archive_download_title)) },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.archive_download_message))
                    archiveFormats.forEach { format ->
                        Button(
                            onClick = {
                                val chosen = archiveItems
                                archiveItems = emptyList()
                                archiveDialogOpen = false
                                enqueueArchive(chosen, format)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(format.extension) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { archiveDialogOpen = false; archiveItems = emptyList() }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (pathDialogOpen) {
        AlertDialog(
            onDismissRequest = { if (!pathResolving) pathDialogOpen = false },
            modifier = Modifier.fillMaxWidth(0.88f),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            title = { Text(stringResource(R.string.enter_path)) },
            text = {
                DialogOutlinedTextField(
                    value = pathInput,
                    onValueChange = { pathInput = it },
                    label = stringResource(R.string.current_path),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = ::openEnteredPath,
                    enabled = !pathResolving && pathInput.isNotBlank(),
                ) { Text(stringResource(R.string.go_to_path)) }
            },
            dismissButton = {
                TextButton(onClick = { pathDialogOpen = false }, enabled = !pathResolving) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (createFolderOpen) {
        AlertDialog(
            onDismissRequest = { if (!mutating) createFolderOpen = false },
            modifier = Modifier.fillMaxWidth(0.82f),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            title = { Text(stringResource(R.string.new_folder)) },
            text = { DialogOutlinedTextField(folderName, { folderName = it }, stringResource(R.string.folder_name)) },
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

    val conflictingName = pendingDownload?.name ?: pendingArchiveDownload?.name
    if (conflictingName != null) {
        AlertDialog(
            onDismissRequest = {
                if (pendingDownload != null) resolveDownload(DownloadConflict.Cancel) else resolveArchiveDownload(DownloadConflict.Cancel)
            },
            title = { Text("File already exists") },
            text = { Text("Choose how to save $conflictingName.") },
            confirmButton = { TextButton(onClick = {
                if (pendingDownload != null) resolveDownload(DownloadConflict.Replace) else resolveArchiveDownload(DownloadConflict.Replace)
            }) { Text("Replace") } },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        if (pendingDownload != null) resolveDownload(DownloadConflict.KeepBoth) else resolveArchiveDownload(DownloadConflict.KeepBoth)
                    }) { Text("Keep both") }
                    TextButton(onClick = {
                        if (pendingDownload != null) resolveDownload(DownloadConflict.Cancel) else resolveArchiveDownload(DownloadConflict.Cancel)
                    }) { Text(stringResource(R.string.cancel)) }
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
            text = {
                Text(
                    if (deleteOwners.isNotEmpty()) {
                        stringResource(R.string.owned_folder_delete_message, deleteOwners.joinToString(", "))
                    } else {
                        stringResource(R.string.confirm_delete_message, selected.size)
                    },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !mutating && actions.canDelete,
                    onClick = {
                        val current = profile ?: return@TextButton
                        mutating = true
                        scope.launch {
                            withContext(Dispatchers.IO) { sessionRepository.delete(current, selected.map { it.path }) }
                                .onSuccess {
                                    selectedPaths = emptySet()
                                    deleteOwners = emptyList()
                                    deleteConfirmationOpen = false
                                    refresh()
                                }
                                .onFailure {
                                    mutationError = it.message ?: it.toString()
                                    deleteOwners = emptyList()
                                    deleteConfirmationOpen = false
                                    refresh()
                                }
                            mutating = false
                        }
                    },
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        deleteOwners = emptyList()
                        deleteConfirmationOpen = false
                    },
                    enabled = !mutating,
                ) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (renameDialogOpen) {
        val item = selected.singleOrNull()
        AlertDialog(
            onDismissRequest = { if (!mutating) renameDialogOpen = false },
            modifier = Modifier.fillMaxWidth(0.82f),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            title = { Text(stringResource(R.string.rename_item)) },
            text = {
                DialogOutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    label = stringResource(R.string.new_name),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !mutating && item != null && renameValue.isValidResourceName() && renameValue != item.name,
                    onClick = {
                        val current = profile ?: return@TextButton
                        val renaming = item ?: return@TextButton
                        mutating = true
                        scope.launch {
                            withContext(Dispatchers.IO) { sessionRepository.rename(current, renaming, renameValue.trim()) }
                                .onSuccess {
                                    selectedPaths = emptySet()
                                    renameDialogOpen = false
                                    refresh()
                                }
                                .onFailure { mutationError = it.message ?: it.toString() }
                            mutating = false
                        }
                    },
                ) { Text(stringResource(R.string.rename)) }
            },
            dismissButton = { TextButton(onClick = { renameDialogOpen = false }, enabled = !mutating) { Text(stringResource(R.string.cancel)) } },
        )
    }

    destinationOperation?.let { operation ->
        AlertDialog(
            onDismissRequest = { if (!mutating) destinationOperation = null },
            modifier = Modifier.fillMaxWidth(0.82f),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            title = { Text(stringResource(if (operation == DestinationOperation.Copy) R.string.copy_selected else R.string.move_selected)) },
            text = {
                Column(Modifier.fillMaxWidth().height(300.dp)) {
                    Text(destinationPath, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (BrowserPath.normalize(destinationPath) != "/") {
                        TextButton(onClick = { destinationPath = BrowserPath.parent(destinationPath) }) {
                            Text("‹  ${stringResource(R.string.destination_folder)}")
                        }
                    }
                    if (destinationLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    LazyColumn(Modifier.weight(1f)) {
                        items(destinationDirectories, key = { it.path }) { directory ->
                            FolderNavigationRow(
                                name = directory.name,
                                iconRes = folderIconResource(settings.folderIconSet),
                                folderContentDescription = directory.name,
                                onClick = { destinationPath = directory.path },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !mutating && destinationPath.isNotBlank(),
                    onClick = {
                        val current = profile ?: return@TextButton
                        val selectedItems = selected.toList()
                        mutating = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                if (operation == DestinationOperation.Copy) sessionRepository.copy(current, selectedItems, destinationPath)
                                else sessionRepository.move(current, selectedItems, destinationPath)
                            }
                            result
                                .onSuccess {
                                    selectedPaths = emptySet()
                                    destinationOperation = null
                                    refresh()
                                }
                                .onFailure { mutationError = it.message ?: it.toString() }
                            mutating = false
                        }
                    },
                ) { Text(stringResource(if (operation == DestinationOperation.Copy) R.string.copy_here else R.string.move_here)) }
            },
            dismissButton = { TextButton(onClick = { destinationOperation = null }, enabled = !mutating) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (shareDialogOpen) {
        val item = selected.singleOrNull()
        if (item != null) {
            ShareDialog(
                resourceName = item.name,
                shares = shareLinks,
                loading = shareLoading,
                mutating = shareMutating,
                error = shareError,
                onDismiss = { if (!shareMutating) shareDialogOpen = false },
                onCreate = { duration, unit, password ->
                    val current = profile ?: return@ShareDialog
                    shareMutating = true
                    shareError = null
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            sessionRepository.createShare(current, item.path, duration, unit, password)
                        }.onSuccess { created ->
                            shareLinks = shareLinks.filterNot { it.hash == created.hash } + created
                        }.onFailure { shareError = it.message ?: it.toString() }
                        shareMutating = false
                    }
                },
                onCopy = { share ->
                    val current = profile ?: return@ShareDialog
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(shareLinkLabel, sessionRepository.shareUrl(current, share.hash)))
                },
                onDelete = { share ->
                    val current = profile ?: return@ShareDialog
                    shareMutating = true
                    shareError = null
                    scope.launch {
                        withContext(Dispatchers.IO) { sessionRepository.deleteShare(current, share.hash) }
                            .onSuccess { shareLinks = shareLinks.filterNot { it.hash == share.hash } }
                            .onFailure { shareError = it.message ?: it.toString() }
                        shareMutating = false
                    }
                },
            )
        } else {
            shareDialogOpen = false
        }
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
                IconButton(onClick = onServerSettings, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(AppIcons.Settings), stringResource(R.string.server_settings), modifier = Modifier.size(28.dp))
                }
            })
        } else {
            AppBar(selected.size.toString(), onBack = { selectedPaths = emptySet() }, action = {
                if (actions.canRename) SelectionActionIcon(AppIcons.Edit, stringResource(R.string.rename), onClick = {
                    renameValue = selected.single().name
                    renameDialogOpen = true
                })
                if (actions.canDownload) SelectionActionIcon(AppIcons.Download, stringResource(R.string.download), onClick = {
                    if (selected.size == 1 && !selected.first().isDirectory) {
                        download(selected.first())
                    } else {
                        archiveItems = selected.toList()
                        archiveDialogOpen = true
                    }
                })
                if (actions.canCopy) SelectionActionIcon(AppIcons.Copy, stringResource(R.string.copy), onClick = {
                    destinationPath = "/"
                    destinationOperation = DestinationOperation.Copy
                })
                if (actions.canMove) SelectionActionIcon(AppIcons.Move, stringResource(R.string.move), onClick = {
                    destinationPath = "/"
                    destinationOperation = DestinationOperation.Move
                })
                if (actions.canShare) SelectionActionIcon(AppIcons.Share, stringResource(R.string.share), onClick = { openShareDialog(selected.single()) })
                if (actions.canDelete) SelectionActionIcon(AppIcons.Delete, stringResource(R.string.delete), onClick = {
                    val current = profile ?: return@SelectionActionIcon
                    mutating = true
                    scope.launch {
                        withContext(Dispatchers.IO) { sessionRepository.currentUser(current) }
                            .fold(
                                onSuccess = { user ->
                                    if (user.admin) {
                                        withContext(Dispatchers.IO) {
                                            sessionRepository.resourceOwners(current, selected.map { it.path })
                                        }.onSuccess {
                                            deleteOwners = normalizedOwnerNames(it)
                                            deleteConfirmationOpen = true
                                        }.onFailure { mutationError = it.message ?: it.toString() }
                                    } else {
                                        deleteOwners = emptyList()
                                        deleteConfirmationOpen = true
                                    }
                                },
                                onFailure = { mutationError = it.message ?: it.toString() },
                            )
                        mutating = false
                    }
                })
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
            Text(
                path,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(pathScrollState)
                    .clickable {
                        pathInput = path
                        pathDialogOpen = true
                    },
                maxLines = 1,
                softWrap = false,
            )
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
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            DiskUsageSummary(diskUsage, Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        sortAscending = !sortAscending
                        resources = resources.sortedByResourceName(sortAscending)
                        scope.launch {
                            if (settings.gridView) gridState.scrollToItem(0) else listState.scrollToItem(0)
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text(stringResource(R.string.name))
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        painterResource(if (sortAscending) AppIcons.SortAscending else AppIcons.SortDescending),
                        contentDescription = stringResource(if (sortAscending) R.string.sort_ascending else R.string.sort_descending),
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.items_count, resources.size), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    gridItems(resources, key = { it.path }) { item ->
                        ResourceGridCard(item, settings, profile, sessionRepository, syncFolders.firstOrNull { BrowserPath.normalize(it.remotePath) == BrowserPath.normalize(item.path) }, item.path in selectedPaths, { openItem(item) }, { selectedPaths = selectedPaths.toggle(item.path) }, { download(item) })
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(resources, key = { it.path }) { item ->
                        ResourceRow(
                            item = item,
                            settings = settings,
                            profile = profile,
                            sessionRepository = sessionRepository,
                            syncFolder = syncFolders.firstOrNull { BrowserPath.normalize(it.remotePath) == BrowserPath.normalize(item.path) },
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

private fun List<RemoteResource>.sortedByResourceName(ascending: Boolean): List<RemoteResource> = sortedWith { left, right ->
    if (left.isDirectory != right.isDirectory) {
        if (left.isDirectory) -1 else 1
    } else {
        left.name.compareTo(right.name, ignoreCase = true) * if (ascending) 1 else -1
    }
}

internal data class SortRequest(
    val ascending: Boolean,
    val generation: Long,
    val targetIndex: Int = 0,
)

internal fun nextSortRequest(current: SortRequest): SortRequest =
    SortRequest(ascending = !current.ascending, generation = current.generation + 1)

@Composable
private fun DiskUsageSummary(usage: DiskUsage?, modifier: Modifier = Modifier) {
    Column(modifier) {
        if (usage == null || usage.total <= 0L) {
            Text(
                stringResource(R.string.disk_usage_unavailable),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                stringResource(R.string.disk_usage, formatBinaryBytes(usage.used), formatBinaryBytes(usage.total)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { (usage.used.toDouble() / usage.total.toDouble()).coerceIn(0.0, 1.0).toFloat() },
                modifier = Modifier.fillMaxWidth(0.72f),
            )
        }
    }
}

internal fun formatBinaryBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KiB", "MiB", "GiB", "TiB")
    val unitIndex = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(1, units.size) - 1
    val value = bytes / 1024.0.pow(unitIndex + 1)
    return String.format(Locale.getDefault(), "%.2f %s", value, units[unitIndex])
}

private fun String.isValidResourceName(): Boolean {
    val value = trim()
    return value.isNotEmpty() && value != "." && value != ".." && '/' !in value && '\\' !in value
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
    syncFolder: SyncFolder?,
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
            ResourceVisual(item, settings, profile, sessionRepository, syncFolder, Modifier.size(44.dp))
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
    syncFolder: SyncFolder?,
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
            ResourceVisual(item, settings, profile, sessionRepository, syncFolder, Modifier.size(76.dp).align(Alignment.TopCenter))
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
private fun SelectionActionIcon(iconRes: Int, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(painterResource(iconRes), description, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun ResourceVisual(
    item: RemoteResource,
    settings: AppSettings,
    profile: ServerProfile?,
    sessionRepository: SessionRepository,
    syncFolder: SyncFolder?,
    modifier: Modifier,
) {
    if (item.isDirectory) {
        if (syncFolder == null) Icon(painterResource(folderIconResource(settings.folderIconSet)), null, modifier, tint = Color.Unspecified)
        else SyncFolderIcon(folderIconResource(settings.folderIconSet), syncFolder, modifier)
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
    val cacheNamespace = remember(profile.id) {
        ServerIdentityStore(context.applicationContext).get(profile.id)?.let { "${it.serverId}-${it.userId}" } ?: profile.id
    }
    val cacheFile = remember(cacheNamespace, item.path) {
        AppCacheManager.thumbnailFile(context, cacheNamespace, item.path)
    }
    var bitmap by remember(cacheFile) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(cacheFile) {
        bitmap = withContext(Dispatchers.IO) {
            val cachedBitmap = if (cacheFile.isFile && cacheFile.length() > 0L) {
                BitmapFactory.decodeFile(cacheFile.path)
            } else {
                null
            }
            if (cachedBitmap != null) {
                AppCacheManager.recordAccess(cacheFile)
                cachedBitmap
            } else if (previewKind == PreviewKind.Image) {
                cacheFile.delete()
                cacheFile.parentFile?.mkdirs()
                sessionRepository.thumbnail(profile, item.path, cacheFile)
                AppCacheManager.recordWrite(context, cacheFile)
                BitmapFactory.decodeFile(cacheFile.path)
            } else {
                cacheFile.delete()
                cacheFile.parentFile?.mkdirs()
                val ready = fetchSharedVideoThumbnail(
                    fetch = {
                        cacheFile.delete()
                        sessionRepository.cachedVideoThumbnail(profile, item.path, cacheFile).isSuccess &&
                            cacheFile.isFile && cacheFile.length() > 0L
                    },
                    queue = {
                        sessionRepository.requestVideoThumbnail(profile, item.path).isSuccess
                    },
                )
                if (ready) {
                    AppCacheManager.recordWrite(context, cacheFile)
                    BitmapFactory.decodeFile(cacheFile.path)
                } else {
                    cacheFile.delete()
                    null
                }
            }
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

private fun selectedDocumentSize(resolver: ContentResolver, uri: Uri): Long? =
    resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst() || cursor.isNull(0)) null else cursor.getLong(0).takeIf { it >= 0 }
    }

private fun selectedTreeSize(resolver: ContentResolver, document: DocumentFile): Long? {
    if (document.isFile) return selectedDocumentSize(resolver, document.uri)
    if (!document.isDirectory) return null
    var total = 0L
    for (child in document.listFiles()) {
        val childSize = selectedTreeSize(resolver, child) ?: return null
        total = runCatching { Math.addExact(total, childSize) }.getOrNull() ?: return null
    }
    return total
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
