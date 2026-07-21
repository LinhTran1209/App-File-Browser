# File Server Android Enhancements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add durable encrypted sessions, bilingual settings, reliable file operations and transfers, permission-aware multi-select, and broad native preview support to the File Server Android app.

**Architecture:** Split the current monolithic activity into repositories and focused Compose screens. `SessionRepository` owns encrypted authentication and one-shot token renewal; `FileBrowserClient` owns protocol details; browser, transfer, settings, and preview features consume stable domain interfaces. All large resources use bounded streaming.

**Tech Stack:** Android API 26–37, Kotlin 2.4.10, Compose Material 3, Coroutines, Android Keystore AES-GCM, Storage Access Framework, Media3 ExoPlayer, PdfRenderer, JUnit, Compose UI Test.

## Global Constraints

- Product/package remain `File Server` and `com.j2team.fileserver`.
- Vietnamese is the first-launch language; English is selectable.
- Credentials use a non-exportable AES-256 Android Keystore key and never enter logs or transfer records.
- A rejected token triggers at most one encrypted-credential login and one request retry.
- Delete is visible only when all selected resources permit deletion; the server remains authoritative.
- Upload/download use bounded buffers and at most two concurrent transfers.
- Media extension recognition does not imply codec support; unsupported codecs use an external-app fallback.
- Every task follows red-green-refactor, then unit test, lint, build, commit, and confirmed GitHub update.

---

### Task 1: Encrypted Persistent Sessions

**Files:**
- Create: `app/src/main/java/com/j2team/fileserver/core/session/EncryptedSecretStore.kt`
- Create: `app/src/main/java/com/j2team/fileserver/core/session/SessionRepository.kt`
- Create: `app/src/main/java/com/j2team/fileserver/core/session/SessionModels.kt`
- Modify: `app/src/main/java/com/j2team/fileserver/core/network/FileBrowserClient.kt`
- Modify: `app/src/main/java/com/j2team/fileserver/MainActivity.kt`
- Test: `app/src/test/java/com/j2team/fileserver/core/session/SessionPolicyTest.kt`

**Interfaces:**
- Produces `data class StoredCredential(val username: String, val password: CharArray)`.
- Produces `interface SecretStore { fun put(profileId: String, credential: StoredCredential); fun get(profileId: String): StoredCredential?; fun delete(profileId: String) }`.
- Produces `class SessionRepository` with `suspend fun open(profile): Result<AuthenticatedSession>`, `suspend fun login(profile, username, password): Result<AuthenticatedSession>`, and `fun clear(profileId)`.

- [ ] **Step 1: Write the failing token-renewal policy test**

```kotlin
@Test fun rejectedTokenRelogsInExactlyOnce() = runTest {
    val transport = FakeTransport(responses = mutableListOf(401, 200, 200))
    val policy = SessionPolicy(transport)
    assertTrue(policy.execute("old-token") { transport.request(it) }.isSuccess)
    assertEquals(1, transport.loginCalls)
    assertEquals(2, transport.requestCalls)
}
```

- [ ] **Step 2: Run the focused test**

Run: `.\gradlew.bat testDebugUnitTest --tests "*SessionPolicyTest"`
Expected: FAIL because `SessionPolicy` is absent.

- [ ] **Step 3: Implement encrypted storage**

Use `KeyGenParameterSpec` with alias `file_server_credentials_v1`, AES/GCM/NoPadding,
256-bit key, random 12-byte IV, and profile ID bytes passed to
`cipher.updateAAD(profileId.toByteArray())`. Persist only Base64 IV and ciphertext
in private SharedPreferences.

- [ ] **Step 4: Implement one-shot session renewal**

```kotlin
suspend fun <T> authenticated(profile: ServerProfile, call: suspend (String) -> ApiResult<T>): Result<T> {
    val first = call(tokenStore[profile.id].orEmpty())
    if (first.code !in setOf(401, 403)) return first.toResult()
    val credential = secretStore.get(profile.id) ?: return Result.failure(LoginRequiredException())
    val renewed = transport.login(profile, credential.username, credential.password.concatToString()).getOrThrow()
    tokenStore[profile.id] = renewed
    return call(renewed).toResult()
}
```

- [ ] **Step 5: Wire login and server opening**

