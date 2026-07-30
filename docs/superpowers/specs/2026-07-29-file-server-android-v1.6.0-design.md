# File Server Android v1.6.0 Design

## Goal

Synchronize the native Android client with the customized File Browser server
features already deployed on the Raspberry Pi while preserving Android's native
Media3 playback path for MPEG transport stream video.

## Compatibility

- Target the customized File Browser API currently served at port 8888.
- Keep Android 8.0 (API 26) as the minimum supported version.
- Keep the existing Compose UI, encrypted sessions, transfer queue and Media3
  player architecture.
- Set the application version to `1.6.0` and increment `versionCode`.
- Build on Windows and produce a debug APK for user acceptance testing.
- Do not install the APK and do not commit repository changes.

## User Administration

Extend `ServerUser` with:

- `quotaBytes`
- `quotaUsedBytes`
- `quotaRemainingBytes`
- `quotaUnlimited`
- `scopeMissing`

Opening an existing user must fetch `/api/users/{id}` instead of relying only
on the summary returned by `/api/users`. This ensures quota statistics and
missing-scope state are current.

The editor will replace free-text scope editing with a native server-folder
picker backed by:

- `GET /api/admin/directories?path=...`
- `POST /api/admin/directories`

The picker displays the current filesystem's total, used, free and selected
folder content bytes. An administrator can browse, select or create a folder.

The quota editor supports GB, TB and unlimited. A finite quota is invalid when
it is:

- non-positive;
- below the selected folder's existing content;
- above the selected filesystem's total capacity.

When the assigned folder has been deleted, the editor remains accessible,
shows a red warning, keeps user deletion available, and disables Save until a
valid folder is selected.

Creating a user sends an empty `which` array and `createUserDir: false` so the
explicitly selected folder is used. Updating a user includes `quotaBytes`.

## Upload Quota

Before enqueueing a file or folder upload, the app obtains the account usage
from `/api/usage` and compares the selected byte count with
`total - used`. Folder selection is measured recursively from Android's Storage
Access Framework metadata before remote directories or transfer records are
created.

If the selection does not fit, the app shows a localized quota warning and
does not start the upload. The server remains authoritative and server-side
quota errors are surfaced when concurrent activity changes available space
after preflight.

Unlimited accounts continue to use the physical filesystem usage returned by
the server, so the same preflight also prevents uploads larger than available
disk capacity.

## Owned-Folder Deletion

Before deleting selected resources, the app posts their paths to
`/api/resource-owners`. When the response contains usernames, the confirmation
dialog names those users and requires explicit confirmation. A response with
no owners uses the normal delete confirmation. Authorization and deletion
remain enforced by the server.

## Shared Video Thumbnails

Video thumbnails use the integrated File Browser endpoint:

- `GET /api/video-thumbnail?path=...` retrieves a ready shared thumbnail.
- A `404` causes one `POST` to queue generation.
- The app retries retrieval with bounded delays while the relevant row remains
  active.

The generated server cache is shared by every account that can access the same
video. Android keeps its existing bounded local thumbnail cache. The app no
longer requires the separate port 8890 thumbnail service at runtime.

Image thumbnails continue to use `/api/preview/thumb`.

## MPEG-TS Playback

The Android Media3 player and authenticated raw-resource stream remain
unchanged. The app does not use the browser's transmuxer, metadata endpoint or
video seek index. This preserves the app's existing fast TS startup and seeking
behavior.

## UI and Messages

The native user editor gains:

- server folder picker;
- disk capacity summary;
- quota amount, GB/TB unit and unlimited toggle;
- used and remaining quota summary;
- red validation and missing-folder warnings.

The browser gains localized preflight upload errors and owned-folder deletion
warnings. All new messages are supplied in Vietnamese and English.

## Error Handling

- Directory browsing failures remain in the picker and do not close the user
  editor.
- Only a successfully loaded selected path makes the user form valid.
- Thumbnail failures fall back to the existing video icon and never block
  browsing or playback.
- Upload preflight failures do not create transfer tasks.
- Server validation messages are preserved where possible and otherwise mapped
  to concise localized messages.

## Verification

- Unit-test user quota JSON parsing and request encoding.
- Unit-test quota validation and upload preflight policies.
- Unit-test directory and owner response parsing.
- Unit-test integrated video-thumbnail request behavior.
- Run the complete Android JVM unit-test suite.
- Run Android lint/compile checks supported by the local toolchain.
- Build the v1.6.0 debug APK on Windows and report its path and SHA-256.
