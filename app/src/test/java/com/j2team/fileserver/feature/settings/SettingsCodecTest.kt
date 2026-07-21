package com.j2team.fileserver.feature.settings

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCodecTest {
    @Test fun preservesLanguageDirectoryAndFolderIcon() {
        val input = AppSettings(
            language = AppLanguage.English,
            downloadTreeUri = "content://tree/downloads",
            folderIconSet = FolderIconSet.Color,
        )

        assertEquals(input, decodeSettings(input.toPersistedJson()))
    }

    @Test fun defaultsMissingNewFieldsForExistingSettings() {
        val decoded = decodeSettings("""{"theme":"Dark","sortOrder":"Size"}""")

        assertEquals(AppTheme.Dark, decoded.theme)
        assertEquals(SortOrder.Size, decoded.sortOrder)
        assertEquals(AppLanguage.Vietnamese, decoded.language)
        assertEquals(null, decoded.downloadTreeUri)
        assertEquals(FolderIconSet.Classic, decoded.folderIconSet)
    }

    @Test fun keepsLegacyPreferencesWhenPersistedJsonIsMalformed() {
        val legacy = AppSettings(
            theme = AppTheme.Dark,
            sortOrder = SortOrder.Modified,
            ascending = false,
            gridView = true,
            showHiddenFiles = true,
        )

        assertEquals(legacy, resolvePersistedSettings("{not json", legacy))
    }

    @Test fun readsPersistedJsonInsteadOfLegacyPreferences() {
        val legacy = AppSettings(theme = AppTheme.Dark)
        val persisted = AppSettings(language = AppLanguage.English, folderIconSet = FolderIconSet.Outline)

        assertEquals(persisted, resolvePersistedSettings(persisted.toPersistedJson(), legacy))
    }

    @Test fun rejectsReadOnlyTreeGrant() {
        assertFalse(acceptsDownloadTreeGrant(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    @Test fun acceptsTreeGrantWithWritePermission() {
        assertTrue(acceptsDownloadTreeGrant(Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
    }
}
