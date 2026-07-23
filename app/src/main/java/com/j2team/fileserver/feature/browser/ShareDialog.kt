package com.j2team.fileserver.feature.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.j2team.fileserver.R
import com.j2team.fileserver.core.model.ShareDurationUnit
import com.j2team.fileserver.core.model.ShareLink
import com.j2team.fileserver.core.ui.AppIcons
import com.j2team.fileserver.core.ui.dialogOutlinedTextFieldColors
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ShareDialog(
    resourceName: String,
    shares: List<ShareLink>,
    loading: Boolean,
    mutating: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onCreate: (duration: Int, unit: ShareDurationUnit, password: String) -> Unit,
    onCopy: (ShareLink) -> Unit,
    onDelete: (ShareLink) -> Unit,
) {
    var durationText by remember { mutableStateOf("1") }
    var unit by remember { mutableStateOf(ShareDurationUnit.Hours) }
    var password by remember { mutableStateOf("") }
    var unitMenuOpen by remember { mutableStateOf(false) }
    val duration = durationText.toIntOrNull()?.takeIf { it > 0 }

    AlertDialog(
        onDismissRequest = { if (!mutating) onDismiss() },
        modifier = Modifier.fillMaxWidth(0.88f).widthIn(max = 480.dp),
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                stringResource(R.string.share_item, resourceName),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = durationText,
                        onValueChange = { durationText = it.filter(Char::isDigit).take(6) },
                        label = { Text(stringResource(R.string.share_duration)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        colors = dialogOutlinedTextFieldColors(),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        TextButton(onClick = { unitMenuOpen = true }, enabled = !mutating) {
                            Text(stringResource(unit.labelResource()))
                        }
                        DropdownMenu(expanded = unitMenuOpen, onDismissRequest = { unitMenuOpen = false }) {
                            ShareDurationUnit.entries.forEach { choice ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(choice.labelResource())) },
                                    onClick = { unit = choice; unitMenuOpen = false },
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.optional_password)) },
                    singleLine = true,
                    colors = dialogOutlinedTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (loading) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (shares.isNotEmpty()) {
                    Text(stringResource(R.string.existing_shares), style = MaterialTheme.typography.titleSmall)
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
                        items(shares, key = { it.hash }) { share ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(share.hash)
                                    Text(
                                        share.expiryLabel(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { onCopy(share) }, enabled = !mutating) {
                                    Icon(painterResource(AppIcons.Copy), stringResource(R.string.copy_link))
                                }
                                IconButton(onClick = { onDelete(share) }, enabled = !mutating) {
                                    Icon(painterResource(AppIcons.Delete), stringResource(R.string.delete))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { duration?.let { onCreate(it, unit, password) } },
                enabled = duration != null && !loading && !mutating,
            ) { Text(stringResource(R.string.create_share)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !mutating) {
                Text(stringResource(R.string.close))
            }
        },
    )
}

private fun ShareDurationUnit.labelResource(): Int = when (this) {
    ShareDurationUnit.Seconds -> R.string.seconds
    ShareDurationUnit.Minutes -> R.string.minutes
    ShareDurationUnit.Hours -> R.string.hours
    ShareDurationUnit.Days -> R.string.days
}

@Composable
private fun ShareLink.expiryLabel(): String =
    if (expire <= 0L) stringResource(R.string.never_expires)
    else stringResource(R.string.expires_at, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(expire * 1_000L)))
