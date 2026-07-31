package com.j2team.fileserver.feature.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncModelTest {
    @Test fun fileTimestampDetectsSameSizeEdits() {
        val first = SyncEntry("a.jpg", false, 42, 10_000)
        assertTrue(first.sameContent(first.copy()))
        assertFalse(first.sameContent(first.copy(modified = 10_001)))
        assertFalse(first.sameContent(first.copy(size = 43)))
    }

    @Test fun directoriesIgnoreSizeAndTimestampNoise() {
        val first = SyncEntry("folder", true, 0, 1)
        assertTrue(first.sameContent(first.copy(size = 4096, modified = 999_999)))
    }
}
