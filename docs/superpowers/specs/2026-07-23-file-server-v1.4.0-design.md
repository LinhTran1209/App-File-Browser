# File Server v1.4.0 Design

**Date:** 2026-07-23
**Target:** Android native client for File Browser v2.63.18
**Release:** `versionCode = 6`, `versionName = 1.4.0`

## Scope

This release improves browser clarity and adds native File Browser share-link
management without changing the Raspberry Pi services.

## Settings and hidden files

- Remove the "Show hidden files" control from Settings.
- Remove the setting from active application state and persistence.
- Browser listings always hide resources whose names begin with `.`.
- Existing persisted settings remain readable during migration.

## Dialog fields

Path entry, folder creation, and rename dialogs use explicit Material 3
outlined-field colors.

- Focused border: theme primary color.
- Unfocused border: visible `onSurfaceVariant` color.
- Error border: theme error color.
- The treatment must remain readable in Light, Dark, and System themes.

## Selection toolbar

- Replace localized text such as "1 selected" with the numeric count only.
- Keep rename, download, move, and delete behavior unchanged.
- Add a share action.
- Share is enabled only when exactly one file or directory is selected.

## Share links

The app uses the official File Browser v2.63.18 API:

- `GET /api/shares` lists existing shares.
- `POST /api/share{path}` creates a share.
- `DELETE /api/share/{hash}` removes a share.

The share dialog contains:

- Positive whole-number duration.
- Unit: seconds, minutes, hours, or days.
- Optional password.
- A create action.
- Existing shares for the selected resource, showing identifier and expiry.
- Copy-link and delete actions.

The public link is derived from the configured server base URL and returned
share hash. Authentication uses the existing session repository. Errors remain
inside the dialog and do not clear the current browser selection.

## Disk usage

Replace the browser heading "List/Danh sách" with drive usage for the current
path.

- Call `GET /api/usage{path}` through the authenticated session.
- Display used and total space with binary units.
- Display a compact progress indicator.
- Refresh usage when the path changes and during pull-to-refresh.
- If the server cannot provide usage, omit the progress bar and show a neutral
  unavailable label without blocking file listing.

## Compatibility and delivery

- No Raspberry Pi service changes are required.
- Existing File Browser operations and thumbnail behavior remain unchanged.
- Preserve unrelated working-tree changes, especially `.gitignore`.
- Produce a debug APK, commit the v1.4.0 implementation, and push `main`.
- Do not install the APK. Functional device testing is left to the user.
