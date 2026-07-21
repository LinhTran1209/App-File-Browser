# File Server Android Enhancements Design

**Date:** 2026-07-20  
**Status:** Approved design, pending specification review  
**Product:** File Server (`com.j2team.fileserver`)

## Goal

Upgrade the native Android File Server client into a durable daily-use file
manager. The release must preserve authenticated sessions across app restarts,
provide safe file operations and reliable transfers, support Vietnamese and
English, and preview common document, source-code, image, audio, and video
formats while remaining suitable for a Raspberry Pi 3 server.

## Scope Decomposition

The work is delivered as three ordered subsystems in one continuous release:

1. **Identity and preferences:** encrypted credentials, automatic token renewal,
   language, theme, download directory, and folder icon set.
2. **File operations and transfers:** reliable upload/download, transfer badge
   and tabs, folder upload, folder creation, selection mode, and permission-aware
   deletion.
3. **Preview and media:** scrollable text/code, PDF, image, audio, video, and
   external-app fallback.

Each subsystem must be independently testable and end with a focused commit and
confirmed GitHub update.

## Architecture

`MainActivity` becomes a thin host. Feature screens and state move into focused
packages:

- `core/session`: encrypted credential and token persistence, automatic login,
  and session invalidation.
- `core/network`: File Browser API adapter for resources, permissions, upload,
  download, create directory, delete, and raw streaming URLs.
- `feature/browser`: browser state, selection state, contextual actions, and
  list/grid rendering.
- `feature/transfers`: durable upload/download queue and UI.
- `feature/preview`: preview routing and native preview implementations.
- `feature/settings`: durable preferences and Storage Access Framework directory
  selection.

UI state never stores raw passwords. Network requests receive a short-lived
session object from `SessionRepository`. Large files use bounded streaming
buffers and never load completely into memory.

## Session and Credential Persistence

- Credentials are encrypted with a non-exportable AES-256 key in
  `AndroidKeyStore`.
- Every encrypted record uses a random 12-byte IV and profile ID as associated
  data.
- The app persists the current File Browser token separately from the encrypted
  credential record.
- Opening a saved server attempts the stored token first.
- HTTP 401/403 triggers exactly one silent re-login with the encrypted
  credentials, then retries the original request once.
- If silent login fails because the password changed or the account was removed,
  the app returns to the login screen with a clear message.
- Deleting a server removes its token and encrypted credentials.
- Android app-data removal or uninstall removes all session material.
- Passwords and tokens never appear in logs, exception strings, transfer
  records, screenshots, or analytics.

## Settings

Settings contains:

- Language: Vietnamese and English. Vietnamese is selected on first launch.
  Changing language updates the current activity without clearing navigation or
  sessions.
- Theme: System, Light, and Dark.
- Download directory: selected through
  `ActivityResultContracts.OpenDocumentTree`. The app requests and persists URI
  read/write permission. If permission becomes invalid, downloads pause and the
  user is asked to select a directory again.
- Show hidden files.
- Default browser view: list or grid.
- Folder icon set: Default, Outline, and Color.

All user-visible text is stored in `values/strings.xml` and
`values-en/strings.xml`.

## Icons and Visual Design

- Replace the malformed settings asset with a centered 24 dp vector inside a
  48 dp touch target.
- Password fields include a 24 dp eye/eye-off trailing action with an accessible
  Vietnamese or English description.
- Upload and transfer actions use 28 dp glyphs in at least 48 dp touch targets.
- The transfer action displays a badge from 1 through 99; values above 99 show
  `99+`. The badge counts queued, running, paused, and failed tasks requiring
  attention.
- App and launcher icons are produced after functional flows stabilize. The
  mark combines a folder and small server rack and remains legible from 48 px
  through adaptive-icon launcher sizes.

## Transfer Center

The transfer screen has two tabs:

- **Tải xuống / Downloads**
- **Tải lên / Uploads**

Every row shows filename, destination/source, bytes transferred, total bytes,
progress, state, and an error action where applicable. Completed tasks remain
until dismissed. Failed tasks can retry. Active counts update the transfer
badge immediately.

### Upload

- Single-file upload uses Android's document picker.
- Folder upload uses `OpenDocumentTree`, recursively enumerates readable files,
  creates missing server directories, and queues each file.
- Multipart requests match the File Browser server API and encode remote path
  segments once.
- A maximum of two file transfers run concurrently on Raspberry Pi 3.
- Upload failure preserves the task and error reason and never produces a false
  completed state.

### Download

- Downloads write through the persisted SAF tree URI.
- Partial files use a temporary suffix and are renamed only after a successful
  response.
- Existing filenames prompt to replace, keep both, or cancel.

## Browser Interactions

