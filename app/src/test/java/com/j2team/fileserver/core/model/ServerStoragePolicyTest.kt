package com.j2team.fileserver.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerStoragePolicyTest {
    @Test
    fun decimalQuotaInputRoundTripsGbAndTb() {
        assertEquals(20_000_000_000L, quotaBytesFromInput("20", QuotaUnit.GB, unlimited = false))
        assertEquals(10_000_000_000_000L, quotaBytesFromInput("10", QuotaUnit.TB, unlimited = false))
        assertEquals(QuotaInput("20", QuotaUnit.GB, false), quotaInputFromBytes(20_000_000_000L))
        assertEquals(QuotaInput("10", QuotaUnit.TB, false), quotaInputFromBytes(10_000_000_000_000L))
    }

    @Test
    fun unlimitedQuotaUsesZeroBytes() {
        assertEquals(0L, quotaBytesFromInput("999", QuotaUnit.TB, unlimited = true))
        assertEquals(QuotaInput("0", QuotaUnit.GB, true), quotaInputFromBytes(0L))
    }

    @Test
    fun userStorageRejectsMissingScopeAndQuotaOutsideFolderBounds() {
        val listing = AdminDirectoryListing(
            path = "/media/users/alice",
            parent = "/media/users",
            directories = emptyList(),
            total = 100,
            used = 40,
            free = 60,
            contentBytes = 30,
        )

        assertEquals(StorageValidation.MissingFolder, validateUserStorage(true, 50, false, listing))
        assertEquals(StorageValidation.BelowFolderContent, validateUserStorage(false, 29, false, listing))
        assertEquals(StorageValidation.AboveFilesystem, validateUserStorage(false, 101, false, listing))
        assertEquals(StorageValidation.Valid, validateUserStorage(false, 30, false, listing))
        assertEquals(StorageValidation.Valid, validateUserStorage(false, 0, true, listing))
    }

    @Test
    fun uploadRequiresKnownSizeAndFitsRemainingBytes() {
        assertTrue(uploadFitsAvailableSpace(60, total = 100, used = 40))
        assertFalse(uploadFitsAvailableSpace(61, total = 100, used = 40))
        assertFalse(uploadFitsAvailableSpace(-1, total = 100, used = 40))
    }
}
