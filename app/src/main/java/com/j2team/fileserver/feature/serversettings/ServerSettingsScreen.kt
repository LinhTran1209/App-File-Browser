package com.j2team.fileserver.feature.serversettings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.j2team.fileserver.AppBar
import com.j2team.fileserver.R
import com.j2team.fileserver.core.model.ServerGlobalSettings
import com.j2team.fileserver.core.model.ServerProfile
import com.j2team.fileserver.core.model.ServerSettingsSection
import com.j2team.fileserver.core.model.ServerUser
import com.j2team.fileserver.core.model.ServerUserPermissions
import com.j2team.fileserver.core.model.ShareLink
import com.j2team.fileserver.core.model.bytesToMegabytes
import com.j2team.fileserver.core.model.megabytesToBytes
import com.j2team.fileserver.core.model.visibleSettingsSections
import com.j2team.fileserver.core.session.SessionRepository
import com.j2team.fileserver.core.ui.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ServerSettingsScreen(
    profile: ServerProfile?,
    repository: SessionRepository,
    onBack: () -> Unit,
) {
    val currentProfile = profile
    if (currentProfile == null) {
        onBack()
        return
    }
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
    val showUpdateSuccess = {
        Toast.makeText(context, context.getString(R.string.update_success), Toast.LENGTH_SHORT).show()
    }

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
                                repository.saveUser(currentProfile, updated, newPassword, currentPassword)
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
                busy = busy,
                onSave = { updated, password ->
                    busy = true
                    error = null
                    scope.launch {
                        withContext(Dispatchers.IO) { repository.saveUser(currentProfile, updated, password) }
                            .onSuccess { saved ->
                                users = (users.filterNot { it.id == saved.id } + saved).sortedBy { it.username.lowercase() }
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
    busy: Boolean,
    onSave: (ServerUser, String) -> Unit,
    onDelete: (ServerUser) -> Unit,
) {
    var editing by remember { mutableStateOf<ServerUser?>(null) }
    var creating by remember { mutableStateOf(false) }
    SettingsList {
        item {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(stringResource(R.string.users), Modifier.weight(1f))
                Button(onClick = { creating = true; editing = ServerUser(0, "") }) { Text(stringResource(R.string.new_user)) }
            }
        }
        items(users, key = { it.id }) { user ->
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(user.username, style = MaterialTheme.typography.titleMedium)
                        Text(user.scope, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { creating = false; editing = user }) { Text(stringResource(R.string.edit)) }
                    if (!user.admin) TextButton(onClick = { onDelete(user) }, enabled = !busy) { Text(stringResource(R.string.delete)) }
                }
            }
        }
    }
    editing?.let { user ->
        UserEditorDialog(
            user = user,
            creating = creating,
            busy = busy,
            onDismiss = { editing = null },
            onSave = { updated, password -> onSave(updated, password); editing = null },
        )
    }
}

@Composable
private fun UserEditorDialog(user: ServerUser, creating: Boolean, busy: Boolean, onDismiss: () -> Unit, onSave: (ServerUser, String) -> Unit) {
    var draft by remember(user) { mutableStateOf(user) }
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (creating) R.string.new_user else R.string.edit_user)) },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SettingField(stringResource(R.string.username), draft.username) { draft = draft.copy(username = it) }
                SettingField(stringResource(R.string.scope), draft.scope) { draft = draft.copy(scope = it) }
                if (creating) PasswordField(stringResource(R.string.password), password) { password = it }
                ToggleRow(stringResource(R.string.administrator), draft.admin) { draft = draft.copy(admin = it) }
                if (!draft.admin) {
                    PermissionRow(stringResource(R.string.permission_create), draft.permissions.create) { draft = draft.copy(permissions = draft.permissions.copy(create = it)) }
                    PermissionRow(stringResource(R.string.permission_delete), draft.permissions.delete) { draft = draft.copy(permissions = draft.permissions.copy(delete = it)) }
                    PermissionRow(stringResource(R.string.permission_download), draft.permissions.download) { draft = draft.copy(permissions = draft.permissions.copy(download = it)) }
                    PermissionRow(stringResource(R.string.permission_modify), draft.permissions.modify) { draft = draft.copy(permissions = draft.permissions.copy(modify = it)) }
                    PermissionRow(stringResource(R.string.permission_rename), draft.permissions.rename) { draft = draft.copy(permissions = draft.permissions.copy(rename = it)) }
                    PermissionRow(stringResource(R.string.permission_share), draft.permissions.share) { draft = draft.copy(permissions = draft.permissions.copy(share = it)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft, password) }, enabled = !busy && draft.username.isNotBlank() && (!creating || password.isNotBlank())) {
                Text(stringResource(if (creating) R.string.save else R.string.update))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SettingsList(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) =
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp), content = content)

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = modifier.padding(16.dp))

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
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
private fun SettingField(label: String, value: String, onValue: (String) -> Unit) =
    OutlinedTextField(value, onValue, label = { Text(label) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), singleLine = true)

@Composable
private fun PasswordField(label: String, value: String, onValue: (String) -> Unit) {
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
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
    ServerSettingsSection.Shares -> R.string.shares
    ServerSettingsSection.Global -> R.string.global
    ServerSettingsSection.Users -> R.string.users
}
