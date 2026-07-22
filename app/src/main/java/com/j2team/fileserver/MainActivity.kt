package com.j2team.fileserver

import android.app.LocaleManager
import android.content.Context
import android.content.ContentResolver
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.LocaleList
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.j2team.fileserver.core.model.RemoteResource
import com.j2team.fileserver.core.model.ServerProfile
import com.j2team.fileserver.core.network.Endpoint
import com.j2team.fileserver.core.network.FileBrowserClient
import com.j2team.fileserver.core.session.EncryptedSecretStore
import com.j2team.fileserver.core.session.LoginRequiredException
import com.j2team.fileserver.core.session.SessionRepository
import com.j2team.fileserver.core.ui.FileServerTheme
import com.j2team.fileserver.core.ui.AppIcons
import com.j2team.fileserver.feature.browser.BrowserPath
import com.j2team.fileserver.feature.browser.BrowserScreen
import com.j2team.fileserver.feature.preview.PreviewScreen
import com.j2team.fileserver.feature.servers.ServerStore
import com.j2team.fileserver.feature.settings.AppSettings
import com.j2team.fileserver.feature.settings.AppTheme
import com.j2team.fileserver.feature.settings.AppLanguage
import com.j2team.fileserver.feature.settings.FolderIconSet
import com.j2team.fileserver.feature.settings.SettingsScreen
import com.j2team.fileserver.feature.settings.SettingsStore
import com.j2team.fileserver.feature.transfers.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            super.attachBaseContext(newBase)
            return
        }
        val language = SettingsStore(newBase).read().language
        val config = Configuration(newBase.resources.configuration).apply { setLocale(Locale.forLanguageTag(language.languageTag)) }
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) applyLanguage(SettingsStore(this).read().language)
        setContent {
            FileServerApp(
                serverStore = ServerStore(this),
                settingsStore = SettingsStore(this),
                transferStore = (application as FileServerApp).transferStore,
                sessionRepository = (application as FileServerApp).sessionRepository,
                onLanguageChanged = ::applyLanguage,
            )
        }
    }

    override fun onDestroy() {
        if (isFinishing && !isChangingConfigurations) {
            (application as FileServerApp).sessionRepository.clearProcessSession()
        }
        super.onDestroy()
    }

    private fun applyLanguage(language: AppLanguage) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(language.languageTag)
        } else recreate()
    }
}

private enum class Screen { Servers, AddServer, Login, Browser, Transfers, Settings }

@Composable
private fun FileServerApp(
    serverStore: ServerStore,
    settingsStore: SettingsStore,
    transferStore: TransferStore,
    sessionRepository: SessionRepository,
    onLanguageChanged: (AppLanguage) -> Unit,
) {
    var settings by remember { mutableStateOf(settingsStore.read()) }
    val dark = when (settings.theme) {
        AppTheme.System -> androidx.compose.foundation.isSystemInDarkTheme()
        AppTheme.Light -> false
        AppTheme.Dark -> true
    }
    FileServerTheme(darkTheme = dark) {
        var screenName by rememberSaveable { mutableStateOf(Screen.Servers.name) }
        val screen = Screen.valueOf(screenName)
        var profiles by remember { mutableStateOf(serverStore.all()) }
        var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
        val selected = profiles.firstOrNull { it.id == selectedId }
        var connectionError by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val transferCoordinator = remember(selected?.id) {
            selected?.let { TransferCoordinator(context.applicationContext, transferStore, sessionRepository, it) }
        }

        Surface(
            Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                when (screen) {
                Screen.Servers -> ServersScreen(
                    profiles = profiles,
                    sessionRepository = sessionRepository,
                    onAdd = { screenName = Screen.AddServer.name },
                    error = connectionError,
                    onOpen = { profile ->
                        connectionError = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { sessionRepository.open(profile) }
                            result.onSuccess {
                                selectedId = it.profile.id
                                screenName = Screen.Browser.name
                            }.onFailure {
                                if (it is LoginRequiredException) {
                                    selectedId = profile.id
                                    screenName = Screen.Login.name
                                } else {
                                    connectionError = it.message
                                }
                            }
                        }
                    },
                    onDelete = {
                        sessionRepository.clear(it.id)
                        serverStore.delete(it.id)
                        profiles = serverStore.all()
                    },
                    onSettings = { screenName = Screen.Settings.name },
                )
                Screen.AddServer -> AddServerScreen(
                    onBack = { screenName = Screen.Servers.name },
                    onSave = { raw, name ->
                        Endpoint.normalize(raw).onSuccess {
                            serverStore.create(name.ifBlank { "${it.host}:${it.port}" }, it.scheme, it.host, it.port, it.basePath)
                            profiles = serverStore.all()
                            screenName = Screen.Servers.name
                        }
                    },
                )
                Screen.Login -> LoginScreen(
                    profile = selected,
                    sessionRepository = sessionRepository,
                    onBack = { screenName = Screen.Servers.name },
                    onConnected = { screenName = Screen.Browser.name },
                )
                Screen.Browser -> BrowserScreen(
                    profile = selected,
                    settings = settings,
                    transferStore = transferStore,
                    transferCoordinator = transferCoordinator,
                    sessionRepository = sessionRepository,
                    onBack = { screenName = Screen.Servers.name },
                    onTransfers = { screenName = Screen.Transfers.name },
                )
                Screen.Transfers -> TransfersScreen(transferStore, transferCoordinator) { screenName = Screen.Browser.name }
                Screen.Settings -> SettingsScreen(
                    settings = settings,
                    onBack = { screenName = Screen.Servers.name },
                    onTransfers = { screenName = Screen.Transfers.name },
                    onChanged = { updated ->
                        val languageChanged = settings.language != updated.language
                        settings = updated
                        settingsStore.save(updated)
                        if (languageChanged) onLanguageChanged(updated.language)
                    },
                )
                }
            }
        }
    }
}

