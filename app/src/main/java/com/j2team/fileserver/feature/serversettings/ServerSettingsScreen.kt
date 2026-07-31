package com.j2team.fileserver.feature.serversettings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.j2team.fileserver.AppBar
import com.j2team.fileserver.R
import com.j2team.fileserver.formatBytes
import com.j2team.fileserver.core.model.AdminDirectoryListing
import com.j2team.fileserver.core.model.QuotaUnit
import com.j2team.fileserver.core.model.ServerGlobalSettings
import com.j2team.fileserver.core.model.ServerProfile
import com.j2team.fileserver.core.model.ServerSettingsSection
import com.j2team.fileserver.core.model.ServerUser
import com.j2team.fileserver.core.model.ServerUserPermissions
import com.j2team.fileserver.core.model.ShareLink
import com.j2team.fileserver.core.model.bytesToMegabytes
import com.j2team.fileserver.core.model.megabytesToBytes
import com.j2team.fileserver.core.model.quotaBytesFromInput
import com.j2team.fileserver.core.model.quotaInputFromBytes
import com.j2team.fileserver.core.model.StorageValidation
import com.j2team.fileserver.core.model.validateUserStorage
import com.j2team.fileserver.core.model.visibleSettingsSections
import com.j2team.fileserver.core.session.SessionRepository
import com.j2team.fileserver.core.ui.AppIcons
import com.j2team.fileserver.feature.sync.SyncFoldersScreen
import com.j2team.fileserver.feature.settings.FolderIconSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ServerSettingsScreen(
    profile: ServerProfile?,
    repository: SessionRepository,
    onBack: () -> Unit,
    onOpenRemoteFolder: (String) -> Unit = {},
    folderIconSet: FolderIconSet = FolderIconSet.Classic,
) {
    val currentProfile = profile
    if (currentProfile == null) {
        onBack()
        return
    }
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var currentUser by remember(currentProfile.id) { mutableStateOf<ServerUser?>(null) }
    var section by remember { mutableStateOf(ServerSettingsSection.Profile) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var shares by remember { mutableStateOf<List<ShareLink>>(emptyList()) }
    var global by remember { mutableStateOf(ServerGlobalSettings()) }
    var users by remember { mutableStateOf<List<ServerUser>>(emptyList()) }
    var updateSucceeded by remember { mutableStateOf(false) }
    val showUpdateSuccess = { updateSucceeded = true }

    fun reload() {
        loading = true
        error = null
        scope.launch {
            val userResult = withContext(Dispatchers.IO) { repository.currentUser(currentProfile) }
            userResult.onSuccess { user ->
                currentUser = user
                if (user.admin || user.permissions.share) {
                    withContext(Dispatchers.IO) { repository.allShares(currentProfile) }
                        .onSuccess { shares = it }
                }
                if (user.admin) {
                    withContext(Dispatchers.IO) { repository.serverSettings(currentProfile) }
                        .onSuccess { global = it }
                    withContext(Dispatchers.IO) { repository.users(currentProfile) }
                        .onSuccess { users = it }
                }
            }.onFailure { error = it.message ?: it.toString() }
            loading = false
        }
    }

    LaunchedEffect(currentProfile.id) { reload() }
    LaunchedEffect(updateSucceeded) {
        if (updateSucceeded) {
            delay(2_500)
            updateSucceeded = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            AppBar(stringResource(R.string.server_settings), onBack)
            if (loading) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            }
            val visible = currentUser?.visibleSettingsSections().orEmpty()
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                visible.forEach { choice ->
                    FilterChip(
                        selected = section == choice,
                        onClick = { section = choice },
                        label = { Text(stringResource(choice.labelResource())) },
                    )
                }
            }
            when (section) {
                ServerSettingsSection.Profile -> currentUser?.let { user ->
                    ProfileSettings(
                        user = user,
                        busy = busy,
                        onSave = { updated, newPassword, currentPassword ->
                            busy = true
                            error = null
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    repository.saveUser(
                                        profile = currentProfile,
                                        user = updated,
                                        newPassword = newPassword,
                                        currentPassword = currentPassword,
                                        profileOnly = true,
                                    )
                                }.onSuccess {
                                    currentUser = it
                                    showUpdateSuccess()
                                }
                                    .onFailure { error = it.message ?: it.toString() }
                                busy = false
                            }
                        },
                    )
                }
                ServerSettingsSection.Sync -> SyncFoldersScreen(
                    profile = currentProfile,
                    repository = repository,
                    onOpenRemoteFolder = onOpenRemoteFolder,
                    folderIconSet = folderIconSet,
                )
                ServerSettingsSection.Shares -> ShareManagement(
                    shares = shares,
                    onCopy = { share ->
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Share link", repository.shareUrl(currentProfile, share.hash)))
                    },
                    onDelete = { share ->
                        busy = true
                        scope.launch {
                            withContext(Dispatchers.IO) { repository.deleteShare(currentProfile, share.hash) }
                                .onSuccess { shares = shares.filterNot { it.hash == share.hash } }
                                .onFailure { error = it.message ?: it.toString() }
                            busy = false
                        }
                    },
                )
                ServerSettingsSection.Global -> GlobalSettings(
                    settings = global,
                    busy = busy,
                    onSave = { updated ->
                        busy = true
                        error = null
                        scope.launch {
                            withContext(Dispatchers.IO) { repository.updateServerSettings(currentProfile, updated) }
                                .onSuccess {
                                    global = it
                                    showUpdateSuccess()
                                }
                                .onFailure { error = it.message ?: it.toString() }
                            busy = false
                        }
                    },
                )
                ServerSettingsSection.Users -> UsersSettings(
                    users = users,
                    profile = currentProfile,
                    repository = repository,
                    defaultFolder = global.userHomeBasePath,
                    busy = busy,
                    onSave = { updated, password ->
                        busy = true
                        error = null
                        scope.launch {
                            withContext(Dispatchers.IO) { repository.saveUser(currentProfile, updated, password) }
                                .onSuccess { saved ->
                                    withContext(Dispatchers.IO) { repository.users(currentProfile) }
                                        .onSuccess { refreshed -> users = refreshed.sortedBy { it.username.lowercase() } }
                                        .onFailure {
                                            users = (users.filterNot { it.id == saved.id } + saved)
                                                .sortedBy { it.username.lowercase() }
                                        }
                                    showUpdateSuccess()
                                }.onFailure { error = it.message ?: it.toString() }
                            busy = false
                        }
                    },
                    onDelete = { user ->
                        busy = true
                        scope.launch {
                            withContext(Dispatchers.IO) { repository.deleteUser(currentProfile, user.id) }
                                .onSuccess { users = users.filterNot { it.id == user.id } }
                                .onFailure { error = it.message ?: it.toString() }
                            busy = false
                        }
                    },
                )
            }
        }
        if (updateSucceeded) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shadowElevation = 6.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(28.dp).height(28.dp),
                    )
                    Text(stringResource(R.string.update_success))
                }
            }
        }
    }
}

