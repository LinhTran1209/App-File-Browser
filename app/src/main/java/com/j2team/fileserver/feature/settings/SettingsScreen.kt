package com.j2team.fileserver.feature.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.j2team.fileserver.R
import com.j2team.fileserver.core.ui.AppIcons

@Composable
fun SettingsScreen(settings: AppSettings, onBack: () -> Unit, onTransfers: () -> Unit, onChanged: (AppSettings) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val directoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            onChanged(settings.copy(downloadTreeUri = uri.toString()))
        }
    }
    Column(Modifier.fillMaxSize()) {
        SettingsAppBar(onBack)
        Card(Modifier.fillMaxWidth().padding(16.dp).height(96.dp), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Row(Modifier.fillMaxSize().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(AppIcons.Settings), null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column { Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium); Text("Android native • v1.0", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        SettingsLabel(R.string.language)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppLanguage.entries.forEach { language -> FilterChip(settings.language == language, { onChanged(settings.copy(language = language)) }, { Text(stringResource(if (language == AppLanguage.Vietnamese) R.string.language_vietnamese else R.string.language_english)) }) }
        }
        SettingsLabel(R.string.download_directory)
        Button(onClick = { directoryPicker.launch(null) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(48.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
            Icon(painterResource(AppIcons.Download), null); Spacer(Modifier.width(8.dp)); Text(settings.downloadTreeUri ?: stringResource(R.string.choose_download_directory), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        SettingsLabel(R.string.folder_icons)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FolderIconSet.entries.forEach { iconSet -> FilterChip(settings.folderIconSet == iconSet, { onChanged(settings.copy(folderIconSet = iconSet)) }, { Text(stringResource(folderIconLabel(iconSet))) }, leadingIcon = { Icon(painterResource(folderIcon(iconSet)), null, Modifier.size(24.dp)) }) }
        }
        SettingsLabel(R.string.theme)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppTheme.entries.forEach { theme -> FilterChip(settings.theme == theme, { onChanged(settings.copy(theme = theme)) }, { Text(when (theme) { AppTheme.System -> stringResource(R.string.theme_system); AppTheme.Light -> stringResource(R.string.theme_light); AppTheme.Dark -> stringResource(R.string.theme_dark) }) }) }
        }
        SettingsLabel(R.string.view_mode)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!settings.gridView, { onChanged(settings.copy(gridView = false)) }, { Text(stringResource(R.string.list)) }, leadingIcon = { Icon(painterResource(AppIcons.List), null) })
            FilterChip(settings.gridView, { onChanged(settings.copy(gridView = true)) }, { Text(stringResource(R.string.grid)) }, leadingIcon = { Icon(painterResource(AppIcons.Grid), null) })
        }
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) { Text(stringResource(R.string.show_hidden), modifier = Modifier.weight(1f)); Switch(settings.showHiddenFiles, { onChanged(settings.copy(showHiddenFiles = it)) }) }
        Button(onClick = onTransfers, modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp), shape = MaterialTheme.shapes.large) { Icon(painterResource(AppIcons.Transfers), null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.open_transfers)) }
    }
}

@Composable private fun SettingsAppBar(onBack: () -> Unit) = Surface(color = MaterialTheme.colorScheme.surface) {
    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onBack, Modifier.size(48.dp)) { Text("‹", style = MaterialTheme.typography.headlineMedium) }; Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f)) }
}

@Composable private fun SettingsLabel(resource: Int) = Text(stringResource(resource), fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
private fun folderIcon(set: FolderIconSet): Int = when (set) { FolderIconSet.Classic -> AppIcons.FolderClassic; FolderIconSet.Color -> AppIcons.FolderColor; FolderIconSet.Outline -> AppIcons.FolderOutline }
private fun folderIconLabel(set: FolderIconSet): Int = when (set) { FolderIconSet.Classic -> R.string.folder_icon_classic; FolderIconSet.Color -> R.string.folder_icon_color; FolderIconSet.Outline -> R.string.folder_icon_outline }
