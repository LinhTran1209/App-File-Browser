# File Server Android App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build, test, install, and release a fast native Android APK named
File Server for one or more official File Browser servers.

**Architecture:** A single-activity Compose app uses feature-focused packages
and immutable state. Each server owns an isolated OkHttp session, encrypted
credentials, metadata cache, and transfer namespace. A compatibility adapter
maps File Browser HTTP responses to stable domain models so the UI never
depends on raw API payloads.

**Tech Stack:** JDK 17, Gradle 9.5.0, Android Gradle Plugin 9.3.0, Kotlin
2.4.10, Compose BOM 2026.06.00, Material 3, Coroutines, OkHttp, Kotlinx
Serialization, Room, Android Keystore AES-GCM, Hilt, Coil 3, Media3,
WorkManager, MockWebServer, JUnit, Turbine, Compose UI Test.

## Global Constraints

- Product name and launcher label: `File Server`.
- Package/application ID: `com.j2team.fileserver`.
- Minimum SDK: 26; compile and target SDK: 37.
- Server product: official `filebrowser/filebrowser`, validated first against
  v2.63.x.
- Native Compose UI; WebView is not the primary browser or previewer.
- HTTP is permitted only for user-created endpoints and requires a warning
  before credentials are submitted.
- TLS certificate validation cannot be disabled.
- Credentials and tokens never enter Room, logs, crash text, screenshots, or
  analytics.
- Large resources and media are streamed with bounded buffers.
- UI actions are gated by File Browser permissions and server authorization is
  still authoritative.
- Vietnamese is the default locale; all user-visible text lives in resources.
- Each independently testable feature ends with focused tests, a conventional
  commit, and an immediate push.
- Do not report a push as successful until the remote commit SHA is confirmed.
- Current machine prerequisite: install JDK 17, Android SDK/API 37, Build Tools
  36.0.0, and Git; authorize device `727751d5` for ADB.
- Git prerequisite: initialize or clone the user's repository and configure a
  writable `origin`; the current workspace has neither a Git repository nor a
  repository exposed by the GitHub connector.

## Planned File Structure

```text
.
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/libs.versions.toml
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/j2team/fileserver/
│       │   │   ├── FileServerApp.kt
│       │   │   ├── MainActivity.kt
│       │   │   ├── core/crypto/KeystoreSecretStore.kt
│       │   │   ├── core/database/FileServerDatabase.kt
│       │   │   ├── core/network/Endpoint.kt
│       │   │   ├── core/network/FileBrowserClient.kt
│       │   │   ├── core/network/FileBrowserClientFactory.kt
│       │   │   ├── core/network/FileBrowserDtos.kt
│       │   │   ├── core/model/ServerProfile.kt
│       │   │   ├── core/model/RemoteResource.kt
│       │   │   ├── core/model/ServerCapabilities.kt
│       │   │   ├── core/ui/FileServerTheme.kt
│       │   │   ├── feature/servers/ServerRepository.kt
│       │   │   ├── feature/servers/ServersViewModel.kt
│       │   │   ├── feature/servers/ServersScreen.kt
│       │   │   ├── feature/auth/AuthRepository.kt
│       │   │   ├── feature/auth/LoginViewModel.kt
│       │   │   ├── feature/auth/LoginScreen.kt
│       │   │   ├── feature/browser/BrowserRepository.kt
│       │   │   ├── feature/browser/BrowserViewModel.kt
│       │   │   ├── feature/browser/BrowserScreen.kt
│       │   │   ├── feature/preview/PreviewRouter.kt
│       │   │   ├── feature/preview/PreviewScreen.kt
│       │   │   ├── feature/transfer/TransferRepository.kt
│       │   │   ├── feature/transfer/TransferWorker.kt
│       │   │   ├── feature/transfer/TransfersScreen.kt
│       │   │   ├── feature/settings/SettingsRepository.kt
│       │   │   └── navigation/FileServerNavHost.kt
│       │   └── res/
│       │       ├── values/strings.xml
│       │       ├── values-en/strings.xml
│       │       └── xml/network_security_config.xml
│       ├── test/java/com/j2team/fileserver/
│       └── androidTest/java/com/j2team/fileserver/
└── docs/
    ├── superpowers/specs/2026-07-20-file-server-android-design.md
    └── superpowers/plans/2026-07-20-file-server-android-implementation.md
```

