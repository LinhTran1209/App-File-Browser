package com.j2team.fileserver.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAdminPolicyTest {
    @Test
    fun administratorGetsEveryPermissionAndAdminSections() {
        val user = ServerUser(
            id = 1,
            username = "admin",
            admin = true,
            permissions = ServerUserPermissions(),
        )

        val effective = user.effectivePermissions()

        assertTrue(effective.create)
        assertTrue(effective.delete)
        assertTrue(effective.download)
        assertTrue(effective.modify)
        assertTrue(effective.rename)
        assertTrue(effective.share)
        assertEquals(
            listOf(ServerSettingsSection.Profile, ServerSettingsSection.Shares, ServerSettingsSection.Global, ServerSettingsSection.Users),
            user.visibleSettingsSections(),
        )
    }

    @Test
    fun regularUserOnlySeesPermittedSections() {
        val user = ServerUser(
            id = 2,
            username = "viewer",
            admin = false,
            permissions = ServerUserPermissions(download = true, share = false),
        )

        assertFalse(user.effectivePermissions().share)
        assertEquals(listOf(ServerSettingsSection.Profile), user.visibleSettingsSections())
    }

    @Test
    fun sharePermissionAddsShareManagement() {
        val user = ServerUser(
            id = 3,
            username = "sharer",
            admin = false,
            permissions = ServerUserPermissions(download = true, share = true),
        )

        assertEquals(
            listOf(ServerSettingsSection.Profile, ServerSettingsSection.Shares),
            user.visibleSettingsSections(),
        )
    }

    @Test
    fun chunkSizeIsDisplayedAndSubmittedInMegabytes() {
        assertEquals("20", bytesToMegabytes(20L * 1024L * 1024L))
        assertEquals(20L * 1024L * 1024L, megabytesToBytes("20"))
    }

    @Test
    fun invalidChunkSizeIsRejected() {
        assertNull(megabytesToBytes(""))
        assertNull(megabytesToBytes("0"))
        assertNull(megabytesToBytes("20MB"))
    }
}