Successful explicit login stores credentials and token. Opening a saved server
calls `SessionRepository.open`; it navigates directly to Browser on success and
to Login only for `LoginRequiredException`.

- [ ] **Step 6: Verify and commit**

Run: `.\gradlew.bat testDebugUnitTest lintDebug assembleDebug`
Expected: all tasks pass.

Commit: `git commit -m "feat: persist encrypted server sessions"`

---

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

---

### Task 3: File Browser Permissions, Long Press, and Mutations

**Files:**
- Extend: `app/src/main/java/com/j2team/fileserver/core/model/RemoteResource.kt`
- Modify: `app/src/main/java/com/j2team/fileserver/core/network/FileBrowserClient.kt`
- Create: `app/src/main/java/com/j2team/fileserver/feature/browser/SelectionPolicy.kt`
- Create: `app/src/main/java/com/j2team/fileserver/feature/browser/BrowserScreen.kt`
- Test: `app/src/test/java/com/j2team/fileserver/feature/browser/SelectionPolicyTest.kt`
- Test: `app/src/test/java/com/j2team/fileserver/core/network/FileMutationRequestTest.kt`

**Interfaces:**
- Extends `RemoteResource` with `permissions: ResourcePermissions`.
- Produces `data class ResourcePermissions(val canDownload: Boolean, val canUpload: Boolean, val canCreate: Boolean, val canDelete: Boolean)`.
- Produces `suspend fun createDirectory(profile, token, path): Result<Unit>`.
- Produces `suspend fun delete(profile, token, paths): Result<Unit>`.

- [ ] **Step 1: Write failing permission-intersection tests**

```kotlin
@Test fun deleteRequiresEverySelectedResourceToPermitIt() {
    val allowed = resource(canDelete = true)
    val denied = resource(canDelete = false)
    assertTrue(SelectionPolicy.actions(listOf(allowed)).canDelete)
    assertFalse(SelectionPolicy.actions(listOf(allowed, denied)).canDelete)
}
```

- [ ] **Step 2: Verify RED**

Run: `.\gradlew.bat testDebugUnitTest --tests "*SelectionPolicyTest"`
Expected: FAIL because permissions and selection policy are absent.

- [ ] **Step 3: Parse permissions and implement API mutations**

Map File Browser response permissions into the stable domain model. Encode each
path segment once. `DELETE /api/resources/{path}` runs sequentially and stops on
the first rejected resource; the browser refreshes from the server instead of
removing items optimistically.

- [ ] **Step 4: Implement blank-strip and selection interactions**

Use `combinedClickable(onLongClick = ...)` on a 48 dp action strip below the
breadcrumb. Its menu contains New folder, Upload files, and Upload folder.
Resource long press enters selection mode; the contextual app bar shows the
count and only the `SelectionPolicy` intersection.

- [ ] **Step 5: Add guarded delete**

Show a confirmation dialog with selection count. Disable delete when any
selected item has `canDelete == false`. On server rejection retain every item,
clear no selection, and show the returned error.

- [ ] **Step 6: Verify and commit**

Run: `.\gradlew.bat testDebugUnitTest lintDebug assembleDebug`
Expected: pass.

Commit: `git commit -m "feat: add permission aware browser mutations"`

---

### Task 4: Reliable Uploads, Folder Upload, Downloads, and Transfer Badge

**Files:**
- Modify: `app/src/main/java/com/j2team/fileserver/core/network/FileBrowserClient.kt`
- Modify: `app/src/main/java/com/j2team/fileserver/feature/transfers/TransferModels.kt`
- Modify: `app/src/main/java/com/j2team/fileserver/feature/transfers/TransferStore.kt`
- Create: `app/src/main/java/com/j2team/fileserver/feature/transfers/TransferCoordinator.kt`
- Create: `app/src/main/java/com/j2team/fileserver/feature/transfers/TransfersScreen.kt`
- Test: `app/src/test/java/com/j2team/fileserver/feature/transfers/TransferPolicyTest.kt`
- Test: `app/src/test/java/com/j2team/fileserver/core/network/MultipartEncoderTest.kt`