---

### Task 1: Reproducible Android Project Foundation

**Files:**

- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `gradle/libs.versions.toml`
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/j2team/fileserver/FileServerApp.kt`
- Create: `app/src/main/java/com/j2team/fileserver/MainActivity.kt`
- Create: `app/src/main/java/com/j2team/fileserver/core/ui/FileServerTheme.kt`
- Test: `app/src/androidTest/java/com/j2team/fileserver/AppLaunchTest.kt`

**Interfaces:**

- Produces: a debug APK with application ID `com.j2team.fileserver`.
- Produces: `@Composable fun FileServerTheme(content: @Composable () -> Unit)`.

- [ ] **Step 1: Install and verify the required local tools**

Run:

```powershell
java -version
git --version
.\gradlew.bat --version
& 'C:\platform-tools\adb.exe' devices -l
```

Expected: JDK 17, Git available, Gradle 9.5.0, and device `727751d5` shows
`device` rather than `authorizing` or `unauthorized`.

- [ ] **Step 2: Create the Gradle project with pinned toolchain values**

Use:

```kotlin
// build.gradle.kts
plugins {
    id("com.android.application") version "9.3.0" apply false
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10" apply false
    id("com.google.dagger.hilt.android") version "2.57.1" apply false
    id("org.jetbrains.kotlin.kapt") version "2.4.10" apply false
}
```

Configure `compileSdk = 37`, `minSdk = 26`, `targetSdk = 37`, Java/Kotlin
toolchains at 17, Compose enabled, and stable Compose BOM `2026.06.00`.

- [ ] **Step 3: Write the launch test before application UI**

```kotlin
@RunWith(AndroidJUnit4::class)
class AppLaunchTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun showsProductName() {
        compose.onNodeWithText("File Server").assertIsDisplayed()
    }
}
```

- [ ] **Step 4: Run the test and verify it fails**

Run: `.\gradlew.bat connectedDebugAndroidTest`

Expected: FAIL because `MainActivity` or the title does not exist.

- [ ] **Step 5: Implement the minimal app shell and Figma tokens**

`MainActivity` hosts `FileServerTheme` and a `Scaffold` with the title
`stringResource(R.string.app_name)`. Translate the Figma Color Light/Dark,
Roboto type scale, 4/8/12/16/24/32 dp spacing, and 8/12/16/full radii into
`FileServerTheme.kt`.

- [ ] **Step 6: Verify, install, commit, and push**

Run:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug connectedDebugAndroidTest assembleDebug
& 'C:\platform-tools\adb.exe' install -r '.\app\build\outputs\apk\debug\app-debug.apk'
git add .
git commit -m "feat: bootstrap File Server Android app"
git push origin HEAD
git rev-parse HEAD
```

Expected: all checks pass, APK installs, and local/remote commit SHAs match.

---

### Task 2: Encrypted Multi-Server Profiles

**Files:**

- Create: `core/model/ServerProfile.kt`
- Create: `core/database/FileServerDatabase.kt`
- Create: `core/crypto/KeystoreSecretStore.kt`
- Create: `feature/servers/ServerRepository.kt`
- Create: `feature/servers/ServersViewModel.kt`
- Create: `feature/servers/ServersScreen.kt`
- Test: `feature/servers/ServerRepositoryTest.kt`
- Test: `feature/servers/ServersViewModelTest.kt`

**Interfaces:**

```kotlin
data class ServerProfile(
    val id: String,
    val displayName: String,
    val scheme: Scheme,
    val host: String,
    val port: Int,
    val basePath: String,
    val sortOrder: Int,
    val lastOpenedAt: Instant?
)

interface SecretStore {
    suspend fun put(profileId: String, secret: ServerSecret)
    suspend fun get(profileId: String): ServerSecret?
    suspend fun delete(profileId: String)
}

interface ServerRepository {
    fun observeAll(): Flow<List<ServerProfile>>
    suspend fun save(draft: ServerDraft): ServerProfile
    suspend fun delete(id: String)
    suspend fun reorder(ids: List<String>)
}
```

- [ ] **Step 1: Write failing tests**

Cover automatic names (`192.168.1.10:8080`), CRUD ordering, endpoint-change
secret invalidation, and delete removing the matching encrypted secret.