@Composable
private fun ProfileSettings(
    user: ServerUser,
    busy: Boolean,
    onSave: (ServerUser, String, String) -> Unit,
) {
    var draft by remember(user) { mutableStateOf(user) }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var currentPassword by remember { mutableStateOf("") }
    SettingsList {
        item { SectionTitle(stringResource(R.string.profile_settings)) }
        item { ToggleRow(stringResource(R.string.hide_dotfiles), draft.hideDotfiles) { draft = draft.copy(hideDotfiles = it) } }
        item { ToggleRow(stringResource(R.string.single_click), draft.singleClick) { draft = draft.copy(singleClick = it) } }
        item { ToggleRow(stringResource(R.string.redirect_after_move), draft.redirectAfterCopyMove) { draft = draft.copy(redirectAfterCopyMove = it) } }
        item { ToggleRow(stringResource(R.string.exact_date_format), draft.dateFormat) { draft = draft.copy(dateFormat = it) } }
        if (!draft.lockPassword) {
            item { SectionTitle(stringResource(R.string.change_password)) }
            item { PasswordField(stringResource(R.string.new_password), newPassword) { newPassword = it } }
            item { PasswordField(stringResource(R.string.confirm_password), confirmPassword) { confirmPassword = it } }
            item { PasswordField(stringResource(R.string.current_password), currentPassword) { currentPassword = it } }
        }
        item {
            SaveButton(
                enabled = !busy &&
                    (newPassword.isBlank() || (newPassword == confirmPassword && currentPassword.isNotBlank())),
                onClick = { onSave(draft, newPassword, currentPassword) },
            )
        }
    }
}

