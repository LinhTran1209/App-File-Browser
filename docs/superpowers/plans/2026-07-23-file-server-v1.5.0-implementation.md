# File Server Android v1.5.0 Implementation Plan

## Task 1: Lock API contracts with tests

- Add representative File Browser v2.63.x JSON fixtures.
- Add failing tests for user, settings and share parsing.
- Add failing tests for permission-based destination visibility.
- Implement the settings domain models and parser helpers.

## Task 2: Add authenticated settings transport

- Add failing request-construction and response-handling tests.
- Extend `FileBrowserClient` with settings, users and share-management methods.
- Extend `SessionRepository` with token-renewing wrappers.
- Preserve full global-settings JSON during updates.

## Task 3: Add native server settings UI

- Add the server Settings destination and navigation.
- Add Profile, Shares, Global and Users screens.
- Gate destinations and actions by the authenticated user's permissions.
- Add loading, error, validation and confirmation states.

## Task 4: Fix existing UI regressions

- Compact the server Delete popup.
- Rebuild the Share dialog with bounded dimensions.
- Reset list/grid scroll position after name-sort changes.
- Add targeted tests for pure layout/state helpers where practical.

## Task 5: Version, verify and publish

- Set version name to 1.5.0 and increment the version code.
- Run unit tests and a debug APK build.
- Inspect the final diff and APK location.
- Commit only v1.5.0 files, excluding the user's existing `.gitignore` change.
- Push the current `main` branch to origin.

