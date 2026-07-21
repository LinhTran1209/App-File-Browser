package com.j2team.fileserver.feature.settings

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.j2team.fileserver.BuildConfig
import com.j2team.fileserver.R
import com.j2team.fileserver.core.ui.AppIcons

@Composable
internal fun SettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onTransfers: () -> Unit,
    onChanged: (AppSettings) -> Unit,
    directoryPicker: @Composable ((TreeSelection?) -> Unit) -> () -> Unit = { onSelection ->
        val launcher = rememberLauncherForActivityResult(PersistableTreeContract(), onSelection)
        val launch: () -> Unit = { launcher.launch(Unit) }
        launch
    },
    persistTreeGrant: ((TreeSelection) -> Boolean)? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var directoryError by remember { mutableStateOf<String?>(null) }
    val directoryPermissionError = stringResource(R.string.download_directory_permission_error)
    val onDirectorySelection: (TreeSelection?) -> Unit = { selection ->
        if (selection != null) {
            val persisted = acceptsDownloadTreeGrant(selection.grantFlags) && (
                persistTreeGrant?.invoke(selection) ?: runCatching {
                    context.contentResolver.takePersistableUriPermission(selection.uri, selection.grantFlags)
                }.isSuccess
            )
            if (persisted) {
                directoryError = null
                onChanged(settings.copy(downloadTreeUri = selection.uri.toString()))
            } else {
                directoryError = directoryPermissionError
            }
        }
    }
    val launchDirectoryPicker = directoryPicker(onDirectorySelection)
    Column(Modifier.fillMaxSize()) {
        SettingsAppBar(onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Card(Modifier.fillMaxWidth().padding(16.dp).height(96.dp), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Row(Modifier.fillMaxSize().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(AppIcons.Settings), null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column { Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium); Text(stringResource(R.string.app_version, BuildConfig.VERSION_NAME), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        SettingsLabel(R.string.language)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppLanguage.entries.forEach { language -> FilterChip(settings.language == language, { onChanged(settings.copy(language = language)) }, { Text(stringResource(if (language == AppLanguage.Vietnamese) R.string.language_vietnamese else R.string.language_english)) }) }
        }
        SettingsLabel(R.string.download_directory)
        Button(onClick = launchDirectoryPicker, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(48.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
            Icon(painterResource(AppIcons.Download), null); Spacer(Modifier.width(8.dp)); Text(settings.downloadTreeUri ?: stringResource(R.string.choose_download_directory), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        directoryError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
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
}

@Composable private fun SettingsAppBar(onBack: () -> Unit) = Surface(color = MaterialTheme.colorScheme.surface) {
    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onBack, Modifier.size(48.dp)) { Text("‹", style = MaterialTheme.typography.headlineMedium) }; Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f)) }
}

@Composable private fun SettingsLabel(resource: Int) = Text(stringResource(resource), fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
private fun folderIcon(set: FolderIconSet): Int = when (set) { FolderIconSet.Classic -> AppIcons.FolderClassic; FolderIconSet.Color -> AppIcons.FolderColor; FolderIconSet.Outline -> AppIcons.FolderOutline }
private fun folderIconLabel(set: FolderIconSet): Int = when (set) { FolderIconSet.Classic -> R.string.folder_icon_classic; FolderIconSet.Color -> R.string.folder_icon_color; FolderIconSet.Outline -> R.string.folder_icon_outline }

internal data class TreeSelection(val uri: android.net.Uri, val grantFlags: Int)

private class PersistableTreeContract : ActivityResultContract<Unit, TreeSelection?>() {
    override fun createIntent(context: android.content.Context, input: Unit): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): TreeSelection? {
        val uri = intent?.data ?: return null
        if (resultCode != Activity.RESULT_OK) return null
        val flags = intent.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        return TreeSelection(uri, flags)
    }
}