- [ ] **Step 2: Verify focused failure**

Run: `.\gradlew.bat testDebugUnitTest --tests "*ServerRepositoryTest"`

Expected: FAIL because repository and entities are absent.

- [ ] **Step 3: Implement Room metadata and Keystore AES-GCM secrets**

Use a non-exportable AES-256 key in `AndroidKeyStore`, a new random 12-byte IV
per encryption, and associated data equal to the profile ID. Persist only
ciphertext, IV, and version in app-private storage.

- [ ] **Step 4: Implement the Figma Servers/Add Server screens**

Match frames `01 Servers`, `01 Servers — Dark`, and `02 Add Server`. Provide
add/edit/delete/reorder actions, optional name generation, scheme, host, port,
base path, and validation messages.

- [ ] **Step 5: Verify, commit, and push**

```powershell
.\gradlew.bat testDebugUnitTest lintDebug
git add app/src
git commit -m "feat: add encrypted multi-server profiles"
git push origin HEAD
git rev-parse HEAD
```

---

### Task 3: Endpoint Normalization and Compatibility Handshake

**Files:**

- Create: `core/network/Endpoint.kt`
- Create: `core/network/FileBrowserClientFactory.kt`
- Create: `core/network/FileBrowserDtos.kt`
- Test: `core/network/EndpointTest.kt`
- Test: `core/network/CompatibilityHandshakeTest.kt`

**Interfaces:**

```kotlin
@JvmInline value class NormalizedEndpoint(val baseUrl: HttpUrl)

sealed interface HandshakeResult {
    data class Ready(val auth: AuthMode, val version: String?) : HandshakeResult
    data class Incompatible(val reason: String) : HandshakeResult
    data class Unreachable(val kind: NetworkFailure) : HandshakeResult
}

interface CompatibilityHandshake {
    suspend fun check(profile: ServerProfile): HandshakeResult
}
```

- [ ] **Step 1: Write parameterized endpoint tests**

Cover IPv4, bracketed IPv6, DNS, base paths, missing optional names, invalid
ports, embedded credentials, whitespace, and path normalization.

- [ ] **Step 2: Write MockWebServer handshake tests**

Fixtures cover JSON auth, no-auth, 401, HTML/non-File-Browser response,
timeout, HTTP warning, and version metadata.

- [ ] **Step 3: Verify failures**

Run: `.\gradlew.bat testDebugUnitTest --tests "*EndpointTest" --tests "*CompatibilityHandshakeTest"`

- [ ] **Step 4: Implement normalization and the least-privilege probe**

Do not send stored credentials during “Test connection.” Map all failures to
stable domain types and retain a safe diagnostic ID without URL query data.

- [ ] **Step 5: Commit and push**

```powershell
.\gradlew.bat testDebugUnitTest lintDebug
git add app/src
git commit -m "feat: detect compatible File Browser servers"
git push origin HEAD
```

---

### Task 4: Authentication and Per-Server Sessions

**Files:**

- Create: `core/network/FileBrowserClient.kt`
- Create: `feature/auth/AuthRepository.kt`
- Create: `feature/auth/LoginViewModel.kt`
- Create: `feature/auth/LoginScreen.kt`
- Test: `feature/auth/AuthRepositoryTest.kt`
- Test: `feature/auth/LoginViewModelTest.kt`

**Interfaces:**

```kotlin
interface AuthRepository {
    suspend fun login(
        serverId: String,
        username: String,
        password: CharArray,
        remember: Boolean
    ): AuthResult
    suspend fun enterNoAuth(serverId: String): AuthResult
    suspend fun logout(serverId: String)
}

data class ServerSession(
    val serverId: String,
    val token: String?,
    val capabilities: ServerCapabilities
)
```

- [ ] **Step 1: Write failing auth/session tests**

Cover `POST /api/login`, `X-Auth` injection, no-auth access, wrong password,
expired token, one retry after re-login, redacted logging, and absolute session
isolation between two servers.

- [ ] **Step 2: Verify failure**

Run: `.\gradlew.bat testDebugUnitTest --tests "*Auth*Test"`

- [ ] **Step 3: Implement session-isolated clients**

Use one OkHttp client/session scope per server ID. Never use a process-global
token interceptor. Overwrite password `CharArray` after use.

