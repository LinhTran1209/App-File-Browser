# Video Thumbnail On-Play Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove remote thumbnail extraction from browser lists and cache a lightweight thumbnail only after a video successfully renders.

**Architecture:** `BrowserScreen` becomes cache-only for video thumbnails while retaining the existing image thumbnail API. `MediaPreview` renders video through a `TextureView`, captures the first rendered frame once, and delegates scaling/compression to a focused cache writer.

**Tech Stack:** Kotlin, Jetpack Compose, Media3 ExoPlayer, Android `TextureView`, existing `AppCacheManager`.

## Global Constraints

- Keep version name `1.3.0` and version code `4`.
- Do not modify `README.md`.
- Do not run automated tests; verify with a clean debug build and ADB installation.
- Do not use subagents.

---

### Task 1: Make video thumbnails cache-only in the browser

**Files:**
- Modify: `app/src/main/java/com/j2team/fileserver/feature/browser/BrowserScreen.kt`
- Delete: `app/src/main/java/com/j2team/fileserver/feature/preview/VideoThumbnailExtractor.kt`

- [ ] Remove all `MediaMetadataRetriever` calls and imports.
- [ ] Decode an existing video thumbnail from cache when available.
- [ ] Show the video icon immediately when no cached thumbnail exists.
- [ ] Preserve current image thumbnail fetching and caching.

### Task 2: Capture the first rendered playback frame

**Files:**
- Create: `app/src/main/java/com/j2team/fileserver/feature/preview/VideoThumbnailCache.kt`
- Modify: `app/src/main/java/com/j2team/fileserver/feature/preview/MediaPreview.kt`

- [ ] Render video with a `TextureView` attached to the current ExoPlayer.
- [ ] Observe `Player.Listener.onRenderedFirstFrame` and capture one bitmap only.
- [ ] Scale the bitmap to a maximum 320-pixel edge and write JPEG quality 75.
- [ ] Record the write through `AppCacheManager` so the 100 MB LRU limit remains enforced.
- [ ] Treat all capture/cache errors as non-fatal playback-side effects.

### Task 3: Build, install, and publish

**Files:**
- Verify: `app/build.gradle.kts`

- [ ] Confirm version remains `1.3.0` / `4` and `README.md` is unchanged.
- [ ] Run `:app:assembleDebug` and require `BUILD SUCCESSFUL`.
- [ ] Install with `adb install -r` and confirm package version `1.3.0` / `4`.
- [ ] Commit the scoped changes and push `main` to `origin`.
