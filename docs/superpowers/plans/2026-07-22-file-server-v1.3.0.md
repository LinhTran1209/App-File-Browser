# File Server v1.3.0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add direct path navigation, authenticated 25%-frame video thumbnails, a 100 MiB automatic cache, and theme-correct system bars.

**Architecture:** Keep browser navigation state in `BrowserScreen`, isolate video frame extraction in a focused preview utility, and retain `AppCacheManager` as the only cache eviction authority. Apply safe-area padding to content inside a full-screen themed surface and synchronize system bar styles from `FileServerTheme`.

**Tech Stack:** Kotlin, Jetpack Compose, Android MediaMetadataRetriever, Kotlin Coroutines, AndroidX Activity edge-to-edge APIs.

## Global Constraints

- Version code is 4 and version name is 1.3.0.
- Thumbnail cache maximum is 100 MiB with least-recently-used eviction.
- Video frame timestamp is 25% of duration.
- README is unchanged.
- Do not use agents or run automated tests; validate with an Android debug build and installation only.

---

### Task 1: Direct path navigation

**Files:**
- Modify: `app/src/main/java/com/j2team/fileserver/feature/browser/BrowserScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-en/strings.xml`

**Interfaces:**
- Consumes: `BrowserPath.normalize`, `BrowserPath.parent`, `SessionRepository.listWithPermissions`.
- Produces: a horizontally scrollable path and a path-entry dialog that opens directories or files.

- [ ] Add `pathDialogOpen`, `pathInput`, and `pathResolving` Compose state.
- [ ] Replace ellipsis with `horizontalScroll(rememberScrollState())` and open the dialog from `clickable`.
- [ ] Normalize input; handle `/`; otherwise list the parent and match `RemoteResource.path`.
- [ ] Set `path` for a directory, or set parent `path` plus `preview` for a file.
- [ ] Route failures to `mutationError` and add localized path-dialog strings.

### Task 2: Authenticated video thumbnail extraction

**Files:**
- Create: `app/src/main/java/com/j2team/fileserver/feature/preview/VideoThumbnailExtractor.kt`
- Modify: `app/src/main/java/com/j2team/fileserver/feature/browser/BrowserScreen.kt`

**Interfaces:**
- Consumes: `SessionRepository.streamingToken(profile)`, `SessionRepository.rawUrl(profile, path)`.
- Produces: `suspend fun extract(profile, item, sessionRepository, destination): Result<File>`.

- [ ] Use `MediaMetadataRetriever.setDataSource(rawUrl, mapOf("X-Auth" to token))`.
- [ ] Read `METADATA_KEY_DURATION`, convert 25% from milliseconds to microseconds, and request `OPTION_CLOSEST`.
- [ ] JPEG-compress the frame into the deterministic cache file and always release the retriever.
- [ ] In `ResourceVisual`, keep server thumbnails for images and call the extractor for videos.
- [ ] Record successful writes through `AppCacheManager`; delete invalid files and use the video icon on failure.

### Task 3: 100 MiB automatic thumbnail cache

**Files:**
- Modify: `app/src/main/java/com/j2team/fileserver/core/cache/AppCacheManager.kt`

**Interfaces:**
- Produces: size-only least-recently-used eviction at `100L * 1024L * 1024L`.

- [ ] Change `THUMBNAIL_MAX_BYTES` from 32 MiB to 100 MiB.
- [ ] Remove `THUMBNAIL_MAX_FILES` and its eviction condition.
- [ ] Preserve access-time updates, zero-byte cleanup, seven-day expiry, startup cleanup, and oldest-first deletion.

### Task 4: Theme-correct system bars

**Files:**
- Modify: `app/src/main/java/com/j2team/fileserver/MainActivity.kt`
- Modify: `app/src/main/java/com/j2team/fileserver/core/ui/FileServerTheme.kt`

**Interfaces:**
- Consumes: resolved `darkTheme` and `MaterialTheme.colorScheme.background`.
- Produces: full-window themed background and matching system-bar icons/scrims.

- [ ] Move `WindowInsets.safeDrawing` padding from the root `Surface` to an inner `Box` so background fills system-bar regions.
- [ ] In a `SideEffect`, call `Activity.enableEdgeToEdge` with `SystemBarStyle.dark` or `SystemBarStyle.light` using the current theme background.
- [ ] Keep content below safe insets and leave fullscreen-player system UI control intact.

### Task 5: Release, build, install, and publish

**Files:**
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Produces: debug APK version 1.3.0 and a synchronized `main` branch.

- [ ] Set `versionCode = 4` and `versionName = "1.3.0"`.
- [ ] Run `gradle assembleDebug --no-daemon`; expect `BUILD SUCCESSFUL`.
- [ ] Run `adb install -r app/build/outputs/apk/debug/app-debug.apk`; expect `Success`.
- [ ] Confirm installed `versionCode=4` and `versionName=1.3.0` with `dumpsys package`.
- [ ] Inspect `git diff --check` and stage only v1.3.0 files.
- [ ] Commit with `feat: release v1.3.0 navigation and thumbnails` and push `origin main`.