- [ ] **Step 4: Implement the Figma Login screen**

Match `03 Login`, including remember-password, insecure-HTTP warning, wrong
credentials, unreachable server, unsupported auth, and TLS failure states.

- [ ] **Step 5: Verify, commit, and push**

```powershell
.\gradlew.bat testDebugUnitTest lintDebug
git add app/src
git commit -m "feat: authenticate isolated File Browser sessions"
git push origin HEAD
```

---

### Task 5: Permission-Aware Folder Browser

**Files:**

- Create: `core/model/RemoteResource.kt`
- Create: `core/model/ServerCapabilities.kt`
- Create: `feature/browser/BrowserRepository.kt`
- Create: `feature/browser/BrowserViewModel.kt`
- Create: `feature/browser/BrowserScreen.kt`
- Test: `feature/browser/BrowserRepositoryTest.kt`
- Test: `feature/browser/BrowserViewModelTest.kt`
- Test: `androidTest/.../BrowserScreenTest.kt`

**Interfaces:**

```kotlin
data class RemoteResource(
    val path: String,
    val name: String,
    val kind: ResourceKind,
    val size: Long?,
    val modifiedAt: Instant?,
    val mimeType: String?
)

data class BrowserState(
    val serverId: String,
    val path: String,
    val resources: ImmutableList<RemoteResource>,
    val layout: BrowserLayout,
    val sort: ResourceSort,
    val selectedPaths: ImmutableSet<String>,
    val capabilities: ServerCapabilities,
    val refreshing: Boolean,
    val error: BrowserError?
)
```

- [ ] **Step 1: Write failing resource and state tests**

Cover encoded Unicode paths, folder listing, sort stability, search,
breadcrumbs, selection, cached-first rendering, refresh cancellation, and
capability-to-action mapping.

- [ ] **Step 2: Verify failure**

Run: `.\gradlew.bat testDebugUnitTest --tests "*Browser*Test"`

- [ ] **Step 3: Implement API/cache repository and reducer-style ViewModel**

Cache keys include server ID, canonical path, and modification marker.
Directory requests and thumbnail jobs cancel when their screen leaves
composition.

- [ ] **Step 4: Implement list and grid screens**

Match Figma `04 Files List` and `05 Files Grid`; use stable lazy-list keys,
pull-to-refresh, breadcrumbs, search, sort, list/grid preference, long-press
selection, and permission-aware menus.

- [ ] **Step 5: Performance/device verification**

Create a 500-item fixture and verify first cached content under 300 ms. Run:

```powershell
.\gradlew.bat testDebugUnitTest connectedDebugAndroidTest
& 'C:\platform-tools\adb.exe' shell dumpsys gfxinfo com.j2team.fileserver reset
```

- [ ] **Step 6: Commit and push**

```powershell
git add app/src
git commit -m "feat: browse File Browser resources"
git push origin HEAD
```

---

### Task 6: Native Preview Routing

**Files:**

- Create: `feature/preview/PreviewRouter.kt`
- Create: `feature/preview/PreviewScreen.kt`
- Create: `feature/preview/ImagePreview.kt`
- Create: `feature/preview/MediaPreview.kt`
- Create: `feature/preview/TextPreview.kt`
- Create: `feature/preview/PdfPreview.kt`
- Test: `feature/preview/PreviewRouterTest.kt`
- Test: `feature/preview/TextPreviewTest.kt`

**Interfaces:**

```kotlin
sealed interface PreviewKind {
    data object Image : PreviewKind
    data object Video : PreviewKind
    data object Audio : PreviewKind
    data object Text : PreviewKind
    data object Pdf : PreviewKind
    data object External : PreviewKind
}

interface PreviewRouter {
    fun resolve(resource: RemoteResource): PreviewKind
}
```

- [ ] **Step 1: Write failing MIME/extension routing tests**

Cover images, video, audio, text/code, PDF, missing MIME, misleading extension,
binary data, and unsupported Office/archive types.

- [ ] **Step 2: Verify failure and implement routing**

Run: `.\gradlew.bat testDebugUnitTest --tests "*Preview*Test"`

- [ ] **Step 3: Implement authenticated streaming previewers**

Use Coil authenticated fetcher for images, Media3 data source for media, a
bounded text preview ceiling, virtualized PDF pages, and `FileProvider` plus
Storage Access Framework for external opening.