- A deliberate blank action strip remains below the breadcrumb/sort row. A long
  press on it opens actions for **New folder**, **Upload files**, and
  **Upload folder**.
- Long-pressing a file or folder enters multi-select mode. Tapping additional
  items toggles selection.
- The contextual app bar displays the selection count and only actions supported
  by every selected resource.
- Delete requires a confirmation dialog containing the number of selected
  resources.
- The client uses permissions returned by File Browser when available.
  Unsupported or denied delete actions are disabled in the UI.
- The server remains authoritative: a delete rejected by the server is reported
  and no local list item is removed.
- Successful create, upload, or delete refreshes only the affected directory.

## Preview Support

### Text and Source Code

Scrollable UTF-8 preview supports at least:

`txt`, `md`, `markdown`, `py`, `pyw`, `json`, `xml`, `yaml`, `yml`, `toml`,
`ini`, `conf`, `cfg`, `log`, `csv`, `tsv`, `kt`, `kts`, `java`, `c`, `h`,
`cpp`, `hpp`, `cs`, `js`, `jsx`, `ts`, `tsx`, `html`, `htm`, `css`, `scss`,
`sql`, `sh`, `bash`, `zsh`, `ps1`, `bat`, `gradle`, `properties`, `env`,
`dockerfile`, and files without an extension that are detected as text.

Text is read through a bounded stream. Files above the inline-preview limit show
an explicit **Open anyway** action and use incremental paging rather than one
large string.

### PDF and Images

- PDF pages render lazily with Android `PdfRenderer`; page bitmaps are recycled.
- Images support JPEG, PNG, GIF, WebP, BMP, HEIF/HEIC, and formats supported by
  the device decoder.
- Large images are decoded with a sampled size appropriate for the screen.

### Video and Audio

Media3/ExoPlayer handles authenticated streaming with bounded buffering.
Recognized video extensions include:

`mp4`, `m4v`, `mkv`, `mov`, `webm`, `wmv`, `avi`, `m2ts`, `mts`, `ts`, `flv`,
`3gp`, `3g2`, `mpeg`, `mpg`, `vob`, and `ogv`.

Recognized audio extensions include:

`mp3`, `m4a`, `aac`, `flac`, `wav`, `ogg`, `opus`, `wma`, `amr`, `aiff`,
`aif`, and `mka`.

Extension recognition does not guarantee codec support. Unsupported codecs show
the technical reason and an **Open with another app** action. Opening a directory
containing many videos loads metadata and thumbnails by page and never
prebuffers every file.

## Error Handling

- Network errors distinguish timeout, authentication, permission denial,
  missing resource, unsupported format, invalid download directory, and server
  error.
- Authentication retry is limited to one attempt per request to avoid loops.
- Transfer errors remain attached to the durable task.
- UI operations use optimistic state only for progress, never for destructive
  resource changes.
- All failures offer a safe retry or navigation path.

## Testing

### Unit tests

- Keystore payload round-trip and tamper rejection.
- Session token reuse, single silent re-login, and credential removal.
- Language, theme, directory URI, and icon-set persistence.
- Badge count and transfer tab filtering.
- Multipart body/path behavior and upload failure state.
- Browser selection and permission intersection.
- Delete confirmation and rejected-delete list preservation.
- Preview routing for every supported extension group.
- Bounded text paging and parent/child path behavior.

### Instrumentation tests

- Password eye toggle never exposes the password through accessibility when
  hidden.
- Reopening a server after activity/process restart skips the login screen.
- Language and theme changes update visible UI.
- SAF directory selection is persisted.
- Long-press blank strip and long-press resource selection flows.
- Transfer tabs, badge, and error retry.
- Scroll a long text preview and render at least two PDF pages.

### Device and server verification

On the connected Xperia and Raspberry Pi server:

1. Login once, force-stop/reopen, and confirm direct browser entry.
2. Upload a temporary file and folder, verify contents, then delete them through
   the app.
3. Download a file into the selected SAF directory and open it.
4. Preview text, Markdown, Python, PDF, image, audio, and representative video
   files.
5. Attempt a denied delete and confirm the app leaves the resource visible.
6. Verify transfer concurrency never exceeds two.

## Acceptance Criteria

- No malformed, placeholder, or undersized action icons remain.
- Session resumes without credential entry until server credentials become
  invalid, the server profile is deleted, app data is cleared, or the app is
  uninstalled.
- Settings changes persist across process restart.
- Upload and download complete against the configured Raspberry Pi server and
  report failures accurately.
- File creation and deletion obey server permissions.
- Long documents scroll to their end.
- Common image, PDF, audio, and video formats preview natively where the device
  codec supports them and fall back safely otherwise.
- Unit tests, lint, instrumentation tests, APK build, install, and device smoke
  tests all pass before completion is reported.
