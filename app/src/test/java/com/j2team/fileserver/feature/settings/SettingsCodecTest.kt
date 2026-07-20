package com.j2team.fileserver.feature.settings

import org.junit.Assert.assertEquals
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
}