- [ ] **Step 4: Implement the Figma preview experience**

Match `06 Image Preview`: edge-to-edge media, swipe adjacent previewable files,
download/share/open-with/details, and edit only for safe text with modify
permission.

- [ ] **Step 5: Verify multi-gigabyte bounded memory, commit, and push**

```powershell
.\gradlew.bat testDebugUnitTest connectedDebugAndroidTest
git add app/src
git commit -m "feat: add native file previews"
git push origin HEAD
```

---

### Task 7: Permission-Gated File Mutations

**Files:**

- Modify: `feature/browser/BrowserRepository.kt`
- Modify: `feature/browser/BrowserViewModel.kt`
- Create: `feature/browser/FileOperation.kt`
- Create: `feature/browser/FileOperationDialogs.kt`
- Test: `feature/browser/FileOperationTest.kt`
- Test: `androidTest/.../FileOperationDialogTest.kt`

**Interfaces:**

```kotlin
sealed interface FileOperation {
    data class CreateFolder(val parent: String, val name: String) : FileOperation
    data class Rename(val path: String, val newName: String) : FileOperation
    data class Copy(val sources: Set<String>, val destination: String) : FileOperation
    data class Move(val sources: Set<String>, val destination: String) : FileOperation
    data class Delete(val paths: Set<String>) : FileOperation
    data class SaveText(val path: String, val content: String) : FileOperation
}
```

- [ ] **Step 1: Write failing permission and API contract tests**

Cover create, rename, modify, delete, share, download, collisions, 401/403,
missing files, Unicode paths, multi-delete confirmation, and mid-session
permission changes.

- [ ] **Step 2: Implement minimal operations**

Evaluate capability before presenting and executing each operation. Refresh the
affected directory only after server success; never optimistically hide a
resource before destructive confirmation succeeds.

- [ ] **Step 3: Verify and commit**

```powershell
.\gradlew.bat testDebugUnitTest connectedDebugAndroidTest
git add app/src
git commit -m "feat: manage permitted remote files"
git push origin HEAD
```

---

### Task 8: Durable Uploads and Downloads

**Files:**

- Create: `feature/transfer/TransferRepository.kt`
- Create: `feature/transfer/TransferWorker.kt`
- Create: `feature/transfer/TransferNotification.kt`
- Create: `feature/transfer/TransfersScreen.kt`
- Modify: `core/database/FileServerDatabase.kt`
- Test: `feature/transfer/TransferRepositoryTest.kt`
- Test: `feature/transfer/TransferWorkerTest.kt`

**Interfaces:**

```kotlin
data class TransferRecord(
    val id: String,
    val serverId: String,
    val remotePath: String,
    val localUri: Uri,
    val direction: TransferDirection,
    val bytesDone: Long,
    val bytesTotal: Long?,
    val state: TransferState
)

interface TransferRepository {
    fun observeAll(): Flow<List<TransferRecord>>
    suspend fun enqueueUpload(serverId: String, destination: String, uris: List<Uri>)
    suspend fun enqueueDownload(serverId: String, resource: RemoteResource, target: Uri)
    suspend fun cancel(id: String)
    suspend fun retry(id: String)
}
```

- [ ] **Step 1: Write failing transfer lifecycle tests**

Cover streaming, progress persistence, cancellation, process recreation,
retryable network interruption, permanent 403, collision choice, low storage,
and no whole-file allocation.

- [ ] **Step 2: Implement WorkManager transfers**

Use foreground workers for long transfers, bounded buffers, persisted progress,
Wi-Fi-only constraints, cancellation propagation, and grouped notifications.

- [ ] **Step 3: Implement the Figma Transfers screen**

Match `07 Transfers`, including active/completed/failed groups, speed,
remaining bytes/time, cancel, retry, and open.

- [ ] **Step 4: Device verification, commit, and push**

```powershell
.\gradlew.bat testDebugUnitTest connectedDebugAndroidTest
git add app/src
git commit -m "feat: add durable background transfers"
git push origin HEAD
```

---

### Task 9: Settings, Accessibility, and Performance Hardening

**Files:**

