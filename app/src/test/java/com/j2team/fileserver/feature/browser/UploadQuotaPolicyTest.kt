package com.j2team.fileserver.feature.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadQuotaPolicyTest {
    @Test
    fun exactFitIsAcceptedAndOverLimitIsRejected() {
        assertTrue(uploadSelectionFits(selectedBytes = 60, total = 100, used = 40))
        assertFalse(uploadSelectionFits(selectedBytes = 61, total = 100, used = 40))
    }

    @Test
    fun unknownOrNegativeSelectionSizeIsRejected() {
        assertFalse(uploadSelectionFits(selectedBytes = null, total = 100, used = 40))
        assertFalse(uploadSelectionFits(selectedBytes = -1, total = 100, used = 40))
    }

    @Test
    fun ownerNamesAreTrimmedAndDeduplicatedInServerOrder() {
        assertEquals(
            listOf("test2", "test1"),
            normalizedOwnerNames(listOf(" test2 ", "test1", "test2", "")),
        )
        assertTrue(normalizedOwnerNames(emptyList()).isEmpty())
    }
}
