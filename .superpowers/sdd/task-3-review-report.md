# Task 3 independent re-review

## Verdicts

- **Spec compliance: APPROVED.** No Critical or Important finding remains.
- **Code quality / security: APPROVED.** No Critical or Important finding remains.

## Closure verification

| Prior finding | Verification |
| --- | --- |
| Official File Browser permissions were not mapped | Closed. `FileBrowserClient.kt:153-169` derives the positive user id from the Base64URL JWT payload, requests the authenticated self-user resource at `/api/users/{id}`, and maps only `perm.download`, `perm.create`, and `perm.delete`. Malformed/missing token or `perm` values deny access. `SessionRepository.kt:58-69` applies those capabilities to both the directory and every listed resource. |
| Reconciliation erased a delete rejection | Closed. `BrowserScreen.kt:92-117` keeps list and mutation errors separate; delete failures write `mutationError` at line 246, while refresh only clears `listError` at line 115. The mutation error remains visible until its explicit dismiss action at lines 319-322. |
| List paths were not exactly-once encoded | Closed. Listing now goes through the same `apiUrl` encoder (`FileBrowserClient.kt:172-173,240-247`) as raw/download, upload, create, and delete. `FileMutationRequestTest` covers spaces, a literal percent, and `#` in multiple segments. |
| Uploads were only display-gated | Closed. Both picker callbacks recheck current directory state (`BrowserScreen.kt:157-169`); file upload rechecks the live server capabilities immediately before creating a task/sending it (`123-150`); tree upload rechecks before the root, every child directory, and every file mutation (`395-429`). Rejection is surfaced as the localized not-permitted error. |
| Blank strip was inaccessible/inert | Closed. The 48 dp strip has a content description plus explicit click/long-press labels and both gestures open the menu (`BrowserScreen.kt:284-291`). |
| Folder name accepted separator/traversal-like input | Closed. `BrowserPath.kt:10-13` rejects dot segments and both slash styles; `BrowserPathTest` exercises `../other`, `a/b`, and `a\\b`. |

## Additional checks

- Default-deny permissions and selected-resource action intersection remain intact (`RemoteResource.kt:11-17`, `SelectionPolicy.kt:12-15`).
- Deletes remain sequential, stop on the first rejected resource, do not retry mutation requests, and reconcile the server list while retaining the still-listed selection.
- Session renewal remains safe for mutations: authentication is renewed using a read before each mutation is issued once (`SessionRepository.kt:97-100`).
- SAF cursors/streams use `use`, and temporary tree-upload files are removed in `finally` (`BrowserScreen.kt:412-435`).

## Verification note

I attempted the focused unit-test suite for `FileMutationRequestTest`, `BrowserPathTest`, and `SelectionPolicyTest`. It could not start in this environment because `JAVA_HOME` is invalid and no `java` executable is on `PATH`; this is an environment limitation, not a reported product failure. The implementation report records a passing full Gradle verification.