**Interfaces:**
- Produces `enum class TransferTab { Downloads, Uploads }`.
- Produces `fun List<TransferTask>.attentionCount(): Int`.
- Produces `class TransferCoordinator` limited by `Semaphore(2)`.
- Produces `suspend fun enqueueFolder(treeUri, remotePath)`.

- [ ] **Step 1: Write failing badge/tab tests**

```kotlin
@Test fun badgeCountsTasksNeedingAttentionAndCapsAt99() {
    assertEquals("4", fourMixedActiveAndFailed.attentionBadge())
    assertEquals("99+", oneHundredQueued.attentionBadge())
}

@Test fun tabsSeparateDirections() {
    assertTrue(tasks.forTab(TransferTab.Uploads).all { it.direction == TransferDirection.Upload })
}
```

- [ ] **Step 2: Write failing multipart tests**

Assert one `file` part, filename escaping, correct terminal boundary, exact
encoded destination, non-2xx failure, and progress never exceeding total.

- [ ] **Step 3: Verify RED**

Run: `.\gradlew.bat testDebugUnitTest --tests "*TransferPolicyTest" --tests "*MultipartEncoderTest"`
Expected: FAIL for missing policy/encoder.

- [ ] **Step 4: Implement transfer coordinator**

Use `Semaphore(2)` around streaming operations. Folder upload recursively walks
`DocumentFile`, creates directories before files, and enqueues one durable task
per file. Failed tasks retain their last byte count and error.

- [ ] **Step 5: Implement SAF downloads**

Write `.<name>.part` through `DocumentFile.createFile`, copy with an 8 KiB
buffer, and create the final document only after success. Handle replace, keep
both, and cancel via an explicit conflict dialog.

- [ ] **Step 6: Implement transfer UI and badge**

Use a 28 dp transfer icon, Material badge, Downloads/Uploads tabs, state rows,
retry, dismiss, and progress. The browser toolbar observes the queue and updates
the badge without reopening the screen.

- [ ] **Step 7: Verify and commit**

Run: `.\gradlew.bat testDebugUnitTest lintDebug assembleDebug`
Expected: pass.

Commit: `git commit -m "feat: deliver reliable transfer center"`

---

### Task 5: Scrollable Text, Code, PDF, and Image Preview

**Files:**
- Modify: `app/src/main/java/com/j2team/fileserver/feature/preview/PreviewRouter.kt`
- Create: `app/src/main/java/com/j2team/fileserver/feature/preview/TextPreview.kt`
- Create: `app/src/main/java/com/j2team/fileserver/feature/preview/PdfPreview.kt`
- Create: `app/src/main/java/com/j2team/fileserver/feature/preview/ImagePreview.kt`
- Create: `app/src/main/java/com/j2team/fileserver/feature/preview/PreviewScreen.kt`
- Test: `app/src/test/java/com/j2team/fileserver/feature/preview/PreviewRouterTest.kt`
- Test: `app/src/test/java/com/j2team/fileserver/feature/preview/TextPagerTest.kt`

**Interfaces:**
- Adds `PreviewKind.Pdf`.
- Produces `class TextPager(input: InputStream, pageBytes: Int = 128 * 1024)`.
- Produces `suspend fun loadNext(): TextPage`.

- [ ] **Step 1: Write failing preview-routing tests**

Create table-driven assertions covering every text/code, image, PDF, audio, and
video extension listed in the specification, including uppercase extensions and
extensionless text.

- [ ] **Step 2: Write failing long-text paging test**

```kotlin
@Test fun readsLongTextInBoundedPagesWithoutTruncating() {
    val pager = TextPager(ByteArrayInputStream("x".repeat(400_000).toByteArray()), 128_000)
    val pages = generateSequence { pager.loadNextBlocking() }.toList()
    assertEquals(400_000, pages.sumOf { it.text.length })
    assertTrue(pages.size > 1)
}
```

- [ ] **Step 3: Verify RED**

Run: `.\gradlew.bat testDebugUnitTest --tests "*PreviewRouterTest" --tests "*TextPagerTest"`
Expected: FAIL.

- [ ] **Step 4: Implement previews**

Text/code uses `LazyColumn` pages and selectable monospace text. Markdown remains
scrollable plain/source text. PDF uses `PdfRenderer` and lazy page bitmaps.
Images decode sampled dimensions and recycle temporary files/bitmaps when the
screen leaves composition.

