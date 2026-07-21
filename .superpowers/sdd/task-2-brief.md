### Task 2: Language, Download Directory, and Icon Settings

**Files:**
- Modify: `app/src/main/java/com/j2team/fileserver/feature/settings/SettingsStore.kt`
- Create: `app/src/main/java/com/j2team/fileserver/feature/settings/SettingsScreen.kt`
- Create: `app/src/main/java/com/j2team/fileserver/core/ui/AppIcons.kt`
- Modify: `app/src/main/java/com/j2team/fileserver/core/ui/FileServerTheme.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-en/strings.xml`
- Replace: `app/src/main/res/drawable/settings_icon.xml`
- Test: `app/src/test/java/com/j2team/fileserver/feature/settings/SettingsCodecTest.kt`

**Interfaces:**
- Produces `enum class AppLanguage { Vietnamese, English }`.
- Extends `AppSettings` with `language`, `downloadTreeUri`, and `folderIconSet`.
- Produces `fun AppSettings.toPersistedJson(): String` and `fun decodeSettings(json: String): AppSettings`.

- [ ] **Step 1: Write failing settings round-trip tests**

```kotlin
@Test fun preservesLanguageDirectoryAndFolderIcon() {
    val input = AppSettings(
        language = AppLanguage.English,
        downloadTreeUri = "content://tree/downloads",
        folderIconSet = FolderIconSet.Color,
    )
    assertEquals(input, decodeSettings(input.toPersistedJson()))
}
```

- [ ] **Step 2: Verify RED**

Run: `.\gradlew.bat testDebugUnitTest --tests "*SettingsCodecTest"`
Expected: FAIL because the new fields/codecs are absent.

- [ ] **Step 3: Implement persistence and locale application**

Use `LocaleManager.applicationLocales = LocaleList.forLanguageTags("vi")` on API
33+, and a localized configuration context on API 26–32. Changing language
recreates only the activity. First launch defaults to Vietnamese.

- [ ] **Step 4: Implement SAF directory selection**

Use `rememberLauncherForActivityResult(OpenDocumentTree())`. On selection call:

```kotlin
contentResolver.takePersistableUriPermission(
    uri,
    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
)
onChanged(settings.copy(downloadTreeUri = uri.toString()))
```

- [ ] **Step 5: Replace and normalize icons**

Create centered 24/28 dp vector assets with 48 dp touch targets for Settings,
eye/eye-off, transfer, upload, download, new-folder, delete, list, grid, and
three folder styles. The password field uses:

```kotlin
trailingIcon = {
    IconButton(onClick = { visible = !visible }) {
        Icon(
            painterResource(if (visible) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
            contentDescription = stringResource(if (visible) R.string.hide_password else R.string.show_password),
        )
    }
}
```

- [ ] **Step 6: Verify and commit**

Run: `.\gradlew.bat testDebugUnitTest lintDebug assembleDebug`
Expected: pass with no malformed-vector lint error.

Commit: `git commit -m "feat: add language directory and icon settings"`
