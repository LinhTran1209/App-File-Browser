Exit code: 0
Wall time: 0.6 seconds
Output:
package com.j2team.fileserver

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.j2team.fileserver.core.network.Endpoint
import com.j2team.fileserver.core.model.ServerProfile
import com.j2team.fileserver.feature.servers.ServerStore
import com.j2team.fileserver.core.model.RemoteResource
import com.j2team.fileserver.core.network.FileBrowserClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import com.j2team.fileserver.core.ui.FileServerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FileServerAppShell(ServerStore(this))
        }
    }
}

@Composable
private fun FileServerAppShell(store: ServerStore) {
    var profiles by remember { mutableStateOf(store.all()) }
    var screen by remember { mutableStateOf(AppScreen.Servers) }
    var selected by remember { mutableStateOf<ServerProfile?>(null) }
    var token by remember { mutableStateOf<String?>(null) }
    FileServerTheme {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                Surface(tonalElevation = 1.dp) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(text = stringResource(R.string.app_name), style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                        if (screen != AppScreen.Servers) TextButton(onClick = { screen = AppScreen.Servers }) { Text("Servers") }
                    }
                }
            },
            bottomBar = {
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { screen = AppScreen.Servers }) { Text("Servers") }
                    TextButton(onClick = { screen = AppScreen.Transfers }) { Text("Transfers") }
                    TextButton(onClick = { screen = AppScreen.Settings }) { Text("Settings") }
                }
            },
        ) { contentPadding ->
            Surface(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
                when (screen) {
                    AppScreen.Servers -> ServersScreen(profiles, onAdd = { screen = AppScreen.Add }, onOpen = { selected = it; screen = AppScreen.Login }, onDelete = { store.delete(it.id); profiles = store.all() })
                    AppScreen.Add -> AddServerScreen(onCancel = { screen = AppScreen.Servers }, onSave = { raw, name ->
                        Endpoint.normalize(raw).onSuccess { endpoint -> store.create(name, endpoint.scheme, endpoint.host, endpoint.port, endpoint.basePath); profiles = store.all(); screen = AppScreen.Servers }
                    })
                    AppScreen.Login -> LoginScreen(selected, onConnected = { token = it; screen = AppScreen.Browser })
                    AppScreen.Browser -> BrowserScreen(selected, token, onBack = { screen = AppScreen.Servers })
                    AppScreen.Transfers -> EmptyFeatureScreen("Transfers", "Uploads and downloads will appear here")
                    AppScreen.Settings -> EmptyFeatureScreen("Settings", "Theme, security and connection preferences")
                }
            }
        }
    }
}

private enum class AppScreen { Servers, Add, Login, Browser, Transfers, Settings }

@Composable
private fun ServersScreen(profiles: List<ServerProfile>, onAdd: () -> Unit, onOpen: (ServerProfile) -> Unit, onDelete: (ServerProfile) -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Your servers", style = androidx.compose.material3.MaterialTheme.typography.headlineMedium)
        if (profiles.isEmpty()) Text("Add a File Browser server to get started")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
            items(profiles, key = { it.id }) { profile ->
                Card(onClick = { onOpen(profile) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column { Text(profile.displayName, style = androidx.compose.material3.MaterialTheme.typography.titleMedium); Text(profile.endpoint) }
                        TextButton(onClick = { onDelete(profile) }) { Text("Delete") }
                    }
                }
            }
        }
        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("Add server") }
    }
}

@Composable
private fun AddServerScreen(onCancel: () -> Unit, onSave: (String, String) -> Unit) {
    var endpoint by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Add server", style = androidx.compose.material3.MaterialTheme.typography.headlineMedium)
        OutlinedTextField(endpoint, { endpoint = it; error = null }, label = { Text("Server URL") }, placeholder = { Text("http://192.168.1.10:8080") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(name, { name = it }, label = { Text("Display name (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        error?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(onClick = { Endpoint.normalize(endpoint).fold({ onSave(endpoint, name) }, { error = it.message ?: "Invalid URL" }) }) { Text("Save") }
        }
    }
}

@Composable
private fun LoginScreen(profile: ServerProfile?, onConnected: (String?) -> Unit) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Connect to ${profile?.displayName ?: "Server"}", style = androidx.compose.material3.MaterialTheme.typography.headlineMedium)
        if (profile?.scheme == "http") Text("HTTP is unencrypted. Continue only on your trusted home network.", color = androidx.compose.material3.MaterialTheme.colorScheme.error)
        Text("Credentials are used only for this connection and are never logged.")
        OutlinedTextField(username, { username = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(password, { password = it }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        error?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
        Button(onClick = {
            if (profile == null) return@Button
            busy = true; error = null
            scope.launch {
                val result = withContext(Dispatchers.IO) { FileBrowserClient().login(profile, username, password) }
                busy = false
                result.onSuccess { onConnected(it) }.onFailure { error = it.message ?: "Sign in failed" }
            }
        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Connectingâ€¦" else "Sign in") }
    }
}

@Composable
private fun BrowserScreen(profile: ServerProfile?, token: String?, onBack: () -> Unit) {
    var resources by remember { mutableStateOf<List<RemoteResource>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(profile) {
        if (profile != null) {
            val result = withContext(Dispatchers.IO) { FileBrowserClient().list(profile, token) }
            result.onSuccess { resources = it }.onFailure { error = it.message }
        }
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(profile?.displayName ?: "Files", style = androidx.compose.material3.MaterialTheme.typography.headlineMedium)
        Text(profile?.basePath ?: "/")
        Divider()
        error?.let { Text("Could not connect: $it", color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
        if (resources.isEmpty() && error == null) Text("Loading filesâ€¦")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(resources, key = { it.path }) { item ->
                Card(Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(if (item.isDirectory) "â–£ ${item.name}" else item.name); Text(if (item.isDirectory) "Folder" else "${item.size} B") } }
            }
        }
        TextButton(onClick = onBack) { Text("Back to servers") }
    }
}

@Composable
private fun EmptyFeatureScreen(title: String, message: String) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(title, style = androidx.compose.material3.MaterialTheme.typography.headlineMedium); Text(message) }
}