- Create: `feature/settings/SettingsRepository.kt`
- Create: `feature/settings/SettingsScreen.kt`
- Modify: `core/ui/FileServerTheme.kt`
- Create: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/res/values-en/strings.xml`
- Create: `app/src/main/res/xml/network_security_config.xml`
- Test: `androidTest/.../AccessibilityTest.kt`
- Test: `androidTest/.../SettingsScreenTest.kt`

**Interfaces:**

```kotlin
data class AppSettings(
    val theme: ThemeMode,
    val defaultLayout: BrowserLayout,
    val defaultSort: ResourceSort,
    val wifiOnlyTransfers: Boolean,
    val cacheLimitBytes: Long,
    val textPreviewLimitBytes: Long
)
```

- [ ] **Step 1: Write failing preference and accessibility tests**

Cover persistence, light/dark/system theme, 48 dp targets, semantic labels,
large font scale, Vietnamese/English resources, and no color-only status.

- [ ] **Step 2: Implement Settings matching Figma `08 Settings`**

Add theme, layout/sort defaults, Wi-Fi-only transfers, cache limit/clear cache,
text preview ceiling, HTTP security explanation, version, and compatibility
information.

- [ ] **Step 3: Run release-quality static and performance checks**

```powershell
.\gradlew.bat testDebugUnitTest lintRelease connectedDebugAndroidTest
.\gradlew.bat :app:analyzeReleaseR8Config
```

Expected: no fatal lint, accessibility suite passes, and R8 report has no
missing required keep rules.

- [ ] **Step 4: Commit and push**

```powershell
git add app/src
git commit -m "feat: polish settings accessibility and performance"
git push origin HEAD
```

---

### Task 10: Raspberry Pi Acceptance and Signed APK Release

**Files:**

- Create: `docs/deployment.md`
- Create: `docs/test-matrix.md`
- Create: `app/src/release/keepRules/filebrowser.keep`
- Modify: `app/build.gradle.kts`
- Modify: `README.md`

**Interfaces:**

- Produces: signed `File-Server-v1.0.0.apk`.
- Produces: SHA-256 checksum and an installation guide.

- [ ] **Step 1: Validate the real File Browser server**

Record server version, auth mode, scheme, base URL, user permissions, and Pi
resource limits without recording credentials or tokens.

- [ ] **Step 2: Execute the acceptance matrix**

Test authenticated/no-auth, list/grid, search, all preview families,
permission-denied actions, upload/download interruption, app backgrounding,
process recreation, low storage, HTTP warning, HTTPS, dark mode, and large
font scale.

- [ ] **Step 3: Build and install the signed release**

```powershell
.\gradlew.bat clean testReleaseUnitTest lintRelease connectedReleaseAndroidTest assembleRelease
& 'C:\platform-tools\adb.exe' install -r '.\app\build\outputs\apk\release\app-release.apk'
Get-FileHash '.\app\build\outputs\apk\release\app-release.apk' -Algorithm SHA256
```

- [ ] **Step 4: Verify the installed package**

```powershell
& 'C:\platform-tools\adb.exe' shell pm list packages com.j2team.fileserver
& 'C:\platform-tools\adb.exe' shell am start -n com.j2team.fileserver/.MainActivity
& 'C:\platform-tools\adb.exe' shell dumpsys package com.j2team.fileserver
```

Expected: package exists, activity launches, and version name/code match
`1.0.0`/`1`.

- [ ] **Step 5: Commit release documentation and push**

```powershell
git add README.md docs app/src/release app/build.gradle.kts
git commit -m "docs: add File Server release and deployment guide"
git push origin HEAD
git rev-parse HEAD
```

- [ ] **Step 6: Tag only after all checks pass**

```powershell
git tag -a v1.0.0 -m "File Server v1.0.0"
git push origin v1.0.0
```

Expected: remote branch and tag point to the verified release commit.

## Final Verification Gate

Before calling the project complete, run:

```powershell
.\gradlew.bat clean test lintRelease connectedDebugAndroidTest assembleRelease
& 'C:\platform-tools\adb.exe' devices -l
git status --short
git log --oneline --decorate -12
git ls-remote --heads origin
```

Completion requires:

- all automated checks pass;
- device is authorized and the APK launches;
- acceptance matrix passes on Raspberry Pi 3;
- no credentials/tokens appear in logs or tracked files;
- worktree is clean;
- every feature commit exists on the configured remote;
- signed APK checksum is recorded.