- [ ] **Step 5: Verify and commit**

Run: `.\gradlew.bat testDebugUnitTest lintDebug assembleDebug`
Expected: pass.

Commit: `git commit -m "feat: add document and code previews"`

---

### Task 6: Media3 Audio and Video Streaming

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/com/j2team/fileserver/feature/preview/MediaPreview.kt`
- Modify: `app/src/main/java/com/j2team/fileserver/feature/preview/PreviewScreen.kt`
- Test: `app/src/test/java/com/j2team/fileserver/feature/preview/MediaTypeTest.kt`

**Interfaces:**
- Produces `fun mediaMimeType(name: String): String?`.
- Produces Media3 player factory with authenticated HTTP headers and bounded
  `DefaultLoadControl`.

- [ ] **Step 1: Write failing media type tests**

Assert MIME mappings for `mp4`, `mkv`, `mov`, `webm`, `wmv`, `avi`, `m2ts`,
`mts`, `ts`, `flv`, `3gp`, `mpeg`, `vob`, `ogv`, `mp3`, `aac`, `flac`, `wav`,
`ogg`, `opus`, `wma`, `amr`, `aiff`, and `mka`.

- [ ] **Step 2: Verify RED**

Run: `.\gradlew.bat testDebugUnitTest --tests "*MediaTypeTest"`
Expected: FAIL for incomplete mappings.

- [ ] **Step 3: Add Media3**

Pin Media3 in the version catalog and add `media3-exoplayer`,
`media3-ui-compose`, and `media3-datasource-okhttp`. Configure the data source
with `X-Auth` and load control with a bounded 15–50 second video buffer.

- [ ] **Step 4: Implement playback and fallback**

Dispose the player in `DisposableEffect`. Display codec/playback errors and an
Open with another app action using a temporary FileProvider URI. Directory
lists never create players or prebuffer media.

- [ ] **Step 5: Verify and commit**

Run: `.\gradlew.bat testDebugUnitTest lintDebug assembleDebug`
Expected: pass.

Commit: `git commit -m "feat: stream common audio and video formats"`

---

### Task 7: Launcher Branding and Full Acceptance

**Files:**
- Create: `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- Create: `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`
- Create: `app/src/main/res/drawable/ic_launcher_foreground.xml`
- Create: `app/src/main/res/values/ic_launcher_background.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/androidTest/java/com/j2team/fileserver/AppLaunchTest.kt`
- Create: `app/src/androidTest/java/com/j2team/fileserver/SessionResumeTest.kt`
- Create: `app/src/androidTest/java/com/j2team/fileserver/SettingsAndSelectionTest.kt`

**Interfaces:**
- Produces adaptive launcher icon using a folder/server mark.
- Produces end-to-end device verification evidence.

- [ ] **Step 1: Add instrumentation tests**

Tests cover password eye semantics, session resume after activity recreation,
language change, directory-selection state, long-press selection, transfer tabs,
and long-text scrolling.

- [ ] **Step 2: Verify instrumentation**

Run: `.\gradlew.bat connectedDebugAndroidTest`
Expected: all tests pass on `QV704YX51G`.

- [ ] **Step 3: Add adaptive launcher branding**

Use a safe-zone centered folder/server vector foreground with indigo background.
Set `android:icon` and `android:roundIcon` in the manifest.

- [ ] **Step 4: Run complete verification**

Run:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug connectedDebugAndroidTest assembleDebug
& $adb -s QV704YX51G install -r .\app\build\outputs\apk\debug\app-debug.apk
```

Expected: exit code 0, installation `Success`.

- [ ] **Step 5: Raspberry Pi acceptance**

Using a uniquely named temporary directory:

1. Login once and confirm process-restart session resume.
2. Upload a text file and nested folder.
3. Download the text file to the selected SAF directory.
4. Preview text/code, PDF, image, audio, and available video samples.
5. Multi-select and delete the temporary resources.
6. Confirm denied delete remains visible.
7. Confirm no more than two concurrent transfers.

- [ ] **Step 6: Commit and publish**

Commit: `git commit -m "feat: complete File Server Android experience"`

Update every changed file through the connected GitHub app and record the
confirmed remote commit SHA. Do not report a Git push until confirmation is
returned.
