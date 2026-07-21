# Task 3 implementation report

## Delivered

- Added a stable, default-deny `ResourcePermissions` model and response parsing for item and response-level permission objects.
- Added encoded create-directory and sequential delete requests. Delete stops at the first rejected path.
- Added session-aware mutation wrappers that renew through a safe list request, then send each mutation only once.
- Added `BrowserScreen` with a 48 dp blank long-press action strip, permission-gated folder/file/tree upload actions, selection mode, action intersections, and confirmation-gated deletion without optimistic removal.
- Preserved SAF download copying and the existing preview path.

## Review corrections

- File Browser v2 capability source is the authenticated self-user endpoint `GET /api/users/{id}`. The id comes from the server-issued JWT `user.id`; its response uses `perm.download`, `perm.create`, and `perm.delete`. Upload and create both map to `perm.create`, matching File Browser's own frontend. Malformed or absent fields remain denied.
- Directory listing URLs now use the shared segment-by-segment encoder. Upload permissions are rechecked after picker return and immediately before each tree/file mutation.
- Listing and mutation errors are independent, so a reconciliation refresh cannot erase a delete rejection. The blank action strip now has localized accessibility labels and a non-inert click action.
- New-folder input now accepts exactly one non-traversal path segment.

## Test evidence

- RED: focused tests initially failed because permission and mutation types did not exist.
- GREEN: `testDebugUnitTest --tests "*SelectionPolicyTest" --tests "*FileMutationRequestTest"` passed.
- Full: `testDebugUnitTest lintDebug assembleDebug` passed.

## Notes

- Missing server permission fields are denied by default; destructive controls therefore remain unavailable until the server explicitly grants them.
- Delete failures retain the remaining selection, reconcile the list from the server (including any earlier successful deletes), and display the server error after its confirmation dialog closes.
