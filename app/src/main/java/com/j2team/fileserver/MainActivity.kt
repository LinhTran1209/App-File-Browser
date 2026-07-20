package com.j2team.fileserver

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.j2team.fileserver.core.model.*
import com.j2team.fileserver.core.network.*
import com.j2team.fileserver.core.ui.FileServerTheme
import com.j2team.fileserver.feature.servers.ServerStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() { override fun onCreate(state: Bundle?) { super.onCreate(state); setContent { Shell(ServerStore(this)) } } }
private enum class Screen { Servers, Add, Login, Browser, Transfers, Settings }

@Composable private fun Shell(store: ServerStore) {
    var profiles by remember { mutableStateOf(store.all()) }
    var screen by remember { mutableStateOf(Screen.Servers) }
    var selected by remember { mutableStateOf<ServerProfile?>(null) }
    var token by remember { mutableStateOf<String?>(null) }
    FileServerTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { when (screen) {
        Screen.Servers -> Servers(profiles, { screen = Screen.Add }, { selected = it; screen = Screen.Login }, { store.delete(it.id); profiles = store.all() }, { screen = Screen.Settings })
        Screen.Add -> Add({ screen = Screen.Servers }) { raw, name -> Endpoint.normalize(raw).fold({ e -> store.create(name.ifBlank { "File Server" }, e.scheme, e.host, e.port, e.basePath); profiles = store.all(); screen = Screen.Servers }, { }) }
        Screen.Login -> Login(selected, { screen = Screen.Servers }) { token = it; screen = Screen.Browser }
        Screen.Browser -> Browser(selected, token) { screen = Screen.Servers }
        Screen.Transfers -> TransfersScreen { screen = Screen.Servers }
        Screen.Settings -> SettingsScreen({ screen = Screen.Servers }, { screen = Screen.Transfers })
    } } }
}

@Composable private fun Bar(title: String, back: (() -> Unit)? = null, action: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (back != null) TextButton(onClick = back, contentPadding = PaddingValues(0.dp)) { Text("<", style = MaterialTheme.typography.headlineMedium) }
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleLarge); if (back == null) Text("Your servers", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (action != null) TextButton(onClick = action) { Text("Settings") }
    }
}

@Composable private fun Servers(list: List<ServerProfile>, add: () -> Unit, open: (ServerProfile) -> Unit, delete: (ServerProfile) -> Unit, settings: () -> Unit) {
    Column(Modifier.fillMaxSize()) { Bar("File Server", action = settings)
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            if (list.isEmpty()) item { Text("No servers yet. Add one to get started.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(list, key = { it.id }) { p -> Card(onClick = { open(p) }, modifier = Modifier.fillMaxWidth().height(112.dp), shape = RoundedCornerShape(16.dp), border = ButtonDefaults.outlinedButtonBorder) { Row(Modifier.fillMaxSize().padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Text("F", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge) }; Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(p.displayName, style = MaterialTheme.typography.titleMedium); Text(p.endpoint, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(6.dp)); Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondary) { Text("Online", color = Color.White, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) } }; TextButton(onClick = { delete(p) }) { Text("Delete") } } } }
        }
        Button(onClick = add, modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp), shape = RoundedCornerShape(18.dp)) { Text("+  Add server") }
    }
}

@Composable private fun Add(back: () -> Unit, save: (String, String) -> Unit) { var url by remember { mutableStateOf("") }; var name by remember { mutableStateOf("") }; var error by remember { mutableStateOf<String?>(null) }; Column(Modifier.fillMaxSize()) { Bar("Add server", back); Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { OutlinedTextField(url, { value -> url = value; error = null }, label = { Text("Server address") }, placeholder = { Text("http://192.168.1.10:8080") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(12.dp)); OutlinedTextField(name, { value -> name = value }, label = { Text("Display name") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(12.dp)); error?.let { Text(it, color = MaterialTheme.colorScheme.error) }; Button(onClick = { Endpoint.normalize(url).fold({ save(url, name) }, { cause -> error = cause.message ?: "Invalid URL" }) }, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp)) { Text("Save server") } } } }

@Composable private fun Login(profile: ServerProfile?, back: () -> Unit, connected: (String?) -> Unit) { var user by remember { mutableStateOf("") }; var pass by remember { mutableStateOf("") }; var error by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }; val scope = rememberCoroutineScope(); Column(Modifier.fillMaxSize()) { Bar("Sign in", back); Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) { Spacer(Modifier.height(16.dp)); Box(Modifier.size(88.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) { Text("F", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary) }; Text(profile?.displayName ?: "Server", style = MaterialTheme.typography.headlineMedium); Text(profile?.endpoint ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(8.dp)); OutlinedTextField(user, { value -> user = value }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(12.dp)); OutlinedTextField(pass, { value -> pass = value }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation(), shape = RoundedCornerShape(12.dp)); error?.let { Text(it, color = MaterialTheme.colorScheme.error) }; Button(onClick = { if (profile != null) { busy = true; scope.launch { val result = withContext(Dispatchers.IO) { FileBrowserClient().login(profile, user, pass) }; busy = false; result.onSuccess { connected(it) }.onFailure { cause -> error = cause.message } } } }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp)) { Text(if (busy) "Connecting..." else "Sign in") } } } }

@Composable private fun Browser(profile: ServerProfile?, token: String?, back: () -> Unit) {
    var path by remember { mutableStateOf("/") }
    var data by remember { mutableStateOf<List<RemoteResource>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<RemoteResource?>(null) }
    LaunchedEffect(profile, token, path) { if (profile != null) FileBrowserClient().list(profile, token, path).onSuccess { data = it; error = null }.onFailure { cause -> error = cause.message } }
    if (preview != null) {
        Column(Modifier.fillMaxSize().background(Color(0xFF030712))) { Bar(preview!!.name, { preview = null }); Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Preview is ready for streaming", color = Color.White) } }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Bar(profile?.displayName ?: "Files", back)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) { if (path != "/") TextButton(onClick = { path = path.substringBeforeLast('/', "/").ifBlank { "/" } }) { Text("Up") }; Text(path, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text("List", fontWeight = FontWeight.Medium); Text("Name up   ${data.size} items", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        error?.let { cause -> Text("Connection failed: $cause", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(data, key = { it.path }) { item ->
                Card(onClick = { if (item.isDirectory) path = item.path else preview = item }, modifier = Modifier.fillMaxWidth().height(72.dp), shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.fillMaxSize().padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Text(if (item.isDirectory) "[ ]" else "file", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(item.name, style = MaterialTheme.typography.titleMedium); Text(if (item.isDirectory) "Folder" else "${item.size} B", color = MaterialTheme.colorScheme.onSurfaceVariant) }; Text(if (item.isDirectory) ">" else "⋮") }
                }
            }
        }
    }
}

@Composable private fun TransfersScreen(back: () -> Unit) { Column(Modifier.fillMaxSize()) { Bar("Transfers", back); Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No active transfers", color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
@Composable private fun SettingsScreen(back: () -> Unit, transfers: () -> Unit) { Column(Modifier.fillMaxSize()) { Bar("Settings", back); Card(Modifier.padding(16.dp).fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) { Column(Modifier.padding(16.dp)) { Text("File Server", style = MaterialTheme.typography.titleMedium); Text("Theme and connection preferences") } }; Button(onClick = transfers, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text("Open transfers") } } }
