package com.j2team.fileserver.feature.browser

import com.j2team.fileserver.core.model.RemoteResource
import com.j2team.fileserver.core.model.ResourcePermissions
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionPolicyTest {
    @Test
    fun deleteRequiresEverySelectedResourceToPermitIt() {
        val allowed = resource(canDelete = true)
        val denied = resource(canDelete = false)

        assertTrue(SelectionPolicy.actions(listOf(allowed)).canDelete)
        assertFalse(SelectionPolicy.actions(listOf(allowed, denied)).canDelete)
    }

    @Test
    fun downloadRequiresEverySelectedResourceToPermitIt() {
        val allowed = resource(canDownload = true)
        val denied = resource(canDownload = false)

        assertTrue(SelectionPolicy.actions(listOf(allowed)).canDownload)
        assertFalse(SelectionPolicy.actions(listOf(allowed, denied)).canDownload)
    }

    @Test
    fun noSelectionExposesNoContextualActions() {
        val actions = SelectionPolicy.actions(emptyList())

        assertFalse(actions.canDownload)
        assertFalse(actions.canCopy)
        assertFalse(actions.canDelete)
    }

    @Test
    fun copyRequiresDownloadAndCreatePermissionForEveryItem() {
        assertTrue(SelectionPolicy.actions(listOf(resource(canCreate = true))).canCopy)
        assertFalse(SelectionPolicy.actions(listOf(resource(canDownload = false, canCreate = true))).canCopy)
        assertFalse(SelectionPolicy.actions(listOf(resource(canCreate = false))).canCopy)
    }

    private fun resource(
        canDownload: Boolean = true,
        canDelete: Boolean = true,
        canCreate: Boolean = true,
    ) = RemoteResource(
        name = "item",
        path = "/item",
        isDirectory = false,
        size = 0,
        permissions = ResourcePermissions(
            canDownload = canDownload,
            canUpload = false,
            canCreate = canCreate,
            canDelete = canDelete,
        ),
    )
}