@Composable
internal fun AppBar(
    title: String,
    onBack: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                TextButton(onClick = onBack, modifier = Modifier.size(48.dp), contentPadding = PaddingValues(0.dp)) {
                    Text("‹", style = MaterialTheme.typography.headlineMedium)
                }
            }
            leading?.invoke()
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            action?.invoke()
        }
    }
}

@Composable
private fun ServersScreen(
    profiles: List<ServerProfile>,
    sessionRepository: SessionRepository,
    error: String?,
    onAdd: () -> Unit,
    onOpen: (ServerProfile) -> Unit,
    onDelete: (ServerProfile) -> Unit,
    onSettings: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        AppBar(stringResource(R.string.app_name), leading = {
            Image(painterResource(R.drawable.server_icon), contentDescription = null, modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(10.dp))
        }, action = {
            IconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(AppIcons.Settings), contentDescription = stringResource(R.string.settings))
            }
        })
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Text(
                stringResource(R.string.your_servers),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (profiles.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Image(
                            painterResource(R.drawable.server_icon),
                            contentDescription = null,
                            modifier = Modifier.size(72.dp),
                            alpha = 0.72f,
                        )
                        Text(
                            stringResource(R.string.server_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp),
                        )
                    }
                }
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(profiles, key = { it.id }) { profile ->
                        ServerCard(profile, sessionRepository, { onOpen(profile) }, { onDelete(profile) })
                    }
                }
            }
            Button(
                onClick = onAdd,
                modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp).height(56.dp),
                shape = RoundedCornerShape(18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("+", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.add_server), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun ServerCard(profile: ServerProfile, sessionRepository: SessionRepository, onClick: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var online by remember(profile.endpoint) { mutableStateOf(false) }
    LaunchedEffect(profile.endpoint) {
        online = withContext(Dispatchers.IO) { sessionRepository.isReachable(profile) }
    }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(112.dp),
        shape = RoundedCornerShape(16.dp),
        border = CardDefaults.outlinedCardBorder(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.fillMaxSize().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.server_icon), contentDescription = null, modifier = Modifier.size(48.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(profile.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(profile.endpoint.removeSuffix("/"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Surface(shape = RoundedCornerShape(50), color = if (online) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error) {
                Text(stringResource(if (online) R.string.online else R.string.offline), color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
            Box {
                TextButton(onClick = { menu = true }, modifier = Modifier.size(52.dp), contentPadding = PaddingValues(0.dp)) {
                    Text("⋮", style = MaterialTheme.typography.headlineMedium)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
private fun AddServerScreen(onBack: () -> Unit, onSave: (String, String) -> Unit) {
    var endpoint by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        AppBar(stringResource(R.string.add_server), onBack)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                endpoint, { endpoint = it; error = null },
                label = { Text(stringResource(R.string.server_address)) },
                placeholder = { Text("http://192.168.1.10:8080") },
                modifier = Modifier.fillMaxWidth().height(64.dp), singleLine = true, shape = RoundedCornerShape(12.dp),
            )
            OutlinedTextField(
                name, { name = it },
                label = { Text(stringResource(R.string.display_name)) },
                modifier = Modifier.fillMaxWidth().height(64.dp), singleLine = true, shape = RoundedCornerShape(12.dp),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    Endpoint.normalize(endpoint).fold(
                        onSuccess = { onSave(endpoint, name) },
                        onFailure = { error = it.message ?: "URL không hợp lệ" },
                    )
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(18.dp),
            ) { Text(stringResource(R.string.save_server)) }
        }
    }
}

@Composable
internal fun LoginScreen(
    profile: ServerProfile?,
    sessionRepository: SessionRepository,
    onBack: () -> Unit,
    onConnected: () -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        AppBar(stringResource(R.string.sign_in), onBack)
        Column(
            Modifier.weight(1f).fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(20.dp))
            Image(painterResource(R.drawable.server_icon), contentDescription = null, modifier = Modifier.size(88.dp))
            Text(profile?.displayName ?: stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
            Text(profile?.endpoint.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(username, { username = it }, label = { Text(stringResource(R.string.username)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp), singleLine = true, shape = RoundedCornerShape(12.dp))
            OutlinedTextField(
                password, { password = it }, label = { Text(stringResource(R.string.password)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp), singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(), shape = RoundedCornerShape(12.dp),
                trailingIcon = { IconButton(onClick = { passwordVisible = !passwordVisible }) { Icon(painterResource(if (passwordVisible) AppIcons.VisibilityOff else AppIcons.Visibility), contentDescription = stringResource(if (passwordVisible) R.string.hide_password else R.string.show_password)) } },
            )
            if (profile?.scheme == "http") Text(stringResource(R.string.http_warning), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    val current = profile ?: return@Button
                    busy = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            sessionRepository.login(current, username, password.toCharArray())
                        }
                        busy = false
                        result.onSuccess { onConnected() }.onFailure { error = it.message }
                    }
                },
                enabled = !busy && username.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(18.dp),
            ) { Text(if (busy) stringResource(R.string.connecting) else stringResource(R.string.sign_in)) }
        }
    }
}

@Composable
private fun LegacyBrowserScreen(
    profile: ServerProfile?,
    settings: AppSettings,
    transferStore: TransferStore,
    sessionRepository: SessionRepository,
    onBack: () -> Unit,
    onTransfers: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf(profile?.basePath ?: "/") }
    var resources by remember { mutableStateOf<List<RemoteResource>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    val downloadDirectoryUnavailable = stringResource(R.string.download_directory_unavailable)
    var loading by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<RemoteResource?>(null) }

    fun refresh() {
        val current = profile ?: return
        loading = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { sessionRepository.list(current, path) }
            loading = false
            result.onSuccess { list ->
                resources = list.filter { settings.showHiddenFiles || !it.name.startsWith(".") }
                    .sortedWith(compareByDescending<RemoteResource> { it.isDirectory }.thenBy { it.name.lowercase() })
                error = null
            }.onFailure { error = it.message }
        }
    }
    LaunchedEffect(profile, path, settings.showHiddenFiles) { refresh() }

    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && profile != null) {
            scope.launch {
                val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
                } ?: "upload.bin"
                val temp = File(context.cacheDir, name)
                withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use(input::copyTo) } }
                var task = transferStore.enqueue(name, path, TransferDirection.Upload, temp.length())
                task = transferStore.save(task.copy(state = TransferState.Running))
                withContext(Dispatchers.IO) {
                    sessionRepository.uploadOnce(profile, path, temp) { sent, _ -> transferStore.update(task.id, sent, TransferState.Running) }
                }.onSuccess { transferStore.update(task.id, task.totalBytes, TransferState.Completed); refresh() }
                    .onFailure { transferStore.update(task.id, task.transferredBytes, TransferState.Failed, it.message) }
                temp.delete()
            }
        }
    }

    if (preview != null && profile != null) {
        PreviewScreen(profile, preview!!, transferStore, sessionRepository, onBack = { preview = null })
        return
    }

    Column(Modifier.fillMaxSize()) {
        AppBar(profile?.displayName ?: stringResource(R.string.app_name), onBack, action = {
            IconButton(onClick = onTransfers, modifier = Modifier.size(48.dp)) { Icon(painterResource(AppIcons.Transfers), stringResource(R.string.transfers)) }
        })
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (BrowserPath.normalize(path) != "/") TextButton(onClick = { path = BrowserPath.parent(path) }) { Text("‹") }
            Text(path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = { uploadPicker.launch(arrayOf("*/*")) }, modifier = Modifier.size(48.dp)) { Icon(painterResource(AppIcons.Upload), stringResource(R.string.upload)) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.list), fontWeight = FontWeight.Medium)
            Text(stringResource(R.string.items_count, resources.size), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(stringResource(R.string.connection_failed, it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        downloadError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(resources, key = { it.path }) { item ->
                FileRow(
                    item = item,
                    settings = settings,
                    onOpen = { if (item.isDirectory) path = item.path else preview = item },
                    onDownload = {
                        if (profile != null) {
                            scope.launch {
                                val task = transferStore.enqueue(item.name, item.path, TransferDirection.Download, item.size)
                                val treeUri = settings.downloadTreeUri
                                val destination = if (treeUri == null) {
                                    File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), item.name)
                                } else {
                                    File(context.cacheDir, "download-${task.id}")
                                }
                                downloadError = null
                                transferStore.update(task.id, 0, TransferState.Running)
                                val downloaded = withContext(Dispatchers.IO) {
                                    sessionRepository.download(profile, item.path, destination) { read, _ -> transferStore.update(task.id, read, TransferState.Running) }
                                }
                                downloaded.onSuccess { file ->
                                    val saved = treeUri?.let { selectedTree ->
                                        withContext(Dispatchers.IO) { runCatching { copyToDownloadTree(context.contentResolver, selectedTree, file, item.name) } }
                                    } ?: Result.success(Unit)
                                    saved.onSuccess {
                                        transferStore.update(task.id, item.size, TransferState.Completed)
                                    }.onFailure {
                                        transferStore.update(task.id, 0, TransferState.Failed, it.message)
                                        downloadError = downloadDirectoryUnavailable
                                    }
                                    if (treeUri != null) file.delete()
                                }.onFailure { transferStore.update(task.id, 0, TransferState.Failed, it.message) }
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun FileRow(item: RemoteResource, settings: AppSettings, onOpen: () -> Unit, onDownload: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().height(72.dp).clickable(onClick = onOpen),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(folderIconResource(settings.folderIconSet)), contentDescription = null, modifier = Modifier.size(40.dp), tint = Color.Unspecified)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (item.isDirectory) stringResource(R.string.folder) else formatBytes(item.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (item.isDirectory) Text("›", style = MaterialTheme.typography.titleLarge)
            else IconButton(onClick = onDownload, modifier = Modifier.size(48.dp)) { Icon(painterResource(AppIcons.Download), stringResource(R.string.download)) }
        }
    }
}

@Composable
private fun LegacyTransfersScreen(store: TransferStore, onBack: () -> Unit) {
    var tasks by remember { mutableStateOf(store.all()) }
    Column(Modifier.fillMaxSize()) {
        AppBar(stringResource(R.string.transfers), onBack)
        if (tasks.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.no_transfers), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(tasks, key = { it.id }) { task ->
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row { Text(if (task.direction == TransferDirection.Download) "↓" else "↑"); Spacer(Modifier.width(12.dp)); Text(task.name, modifier = Modifier.weight(1f)); Text(task.state.name) }
                        LinearProgressIndicator(progress = { task.progress }, modifier = Modifier.fillMaxWidth())
                        Text("${formatBytes(task.transferredBytes)} / ${formatBytes(task.totalBytes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!task.isActive) TextButton(onClick = { store.remove(task.id); tasks = store.all() }) { Text(stringResource(R.string.delete)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LegacySettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onTransfers: () -> Unit,
    onChanged: (AppSettings) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        AppBar(stringResource(R.string.settings), onBack)
        Card(
            Modifier.fillMaxWidth().padding(16.dp).height(96.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Row(Modifier.fillMaxSize().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.server_icon), null, Modifier.size(48.dp))
                Spacer(Modifier.width(12.dp))
                Column { Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium); Text("Android native • v1.0", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        Text(stringResource(R.string.theme), fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppTheme.entries.forEach { theme ->
                FilterChip(
                    selected = settings.theme == theme,
                    onClick = { onChanged(settings.copy(theme = theme)) },
                    label = { Text(when (theme) { AppTheme.System -> stringResource(R.string.theme_system); AppTheme.Light -> stringResource(R.string.theme_light); AppTheme.Dark -> stringResource(R.string.theme_dark) }) },
                )
            }
        }
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.show_hidden), modifier = Modifier.weight(1f))
            Switch(settings.showHiddenFiles, { onChanged(settings.copy(showHiddenFiles = it)) })
        }
        Button(onClick = onTransfers, modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp), shape = RoundedCornerShape(18.dp)) {
            Text(stringResource(R.string.open_transfers))
        }
    }
}

internal fun formatBytes(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_048_576 -> "%.1f KB".format(bytes / 1_024.0)
    bytes < 1_073_741_824 -> "%.1f MB".format(bytes / 1_048_576.0)
    else -> "%.1f GB".format(bytes / 1_073_741_824.0)
}

internal fun folderIconResource(set: FolderIconSet): Int = when (set) {
    FolderIconSet.Classic -> AppIcons.FolderClassic
    FolderIconSet.Color -> AppIcons.FolderColor
    FolderIconSet.Outline -> AppIcons.FolderOutline
}

internal fun copyToDownloadTree(resolver: ContentResolver, treeUri: String, source: File, name: String) {
    val tree = Uri.parse(treeUri)
    val treeDocument = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    var destination: Uri? = null
    try {
        destination = DocumentsContract.createDocument(resolver, treeDocument, "application/octet-stream", name)
            ?: throw IOException("Provider did not create destination")
        resolver.openOutputStream(destination, "w")?.use { output ->
            source.inputStream().use { input -> input.copyTo(output) }
        } ?: throw IOException("Provider did not open destination")
    } catch (error: Throwable) {
        destination?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
        throw error
    }
}
