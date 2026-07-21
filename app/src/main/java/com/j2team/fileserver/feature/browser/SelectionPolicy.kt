package com.j2team.fileserver.feature.browser

import com.j2team.fileserver.core.model.RemoteResource

data class SelectionActions(
    val canDownload: Boolean,
    val canMove: Boolean,
    val canDelete: Boolean,
    val canRename: Boolean,
)

/** Contextual actions are safe only when every selected resource permits them. */
object SelectionPolicy {
    fun actions(resources: List<RemoteResource>): SelectionActions = SelectionActions(
        canDownload = resources.isNotEmpty() && resources.all { it.permissions.canDownload },
        canMove = resources.isNotEmpty() && resources.all { it.permissions.canRename },
        canDelete = resources.isNotEmpty() && resources.all { it.permissions.canDelete },
        canRename = resources.size == 1 && resources.single().permissions.canRename,
    )
}
