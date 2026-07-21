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

