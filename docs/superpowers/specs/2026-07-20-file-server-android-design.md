# File Server Android App — Design Specification

**Date:** 2026-07-20  
**Status:** Approved for detailed planning  
**Product name:** File Server  
**Target:** Android APK for phones  
**Server compatibility:** Official [filebrowser/filebrowser](https://github.com/filebrowser/filebrowser)

## 1. Product Goal

Build a fast, native Android client that connects to one or more File Browser
servers, including Raspberry Pi 3 installations. Users can browse and preview
files and can create, upload, edit, rename, move, copy, download, share, or
delete content only when the authenticated File Browser user has the matching
permission.

The first release prioritizes responsiveness, predictable deployment, and safe
handling of credentials and large files over feature parity with every
administrative function in the File Browser web interface.

## 2. Scope

### Included in the first production release

- Add, edit, delete, reorder, and connect to multiple servers.
- Server fields: optional display name, scheme, host/IP, port, and optional
  base path.
- Generate a display name from the host and port when the name is empty.
- Test connectivity before saving.
- Detect whether the server allows no-auth access or requires authentication.
- Authenticate with username and password when required.
- Store secrets with Android Keystore-backed encryption.
- Browse folders in grid and list layouts.
- Search, sort, refresh, multi-select, and navigate with breadcrumbs.
- Preview images, video, audio, text/code, and PDF.
- Open unsupported formats in a compatible Android application after download.
- Create folders and upload, rename, edit, move, copy, download, share, and
  delete files when permitted by the server.
- Background upload/download with progress, cancellation, retry, and Android
  notifications.
- Light and dark themes, Vietnamese as the default language, and English-ready
  string resources.
- Clear handling of offline servers, expired sessions, permission failures,
  invalid certificates, and interrupted transfers.

### Explicitly deferred

- File Browser global administration, user administration, and server
  configuration.
- Synchronizing whole folders for offline use.
- Automatic LAN discovery.
- iOS and desktop clients.
- Editing Office documents inside the app.
- Media transcoding on the phone or Raspberry Pi.

## 3. Chosen Technical Direction

Use a native Android application written in Kotlin with Jetpack Compose.
Communicate directly with the File Browser HTTP API. Use an Android system
viewer only for formats that the app cannot safely preview, and do not use a
WebView as the primary file browser.

This approach is selected over a WebView wrapper because it provides controlled
pagination, thumbnail loading, media streaming, background transfers, secure
multi-server credentials, and consistent permission-aware actions.

### Proposed stack

- Kotlin and Coroutines
- Jetpack Compose with Material 3
- Navigation Compose
- OkHttp for HTTP, streaming, and interceptors
- Kotlinx Serialization for API models
- Room for server profiles, cached directory metadata, and transfer records
- Android Keystore-backed encrypted secret storage
- Coil for image and thumbnail loading
- AndroidX Media3 for audio and video
- WorkManager for durable background transfers
- Hilt for dependency injection
- JUnit, Turbine, MockWebServer, Compose UI Test, and Android instrumented tests

The implementation plan will pin exact stable dependency versions after the
Android toolchain available in the workspace has been inspected.

## 4. System Boundaries

### Server Profiles

Owns non-secret connection metadata, ordering, recent usage, and the preferred
layout for each server. A normalized endpoint is composed from:

`scheme://host:port/basePath`

Host input accepts IPv4, IPv6, local DNS names, and public domain names. HTTP is
allowed for private networks because Raspberry Pi deployments commonly use it,
but the app warns before credentials are sent over cleartext. HTTPS remains the
recommended default for remote access.

### Secret Store

Owns username, encrypted password when the user enables “Remember password,”
and session token data. Secrets are never written to logs, Room tables,
analytics, screenshots, or crash messages.

### File Browser API Adapter

Owns endpoint construction, authentication, token injection, compatibility
handling, permission parsing, resource listing, file operations, streaming,
and consistent error mapping. UI and storage code do not depend on raw HTTP
responses.

Each server has an isolated client/session. Switching servers cannot reuse a
token, cookie, cache key, or request queue from another server.

### Browser Domain

Owns navigation history, folder results, sorting, search, selection, and
permission-aware commands. It exposes immutable UI state so screen rendering
remains independent from HTTP implementation details.

### Preview Domain

Selects a previewer from MIME type, extension, server metadata, and file size:

- Image: Coil with zoomable full-screen paging.
- Video/audio: Media3 with authenticated streaming requests.
- Text/code: streamed text with a configurable preview-size ceiling; full edit
  is enabled only for safe text types and users with modify permission.
- PDF: Android PDF rendering with page virtualization.
- Unknown/binary: metadata screen with download and “Open with” actions.

### Transfer Domain

Owns WorkManager jobs, progress persistence, cancellation, retry rules,
notification channels, collision handling, and Android Storage Access Framework
integration. Transfers stream bytes and never load a whole large file into
memory.

## 5. Connection and Authentication Flow

1. Normalize and validate the endpoint locally.
2. Perform a short-timeout health/compatibility request.
3. Read public server settings or probe a safe API route to determine the
   authentication behavior supported by that File Browser version.
4. If the server permits no-auth access, obtain user capabilities and open the
   root scope.
5. If credentials are required, show the login screen and submit them to the
   official login endpoint.
6. Store the returned session only inside the selected server profile.
7. Fetch the current user record and translate File Browser permissions into
   app capabilities.
8. On an expired or rejected session, pause the original action, request login,
   and retry it once after successful authentication.

Proxy and hook authentication are treated as server compatibility cases. When
the normal API login cannot complete but the server exposes a browser-based
identity flow, the first release displays an actionable unsupported-auth
message instead of silently opening an insecure WebView session.

## 6. Permission Model

The app reads the server-provided user permissions and evaluates them before an
action is rendered and again before it is executed.

| Capability | App behavior |
| --- | --- |
| Create | Create folder and upload actions |
| Rename | Rename action |
| Modify | Text editing and overwrite actions |
| Delete | Delete action with confirmation |
| Share | Create/manage share action |
| Download | Download, offline open, and external-open actions |
| Execute | Not exposed in the first release |
| Admin | Does not unlock deferred server administration screens |

A hidden action improves clarity, but server authorization remains the source
of truth. HTTP 401, 403, and permission changes are handled explicitly.

## 7. Information Architecture and Screens

### 7.1 Server List

- App bar: “File Server,” search when the list grows, theme/settings action.
- Server cards: generated or custom name, endpoint, connection status,
  authentication indicator, last-opened time, and overflow actions.
- Primary floating action: add server.
- Empty state explains IP, port, and where to find the File Browser address.

### 7.2 Add/Edit Server

- Name (optional)
- Scheme selector: HTTP or HTTPS
- Server/IP
- Port
- Advanced base path
- Test connection button with latency and detected server information
- Save button

Editing a server that changes its endpoint clears its active session and cached
directory entries after confirmation. Deleting a server removes its local
metadata, secrets, cache, and pending transfers but never changes the remote
server.

### 7.3 Login

- Server identity and endpoint
- Username and password
- Show/hide password
- Remember password
- Login
- Clear errors for wrong credentials, unreachable server, unsupported auth,
  TLS failure, and insecure HTTP

### 7.4 File Browser

- Server name and connection state
- Breadcrumb path
- Search and overflow menu
- List/grid toggle and sort sheet
- Pull-to-refresh
- File and folder rows/cards with type icon or thumbnail, name, size, and
  modified time
- Long press enters multi-select mode
- Permission-aware action bar and create/upload floating action

The bottom navigation is intentionally omitted. Server switching occurs through
the app bar because the core hierarchy is Servers → Folder → Preview, and a
persistent bottom bar would consume space without representing peer sections.

### 7.5 Preview

- Edge-to-edge content
- Top bar with filename and close/back
- Type-specific controls
- Actions: download, share, open with, details, and edit when applicable
- Swipe between adjacent previewable files without downloading the folder

### 7.6 Transfers

- Active, completed, and failed groups
- Per-item progress, speed, remaining bytes, cancel, retry, and open
- Aggregate notification for background work

### 7.7 Settings

- Theme: system, light, dark
- Default layout and sort
- Wi-Fi-only transfer option
- Cache limit and clear cache
- Default text preview size ceiling
- Security explanation for HTTP and stored credentials
- App and File Browser compatibility information

## 8. Visual Design Direction

Use Material 3 foundations with a restrained “private cloud” character:

- Primary color: deep indigo-blue for trust and infrastructure.
- Accent: cyan for active transfers and connectivity.
- Neutral surfaces with strong file-name contrast.
- Rounded 16 dp cards and 12 dp controls.
- Minimum 48 dp touch targets.
- 8 dp spacing grid.
- File types are differentiated by icon and label, never by color alone.
- Motion is short and functional: 150–250 ms transitions, shared-axis folder
  navigation, and subtle progress animation.
- Phone-first frames target 360 × 800 dp while remaining adaptive at 320–600 dp.

The Figma file will include foundations, reusable components, light/dark
variants, core user flows, empty/loading/error states, and a clickable
prototype path from server creation to media preview.

## 9. Performance Requirements

- Show cached server and folder metadata immediately when available.
- Begin rendering the first folder items without waiting for all thumbnails.
- Cancel thumbnail and listing requests when their screen leaves composition.
- Use stable list keys and immutable state to avoid unnecessary recomposition.
- Stream media and transfers with bounded buffers.
- Virtualize PDF pages and large directory lists.
- Cache thumbnails by server ID, canonical path, modification time, and size.
- Never retain a decoded full-resolution image when it is not visible.
- Apply connection, read, and write timeouts appropriate to local Raspberry Pi
  networks; transfer reads use a separate streaming policy.
- Avoid parallel request bursts that can overwhelm a Raspberry Pi 3.

Initial performance acceptance targets on the connected test phone and a
Raspberry Pi 3 are:

- Warm server list rendering: under 300 ms.
- Cached folder first content: under 300 ms.
- Uncached local-network folder first content: under 1.5 seconds for 500 items,
  excluding server-side search or thumbnail generation.
- Smooth browser scrolling without sustained dropped-frame warnings.
- Memory remains bounded during multi-gigabyte downloads and video streaming.

## 10. Error and Offline Behavior

All low-level errors map to user-facing categories:

- Server unreachable or DNS failure
- Timeout
- Invalid credentials or expired session
- Insufficient permission
- Missing/moved file
- Conflict with an existing name
- Storage space unavailable
- TLS certificate problem
- Insecure HTTP warning
- Unsupported server/auth version
- Transfer interrupted
- Unknown server error with a safe diagnostic code

Read-only cached folder metadata may remain visible offline with a clear offline
banner. Mutating operations are not queued offline in the first release because
remote file conflicts cannot be resolved safely without a synchronization
model.

## 11. Security Requirements

- Use HTTPS for remote servers and warn before sending credentials over HTTP.
- Use Android Network Security Configuration to limit cleartext behavior to
  user-created server connections; do not globally trust arbitrary certificates.
- Do not offer a “disable TLS validation” switch.
- Redact credentials, tokens, cookies, query secrets, and sensitive headers.
- Reject malformed endpoints and path traversal attempts before requests.
- Encode every remote path segment correctly.
- Respect Android scoped storage.
- Require explicit confirmation for destructive multi-file deletion.
- Rate-limit repeated automatic login retries.
- Target a maintained File Browser release; production validation begins with
  v2.63.x and includes compatibility tests for the oldest supported release
  selected during implementation planning.

## 12. Testing Strategy

### Unit tests

- Endpoint normalization and validation
- Generated server names
- Authentication state transitions
- Permission-to-action mapping
- Path encoding
- MIME/previewer selection
- Sorting and selection state
- Error mapping and retry policy

### API contract tests

Use MockWebServer fixtures for authenticated, no-auth, expired-token,
permission-denied, conflict, partial stream, and compatibility responses.
Secrets must be absent from recorded request diagnostics.

### UI tests

- Add, edit, connect, and delete server
- Login success and failure
- Browse and switch layout
- Permission-aware action visibility
- Preview each supported family
- Upload/download progress and cancellation
- Empty, loading, offline, and error states
- Light/dark themes and large font scale

### Device and integration tests

- Connected Android phone through `C:\platform-tools\adb.exe`
- Raspberry Pi 3 File Browser over local Wi-Fi
- HTTP local server and HTTPS remote/reverse-proxy server
- Large folder, large video, interrupted Wi-Fi, app backgrounding, process
  recreation, low storage, and token expiry

ADB authorization must be accepted on the phone before installation and
instrumented testing.

## 13. Delivery and Git Policy

Each independently testable feature is delivered through this cycle:

1. Add a failing automated test.
2. Implement the smallest complete behavior.
3. Run focused tests and the relevant regression suite.
4. Build/install the debug APK when the change affects device behavior.
5. Commit only that feature with a conventional commit message.
6. Push the commit immediately to the configured remote branch.

No unrelated changes are combined into a feature commit. A push is never
reported as successful without confirming the remote commit SHA.

The current workspace has no Git repository, no Git executable on `PATH`, and
the connected GitHub app currently exposes no writable repository. Git delivery
therefore requires a repository URL/permission and an available Git client
before the first implementation feature can be completed.

## 14. Delivery Phases

1. Android project foundation and CI-quality test harness.
2. Encrypted multi-server profiles and connection test.
3. Authentication and isolated sessions.
4. Permission-aware folder browser.
5. Native previews.
6. File mutations and multi-selection.
7. Durable background transfers.
8. Settings, accessibility, performance hardening, and compatibility.
9. Signed APK build, ADB acceptance testing, and release documentation.

Each phase is split into independently testable feature commits in the
implementation plan.

