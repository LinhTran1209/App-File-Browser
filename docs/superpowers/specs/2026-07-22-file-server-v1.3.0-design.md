# File Server v1.3.0 Design

## Scope

Version 1.3.0 adds direct path navigation, locally generated video thumbnails, a 100 MB automatic thumbnail cache, and theme-correct system bars. README content is unchanged. No agents or automated tests are used at the user's request.

## Path navigation

- The current path remains on one line and becomes horizontally scrollable instead of ellipsized.
- Tapping the path opens a compact dialog prefilled with the current path.
- Input is normalized with `BrowserPath.normalize`.
- `/` opens the server root.
- For other paths, the app lists the parent directory and matches the target resource.
- A directory target becomes the current browser path; a file target opens the existing preview screen while the browser moves to its parent.
- Missing or inaccessible paths display the existing mutation-error row without losing the current location.

## Video thumbnails

- Image thumbnails keep using File Browser's thumbnail endpoint.
- Video thumbnails are generated locally with Android `MediaMetadataRetriever` using the authenticated raw URL and `X-Auth` header.
- The requested frame timestamp is exactly 25% of the reported video duration.
- The extracted bitmap is encoded as JPEG into the existing deterministic thumbnail cache file.
- Extraction failures delete empty cache artifacts and fall back to the existing video icon.

## Cache policy

- Thumbnail cache maximum is 100 MiB.
- The separate 256-file cap is removed so eviction is governed by total size.
- The existing seven-day expiry remains.
- On writes and application startup, the least recently accessed thumbnails are deleted first until the cache is at or below 100 MiB.
- Cache cleanup remains automatic and has no Settings control.

## System bars

- The root background is drawn edge-to-edge before safe-area padding is applied to content.
- Status/navigation bar icon appearance and scrims are synchronized with the selected Light, Dark, or System theme.
- Existing fullscreen video behavior remains unchanged.

## Release

- `versionCode = 4`
- `versionName = 1.3.0`
- Build the debug APK, install it on the connected device, commit the scoped changes, and push `main`.

