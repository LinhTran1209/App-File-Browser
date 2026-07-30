# File Server Android v1.6.0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Synchronize the native Android client with the customized File Browser quota, directory administration, ownership warning and shared-thumbnail APIs.

**Architecture:** Extend the existing model, `FileBrowserClient` and `SessionRepository` layers rather than introducing another transport stack. Keep validation and response/request codecs as pure Kotlin units covered by JVM tests, then bind those units to the existing Compose administration and browser screens.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, coroutines, `HttpURLConnection`, Android Storage Access Framework, Media3, JUnit 4, Gradle.

## Global Constraints

- Version name is `1.6.0`; increment `versionCode` from 7 to 8.
- Keep Android API 26 as the minimum version.
- Keep native Media3 MPEG-TS playback unchanged.
- Use the integrated File Browser video-thumbnail endpoint; do not require port 8890.
- Build and test on Windows.
- Produce a debug APK but do not install it.
- Do not create a Git commit.

---

### Task 1: Storage and quota domain contracts

**Files:**
- Modify: `app/src/main/java/com/j2team/fileserver/core/model/ServerAdminModels.kt`
- Create: `app/src/main/java/com/j2team/fileserver/core/model/ServerStorageModels.kt`
- Test: `app/src/test/java/com/j2team/fileserver/core/model/ServerStoragePolicyTest.kt`

**Interfaces:**
- Produces: `AdminDirectoryEntry`, `AdminDirectoryListing`, `QuotaUnit`, `quotaInputFromBytes`, `quotaBytesFromInput`, `validateUserStorage`, `uploadFitsAvailableSpace`.
- Extends: `ServerUser` with quota and missing-scope fields.

- [ ] **Step 1: Write failing model-policy tests**

Cover GB/TB conversion, unlimited quota, quota below folder content, quota above
filesystem capacity, missing-scope invalidity and upload remaining-space checks.

- [ ] **Step 2: Run the focused tests and verify RED**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "*ServerStoragePolicyTest"
```

Expected: compilation fails because the storage contracts do not exist.

- [ ] **Step 3: Implement minimal storage models and pure policies**

Use decimal server units (`1 GB = 1_000_000_000`, `1 TB =
1_000_000_000_000`) to match the web editor and backend values.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run the command from Step 2 and require zero failures.

### Task 2: Customized File Browser API contracts

**Files:**
- Modify: `app/src/main/java/com/j2team/fileserver/core/network/FileBrowserClient.kt`
- Modify: `app/src/main/java/com/j2team/fileserver/core/session/SessionRepository.kt`
- Create: `app/src/test/java/com/j2team/fileserver/core/network/ServerStorageCodecTest.kt`

**Interfaces:**
- Consumes: storage contracts from Task 1.
- Produces: user detail loading, folder list/create, resource-owner lookup,
  integrated thumbnail GET/POST, and corrected user create/update payloads.

- [ ] **Step 1: Write failing JSON and request-policy tests**

Assert parsing of `quotaBytes`, usage fields and `scopeMissing`; parse directory
capacity and owner usernames; assert create-user requests use `which: []`,
`createUserDir: false`, and update requests include `quotaBytes`.

- [ ] **Step 2: Run focused tests and verify RED**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "*ServerStorageCodecTest"
```

Expected: tests fail because quota/directory/owner codecs and payload fields are
missing.

- [ ] **Step 3: Add authenticated transport methods**

Add methods for:

```text
GET  /api/users/{id}
GET  /api/admin/directories?path=...
POST /api/admin/directories
POST /api/resource-owners
GET  /api/video-thumbnail?path=...
POST /api/video-thumbnail?path=...
```

Expose token-renewing repository wrappers. Replace the repository's port-8890
thumbnail calls with the integrated API flow.

- [ ] **Step 4: Correct user mutation payloads**

Creation sends the complete user JSON, `which: []`, and
`createUserDir: false`. Updates add `quotaBytes` to the field list.

- [ ] **Step 5: Run focused tests and verify GREEN**

Run the command from Step 2 and require zero failures.

### Task 3: Native user storage editor

**Files:**
- Modify: `app/src/main/java/com/j2team/fileserver/feature/serversettings/ServerSettingsScreen.kt`
- Create: `app/src/main/java/com/j2team/fileserver/feature/serversettings/ServerFolderPicker.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-en/strings.xml`

**Interfaces:**
- Consumes: user-detail, admin-directory and storage-validation interfaces from
  Tasks 1 and 2.
- Produces: a valid `ServerUser` with explicit scope and quota.

- [ ] **Step 1: Load fresh user detail before opening the editor**

Keep the users list lightweight, but call `repository.user(profile, id)` when
Edit is selected. Preserve the dialog and allow deletion when detail reports a
missing scope.

