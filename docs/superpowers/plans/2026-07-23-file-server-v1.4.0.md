# File Server v1.4.0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add native single-resource share links, drive usage, clearer dialogs, and the requested browser/settings cleanup for v1.4.0.

**Architecture:** Extend the existing authenticated File Browser transport and session repository with typed share and usage operations. Keep UI state in `BrowserScreen`, extract reusable field/usage/share composables into focused files, and preserve existing session renewal behavior.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, `HttpURLConnection`, `org.json`, File Browser v2.63.18 API, Gradle Android plugin.

## Global Constraints

- Android release is exactly `versionCode = 6`, `versionName = 1.4.0`.
- Share is available only for exactly one selected resource.
- Hidden dotfiles are always omitted.
- No Raspberry Pi service changes.
- Do not stage the user's unrelated `.gitignore` change.
- Per user instruction, do not run automated or device functional tests; perform the required debug APK build only.

---

### Task 1: Settings migration, dialog styling, and release metadata

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/java/com/j2team/fileserver/feature/settings/SettingsStore.kt`
- Modify: `app/src/main/java/com/j2team/fileserver/feature/settings/SettingsScreen.kt`
- Create: `app/src/main/java/com/j2team/fileserver/core/ui/DialogFields.kt`

**Interfaces:**
- Produces: `@Composable fun DialogOutlinedTextField(...)`
- Produces: `AppSettings` without an active `showHiddenFiles` property.

- [ ] **Step 1: Remove the hidden-files control**

Delete the Settings row and stop exposing `showHiddenFiles` in active state. Keep the old JSON field optional during decode so existing installations migrate without losing language, theme, folder icon, or download directory settings.

- [ ] **Step 2: Add visible dialog field colors**

Create a shared `DialogOutlinedTextField` wrapper using:

```kotlin
OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
    errorBorderColor = MaterialTheme.colorScheme.error,
)
```

Use it for path, new-folder, and rename inputs.

- [ ] **Step 3: Set release metadata**

Set:

```kotlin
versionCode = 6
versionName = "1.4.0"
```

### Task 2: Typed File Browser usage and share API

**Files:**
- Create: `app/src/main/java/com/j2team/fileserver/core/model/ShareModels.kt`
- Modify: `app/src/main/java/com/j2team/fileserver/core/model/RemoteResource.kt`
- Modify: `app/src/main/java/com/j2team/fileserver/core/network/FileBrowserClient.kt`
- Modify: `app/src/main/java/com/j2team/fileserver/core/session/SessionRepository.kt`
- Modify: `app/src/main/java/com/j2team/fileserver/feature/browser/SelectionPolicy.kt`

**Interfaces:**
- Produces: `data class DiskUsage(val total: Long, val used: Long)`
- Produces: `data class ShareLink(val hash: String, val path: String, val expire: Long, val hasPassword: Boolean)`
- Produces: `enum class ShareDurationUnit(val apiValue: String)`
- Produces: `suspend fun diskUsage(profile, path): Result<DiskUsage>`
- Produces: `suspend fun shares(profile, path): Result<List<ShareLink>>`
- Produces: `suspend fun createShare(profile, path, duration, unit, password): Result<ShareLink>`
- Produces: `suspend fun deleteShare(profile, hash): Result<Unit>`
- Produces: `SelectionActions.canShare`.

- [ ] **Step 1: Add immutable models**

Model File Browser's `total`, `used`, `hash`, `path`, `expire`, and `hasPassword` fields. Units map exactly to `seconds`, `minutes`, `hours`, and `days`.

- [ ] **Step 2: Add authenticated transport calls**

Implement:

```text
GET    /api/usage{encodedPath}
GET    /api/share{encodedPath}
POST   /api/share{encodedPath}
DELETE /api/share/{encodedHash}
```

The POST body is JSON:

```json
{"password":"optional","expires":"1","unit":"hours"}
```

Use existing request timeouts, `X-Auth`, `requestError`, and `ApiResult`.

- [ ] **Step 3: Expose session operations**

Read operations use `authenticated`. Create/delete use `mutationToken` so a safe authenticated read occurs before a non-idempotent request.

- [ ] **Step 4: Add share permission**

Map File Browser user permission `share` into `ResourcePermissions.canShare`. `SelectionPolicy` enables Share only when the selection size is one and the resource is downloadable/shareable.

### Task 3: Browser share dialog and drive usage

**Files:**
- Create: `app/src/main/java/com/j2team/fileserver/feature/browser/ShareDialog.kt`
- Create: `app/src/main/res/drawable/ic_share.xml`
- Create: `app/src/main/res/drawable/ic_copy.xml`
- Modify: `app/src/main/java/com/j2team/fileserver/core/ui/AppIcons.kt`
- Modify: `app/src/main/java/com/j2team/fileserver/feature/browser/BrowserScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-en/strings.xml`

**Interfaces:**
- Consumes: Task 2 share and usage repository functions.
- Produces: `@Composable fun ShareDialog(...)`
- Produces: `fun formatBinaryBytes(bytes: Long): String`

- [ ] **Step 1: Always filter hidden resources**

Replace the configurable filter with:

```kotlin
listing.resources.filterNot { it.name.startsWith(".") }
```

Remove `settings.showHiddenFiles` from refresh keys.

- [ ] **Step 2: Replace the selection title and add Share**

Use `selected.size.toString()` as the app-bar title. Add the Share icon only when `actions.canShare`; opening it loads shares for the single selected resource.

- [ ] **Step 3: Implement Share creation and management UI**

The dialog validates a positive integer duration, offers the four units, accepts an optional password, creates a link, copies the public URL through Android `ClipboardManager`, and deletes individual shares. Show progress and inline errors without clearing selection.

- [ ] **Step 4: Replace List/Grid heading with usage**

Load `/api/usage{path}` together with each directory refresh. Render:

```text
4.94 GiB of 28.5 GiB used
[progress indicator]
```

Keep Name sorting and item count on the right. If usage fails, show an unavailable label without affecting file results.

- [ ] **Step 5: Localize new copy**

Add Vietnamese and English strings for Share, duration units, optional password, copy, expiry, disk usage, unavailable usage, and validation/error states.

### Task 4: Build, review scope, commit, and push

**Files:**
- Build output: `app/build/outputs/apk/debug/app-debug.apk`

- [ ] **Step 1: Build the APK**

Run Gradle `assembleDebug` with the repository's JDK 17 and Android SDK. Expected result: `BUILD SUCCESSFUL` and a non-empty debug APK.

- [ ] **Step 2: Review repository scope**

Run `git status --short` and `git diff --check`. Confirm `.gitignore` remains unstaged and unchanged by this implementation.

- [ ] **Step 3: Commit explicitly scoped files**

Stage only v1.4.0 source, resources, specification, and plan files. Commit:

```text
feat: add sharing and drive usage for v1.4.0
```

- [ ] **Step 4: Push main**

Push the commit to `origin/main` and report the commit hash plus absolute APK path.
