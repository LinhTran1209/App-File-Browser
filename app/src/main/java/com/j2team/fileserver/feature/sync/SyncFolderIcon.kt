package com.j2team.fileserver.feature.sync

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.j2team.fileserver.core.ui.AppIcons

@Composable
fun SyncFolderIcon(@DrawableRes folderIcon: Int, folder: SyncFolder, modifier: Modifier = Modifier) {
    val badgeColor = when {
        !folder.enabled || folder.state == SyncState.Disabled -> Color.Gray
        folder.state == SyncState.StorageFull || folder.state == SyncState.Error -> MaterialTheme.colorScheme.error
        else -> Color(0xFF2EAD62)
    }
    Box(modifier) {
        Icon(painterResource(folderIcon), null, Modifier.fillMaxSize(), tint = Color.Unspecified)
        Box(Modifier.size(17.dp).background(badgeColor, CircleShape).align(Alignment.TopStart), contentAlignment = Alignment.Center) {
            Icon(painterResource(AppIcons.Sync), null, Modifier.size(11.dp), tint = Color.White)
        }
    }
}