- [ ] **Step 2: Add the server folder picker**

Support parent navigation, directory navigation, folder creation and current
folder selection. Show total, used, free and selected-folder content.

- [ ] **Step 3: Add quota controls and validation**

Add unlimited toggle, decimal amount, GB/TB selection, used/remaining summary
and localized red errors. Disable Save while the selected folder is unavailable
or the finite quota violates capacity/content bounds.

- [ ] **Step 4: Compile the Compose UI**

Run:

```powershell
.\gradlew.bat compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

### Task 4: Upload preflight and owned-folder deletion

**Files:**
- Create: `app/src/main/java/com/j2team/fileserver/feature/browser/UploadQuotaPolicy.kt`
- Modify: `app/src/main/java/com/j2team/fileserver/feature/browser/BrowserScreen.kt`
- Modify: `app/src/main/java/com/j2team/fileserver/feature/transfers/TransferCoordinator.kt`
- Create: `app/src/test/java/com/j2team/fileserver/feature/browser/UploadQuotaPolicyTest.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-en/strings.xml`

**Interfaces:**
- Consumes: `/api/usage`, `/api/resource-owners`, Android document sizes and
  existing upload/delete entry points.
- Produces: preflight result with required and available bytes plus localized
  confirmation state.

- [ ] **Step 1: Write failing upload and ownership policy tests**

Cover exact-fit acceptance, over-limit rejection, unknown/negative sizes,
deduplicated usernames and normal confirmation when no owner exists.

- [ ] **Step 2: Run focused tests and verify RED**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "*UploadQuotaPolicyTest"
```

- [ ] **Step 3: Implement pure policies and integrate file upload**

Resolve the selected document size, fetch `/api/usage`, and reject before
creating a transfer task when the file does not fit.

- [ ] **Step 4: Integrate folder upload preflight**

Recursively sum `COLUMN_SIZE` before creating remote directories. Treat missing
file sizes as a preflight error rather than silently bypassing the quota check.

- [ ] **Step 5: Integrate owner-aware delete confirmation**

Lookup all selected paths before deletion. Show the usernames returned by the
server and require explicit confirmation.

- [ ] **Step 6: Run focused tests and verify GREEN**

Run the command from Step 2 and require zero failures.

### Task 5: Shared integrated thumbnails

**Files:**
- Modify: `app/src/main/java/com/j2team/fileserver/core/network/FileBrowserClient.kt`
- Modify: `app/src/main/java/com/j2team/fileserver/core/session/SessionRepository.kt`
- Modify: `app/src/main/java/com/j2team/fileserver/feature/browser/BrowserScreen.kt`
- Modify: `app/src/test/java/com/j2team/fileserver/feature/preview/ThumbnailRequestDispatchTest.kt`

**Interfaces:**
- Consumes: authenticated integrated thumbnail GET/POST methods from Task 2.
- Produces: bounded queue-and-poll behavior that writes into
  `AppCacheManager.thumbnailFile`.

- [ ] **Step 1: Write a failing bounded-retry policy test**

Assert that a missing thumbnail is queued once, retrieval is retried a bounded
number of times, and permanent failure returns the video-icon fallback.

- [ ] **Step 2: Verify RED**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "*ThumbnailRequestDispatchTest"
```

- [ ] **Step 3: Implement integrated queue and retrieval**

Use `/api/video-thumbnail`; remove runtime calls through
`ThumbnailServiceClient`. Keep polling off the main thread and preserve local
cache bounds.

- [ ] **Step 4: Verify GREEN**

Run the command from Step 2 and require zero failures.

### Task 6: Version, regression verification and APK

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `README.md`
- Output: `app/build/outputs/apk/debug/file-server-v1.6.0-debug.apk`

**Interfaces:**
- Consumes: all prior tasks.
- Produces: installable v1.6.0 debug APK.

- [ ] **Step 1: Set version metadata**

Set `versionCode = 8`, `versionName = "1.6.0"` and update README build/output
text.

- [ ] **Step 2: Run the full JVM unit-test suite**

Run:

```powershell
.\gradlew.bat testDebugUnitTest
```

Expected: BUILD SUCCESSFUL with zero failed tests.

- [ ] **Step 3: Run lint and assemble**

Run:

```powershell
.\gradlew.bat lintDebug assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Rename and hash the APK**

Copy the generated debug artifact to
`app/build/outputs/apk/debug/file-server-v1.6.0-debug.apk` and calculate
SHA-256. Do not install, commit or push.

- [ ] **Step 5: Inspect final status**

Run `git diff --check` and `git status --short`; preserve the user's existing
`.gitignore` change and exclude `.kotlin/` from the delivered source summary.
