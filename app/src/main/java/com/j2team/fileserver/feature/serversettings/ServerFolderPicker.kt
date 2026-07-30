package com.j2team.fileserver.feature.serversettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.j2team.fileserver.R
import com.j2team.fileserver.core.model.AdminDirectoryListing
import com.j2team.fileserver.core.model.ServerProfile
import com.j2team.fileserver.core.session.SessionRepository
import com.j2team.fileserver.core.ui.AppIcons
import com.j2team.fileserver.core.ui.FolderNavigationRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ServerFolderPicker(
    profile: ServerProfile,
    repository: SessionRepository,
    selectedPath: String,
    enabled: Boolean,
    onSelected: (String) -> Unit,
    onSelectedListing: (AdminDirectoryListing?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var pickerOpen by remember { mutableStateOf(false) }
    var listing by remember { mutableStateOf<AdminDirectoryListing?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var newFolderName by remember { mutableStateOf("") }
    var selectedPathAvailable by remember(selectedPath) { mutableStateOf<Boolean?>(null) }

    suspend fun load(path: String): Result<AdminDirectoryListing> =
        withContext(Dispatchers.IO) { repository.adminDirectories(profile, path) }
            .onSuccess {
                listing = it
                error = null
            }
            .onFailure { error = it.message ?: it.toString() }

    LaunchedEffect(selectedPath) {
        withContext(Dispatchers.IO) { repository.adminDirectories(profile, selectedPath.ifBlank { "/" }) }
            .onSuccess {
                selectedPathAvailable = true
                onSelectedListing(it)
            }
            .onFailure {
                selectedPathAvailable = false
                onSelectedListing(null)
            }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = selectedPath,
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                label = { Text(stringResource(R.string.user_folder)) },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            TextButton(
                enabled = enabled,
                onClick = {
                    pickerOpen = true
                    loading = true
                    scope.launch {
                        var candidate = if (selectedPathAvailable == false) {
                            serverParentPath(selectedPath)
                        } else {
                            selectedPath.ifBlank { "/" }
                        }
                        while (true) {
                            if (load(candidate).isSuccess || candidate == "/") break
                            candidate = serverParentPath(candidate)
                        }
                        loading = false
                    }
                },
            ) { Text(stringResource(R.string.choose_folder)) }
        }
    }

    if (pickerOpen) {
        AlertDialog(
            onDismissRequest = { if (!loading) pickerOpen = false },
            modifier = Modifier.fillMaxWidth(0.82f),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            title = { Text(stringResource(R.string.choose_folder)) },
            text = {
                Column(Modifier.fillMaxWidth().height(300.dp)) {
                    Text(
                        text = listing?.path ?: selectedPath,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (listing?.path != "/") {
                        TextButton(
                            onClick = {
                                val parent = listing?.parent ?: "/"
                                loading = true
                                scope.launch {
                                    load(parent)
                                    loading = false
                                }
                            },
                        ) {
                            Text("‹  ${stringResource(R.string.parent_folder)}")
                        }
                    }
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    LazyColumn(Modifier.weight(1f)) {
                        items(listing?.directories.orEmpty(), key = { it.path }) { directory ->
                            FolderNavigationRow(
                                name = directory.name,
                                iconRes = AppIcons.FolderColor,
                                folderContentDescription = directory.name,
                                onClick = {
                                    loading = true
                                    scope.launch {
                                        load(directory.path)
                                        loading = false
                                    }
                                },
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = newFolderName,
                            onValueChange = { newFolderName = it },
                            enabled = !loading && listing != null,
                            label = { Text(stringResource(R.string.new_folder_name)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            enabled = !loading && listing != null && newFolderName.trim().isNotEmpty(),
                            onClick = {
                                val parent = listing?.path ?: return@TextButton
                                val name = newFolderName.trim()
                                loading = true
                                error = null
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        repository.createAdminDirectory(profile, parent, name)
                                    }.onSuccess {
                                        listing = it
                                        newFolderName = ""
                                    }.onFailure {
                                        error = it.message ?: it.toString()
                                    }
                                    loading = false
                                }
                            },
                        ) {
                            Text(stringResource(R.string.create))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !loading && listing != null,
                    onClick = {
                        listing?.let {
                            onSelected(it.path)
                            onSelectedListing(it)
                        }
                        pickerOpen = false
                    },
                ) { Text(stringResource(R.string.select_this_folder)) }
            },
            dismissButton = {
                TextButton(
                    enabled = !loading,
                    onClick = { pickerOpen = false },
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

internal fun serverParentPath(path: String): String {
    val normalized = "/" + path.trim().trim('/')
    if (normalized == "/") return "/"
    return normalized.substringBeforeLast('/').ifEmpty { "/" }
}