@Composable
private fun ShareManagement(shares: List<ShareLink>, onCopy: (ShareLink) -> Unit, onDelete: (ShareLink) -> Unit) {
    SettingsList {
        item { SectionTitle(stringResource(R.string.share_management)) }
        if (shares.isEmpty()) item { Text(stringResource(R.string.no_shares), modifier = Modifier.padding(16.dp)) }
        items(shares, key = { it.hash }) { share ->
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text(share.path, style = MaterialTheme.typography.titleSmall)
                    Text(share.username.ifBlank { share.hash }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { onCopy(share) }) { Text(stringResource(R.string.copy_link)) }
                        TextButton(onClick = { onDelete(share) }) { Text(stringResource(R.string.delete)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GlobalSettings(settings: ServerGlobalSettings, busy: Boolean, onSave: (ServerGlobalSettings) -> Unit) {
    var draft by remember(settings) { mutableStateOf(settings) }
    var chunkSizeMb by remember(settings.chunkSizeBytes) { mutableStateOf(bytesToMegabytes(settings.chunkSizeBytes)) }
    val validChunkSize = megabytesToBytes(chunkSizeMb)
    SettingsList {
        item { SectionTitle(stringResource(R.string.global_settings)) }
        item { ToggleRow(stringResource(R.string.allow_signup), draft.signup) { draft = draft.copy(signup = it) } }
        item { ToggleRow(stringResource(R.string.auto_create_home), draft.createUserDir) { draft = draft.copy(createUserDir = it) } }
        item { ToggleRow(stringResource(R.string.hide_login_button), draft.hideLoginButton) { draft = draft.copy(hideLoginButton = it) } }
        item { SettingField(stringResource(R.string.user_home_path), draft.userHomeBasePath) { draft = draft.copy(userHomeBasePath = it) } }
        item { IntField(stringResource(R.string.minimum_password_length), draft.minimumPasswordLength) { draft = draft.copy(minimumPasswordLength = it) } }
        item { SectionTitle(stringResource(R.string.branding)) }
        item { ToggleRow(stringResource(R.string.disable_external_links), draft.disableExternalLinks) { draft = draft.copy(disableExternalLinks = it) } }
        item { ToggleRow(stringResource(R.string.disable_disk_graph), draft.disableUsedPercentage) { draft = draft.copy(disableUsedPercentage = it) } }
        item { SettingField(stringResource(R.string.instance_name), draft.instanceName) { draft = draft.copy(instanceName = it) } }
        item { SettingField(stringResource(R.string.branding_directory), draft.brandingDirectory) { draft = draft.copy(brandingDirectory = it) } }
        item { SectionTitle(stringResource(R.string.chunked_uploads)) }
        item {
            SettingField(stringResource(R.string.chunk_size), chunkSizeMb) { value ->
                chunkSizeMb = value
                megabytesToBytes(value)?.let { draft = draft.copy(chunkSizeBytes = it) }
            }
        }
        item { IntField(stringResource(R.string.retry_count), draft.retryCount) { draft = draft.copy(retryCount = it) } }
        item { SaveButton(!busy && validChunkSize != null) { onSave(draft.copy(chunkSizeBytes = validChunkSize!!)) } }
    }
}

@Composable
private fun UsersSettings(
    users: List<ServerUser>,
    profile: ServerProfile,
    repository: SessionRepository,
    defaultFolder: String,
    busy: Boolean,
    onSave: (ServerUser, String) -> Unit,
    onDelete: (ServerUser) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<ServerUser?>(null) }
    var creating by remember { mutableStateOf(false) }
    var editorLoading by remember { mutableStateOf(false) }
    var editorError by remember { mutableStateOf<String?>(null) }
    var deleteCandidate by remember { mutableStateOf<ServerUser?>(null) }

    fun edit(user: ServerUser) {
        creating = false
        editorLoading = true
        editorError = null
        scope.launch {
            withContext(Dispatchers.IO) { repository.user(profile, user.id) }
                .onSuccess { editing = it }
                .onFailure { editorError = it.message ?: it.toString() }
            editorLoading = false
        }
    }

    SettingsList {
        item {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(stringResource(R.string.users), Modifier.weight(1f))
                Button(
                    onClick = {
                        creating = true
                        editing = ServerUser(0, "", scope = defaultFolder.ifBlank { "/" })
                    },
                ) { Text(stringResource(R.string.new_user)) }
            }
        }
        editorError?.let { message ->
            item { Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        }
        if (editorLoading) {
            item { CircularProgressIndicator(Modifier.padding(16.dp)) }
        }
        items(users, key = { it.id }) { user ->
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(user.username, style = MaterialTheme.typography.titleMedium)
                        Text(user.scope, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { edit(user) }, enabled = !busy && !editorLoading) {
                        Text(stringResource(R.string.edit))
                    }
                    if (!user.admin) {
                        TextButton(onClick = { deleteCandidate = user }, enabled = !busy) {
                            Text(stringResource(R.string.delete))
                        }
                    }
                }
            }
        }
    }
    editing?.let { user ->
        UserEditorDialog(
            user = user,
            creating = creating,
            busy = busy,
            profile = profile,
            repository = repository,
            onDismiss = { editing = null },
            onSave = { updated, password -> onSave(updated, password); editing = null },
            onDelete = if (creating || user.admin) null else {
                { deleteCandidate = user }
            },
        )
    }
    deleteCandidate?.let { user ->
        AlertDialog(
            onDismissRequest = { if (!busy) deleteCandidate = null },
            modifier = Modifier.fillMaxWidth(0.9f),
            shape = RoundedCornerShape(24.dp),
            title = { Text(stringResource(R.string.delete_user_title)) },
            text = { Text(stringResource(R.string.delete_user_message, user.username)) },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        editing = null
                        deleteCandidate = null
                        onDelete(user)
                    },
                ) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }, enabled = !busy) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun UserEditorDialog(
    user: ServerUser,
    creating: Boolean,
    busy: Boolean,
    profile: ServerProfile,
    repository: SessionRepository,
    onDismiss: () -> Unit,
    onSave: (ServerUser, String) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var draft by remember(user) { mutableStateOf(user) }
    var password by remember { mutableStateOf("") }
    val initialQuota = remember(user) { quotaInputFromBytes(user.quotaBytes) }
    var quotaValue by remember(user) { mutableStateOf(initialQuota.value) }
    var quotaUnit by remember(user) { mutableStateOf(initialQuota.unit) }
    var quotaUnlimited by remember(user) { mutableStateOf(initialQuota.unlimited) }
    var selectedListing by remember(user) { mutableStateOf<AdminDirectoryListing?>(null) }
    var folderMissing by remember(user) { mutableStateOf(user.scopeMissing) }
    val quotaBytes = quotaBytesFromInput(quotaValue, quotaUnit, quotaUnlimited)
    val storageValidation = validateUserStorage(
        scopeMissing = folderMissing,
        quotaBytes = quotaBytes,
        unlimited = quotaUnlimited,
        listing = selectedListing,
    )
    val storageValid = storageValidation == StorageValidation.Valid
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.9f).heightIn(max = 620.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    stringResource(if (creating) R.string.new_user else R.string.edit_user),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(12.dp))
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(bottom = 8.dp),
                ) {
                    item {
                        SettingField(
                            stringResource(R.string.username),
                            draft.username,
                            modifier = Modifier.fillMaxWidth(),
                        ) { draft = draft.copy(username = it) }
                    }
                    item {
                        ServerFolderPicker(
                            profile = profile,
                            repository = repository,
                            selectedPath = draft.scope,
                            enabled = !busy,
                            onSelected = { draft = draft.copy(scope = it, scopeMissing = false) },
                            onSelectedListing = {
                                selectedListing = it
                                folderMissing = it == null
                            },
                        )
                    }
                    if (folderMissing) {
                        item {
                            Text(
                                stringResource(R.string.user_folder_missing),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    selectedListing?.let { capacity ->
                        item {
                            Text(
                                stringResource(
                                    R.string.folder_capacity,
                                    formatBytes(capacity.total),
                                    formatBytes(capacity.used),
                                    formatBytes(capacity.free),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        item {
                            Text(
                                stringResource(R.string.folder_content_size, formatBytes(capacity.contentBytes)),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    item {
                        ToggleRow(
                            stringResource(R.string.unlimited_quota),
                            quotaUnlimited,
                            modifier = Modifier.fillMaxWidth(),
                        ) { quotaUnlimited = it }
                    }
                    if (!quotaUnlimited) {
                        item {
                            OutlinedTextField(
                                value = quotaValue,
                                onValueChange = { quotaValue = it },
                                label = { Text(stringResource(R.string.storage_quota)) },
                                isError = storageValidation in setOf(
                                    StorageValidation.InvalidQuota,
                                    StorageValidation.BelowFolderContent,
                                    StorageValidation.AboveFilesystem,
                                ),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                QuotaUnit.entries.forEach { unit ->
                                    FilterChip(
                                        selected = quotaUnit == unit,
                                        onClick = { quotaUnit = unit },
                                        label = { Text(unit.name) },
                                    )
                                }
                            }
                        }
                    }
                    when (storageValidation) {
                        StorageValidation.InvalidQuota -> item {
                            Text(stringResource(R.string.quota_invalid), color = MaterialTheme.colorScheme.error)
                        }
                        StorageValidation.BelowFolderContent -> item {
                            Text(
                                stringResource(
                                    R.string.quota_below_content,
                                    formatBytes(selectedListing?.contentBytes ?: 0),
                                ),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        StorageValidation.AboveFilesystem -> item {
                            Text(
                                stringResource(
                                    R.string.quota_above_disk,
                                    formatBytes(selectedListing?.total ?: 0),
                                ),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        else -> Unit
                    }
                    if (!creating) {
                        item {
                            Text(
                                if (user.quotaBytes > 0) {
                                    stringResource(
                                        R.string.user_quota_usage,
                                        formatBytes(user.quotaUsedBytes),
                                        formatBytes(user.quotaBytes),
                                        formatBytes(user.quotaRemainingBytes),
                                    )
                                } else {
                                    stringResource(R.string.user_storage_used, formatBytes(user.quotaUsedBytes))
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    if (creating) {
                        item {
                            PasswordField(
                                stringResource(R.string.password),
                                password,
                                modifier = Modifier.fillMaxWidth(),
                            ) { password = it }
                        }
                    }
                    item {
                        ToggleRow(
                            stringResource(R.string.administrator),
                            draft.admin,
                            modifier = Modifier.fillMaxWidth(),
                        ) { draft = draft.copy(admin = it) }
                    }
                    if (!draft.admin) {
                        item { PermissionRow(stringResource(R.string.permission_create), draft.permissions.create) { draft = draft.copy(permissions = draft.permissions.copy(create = it)) } }
                        item { PermissionRow(stringResource(R.string.permission_delete), draft.permissions.delete) { draft = draft.copy(permissions = draft.permissions.copy(delete = it)) } }
                        item { PermissionRow(stringResource(R.string.permission_download), draft.permissions.download) { draft = draft.copy(permissions = draft.permissions.copy(download = it)) } }
                        item { PermissionRow(stringResource(R.string.permission_modify), draft.permissions.modify) { draft = draft.copy(permissions = draft.permissions.copy(modify = it)) } }
                        item { PermissionRow(stringResource(R.string.permission_rename), draft.permissions.rename) { draft = draft.copy(permissions = draft.permissions.copy(rename = it)) } }
                        item { PermissionRow(stringResource(R.string.permission_share), draft.permissions.share) { draft = draft.copy(permissions = draft.permissions.copy(share = it)) } }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    onDelete?.let { delete ->
                        TextButton(onClick = delete, enabled = !busy) {
                            Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                        }
                        Spacer(Modifier.weight(1f))
                    }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    TextButton(
                        onClick = {
                            onSave(
                                draft.copy(
                                    quotaBytes = quotaBytes ?: 0,
                                    quotaUnlimited = quotaUnlimited,
                                    scopeMissing = false,
                                ),
                                password,
                            )
                        },
                        enabled = !busy && storageValid && draft.username.isNotBlank() &&
                            (!creating || password.isNotBlank()),
                    ) {
                        Text(stringResource(if (creating) R.string.save else R.string.update))
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsList(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) =
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp), content = content)

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = modifier.padding(16.dp))

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    onChecked: (Boolean) -> Unit,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun PermissionRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChecked)
        Text(label)
    }
}

@Composable
private fun SettingField(
    label: String,
    value: String,
    modifier: Modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    onValue: (String) -> Unit,
) = OutlinedTextField(value, onValue, label = { Text(label) }, modifier = modifier, singleLine = true)

@Composable
private fun PasswordField(
    label: String,
    value: String,
    modifier: Modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    onValue: (String) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    painterResource(if (visible) AppIcons.VisibilityOff else AppIcons.Visibility),
                    contentDescription = stringResource(if (visible) R.string.hide_password else R.string.show_password),
                )
            }
        },
        modifier = modifier,
        singleLine = true,
    )
}

@Composable
private fun IntField(label: String, value: Int, onValue: (Int) -> Unit) =
    SettingField(label, value.toString()) { it.toIntOrNull()?.let(onValue) }

@Composable
private fun SaveButton(enabled: Boolean, onClick: () -> Unit) =
    Button(onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp)) { Text(stringResource(R.string.update)) }

private fun ServerSettingsSection.labelResource(): Int = when (this) {
    ServerSettingsSection.Profile -> R.string.profile
    ServerSettingsSection.Sync -> R.string.sync_folders
    ServerSettingsSection.Shares -> R.string.shares
    ServerSettingsSection.Global -> R.string.global
    ServerSettingsSection.Users -> R.string.users
}
