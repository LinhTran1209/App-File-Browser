# Task 4 implementation report

Status: implemented and verified.

Implementation commit: `3e12c54` (`feat: deliver reliable transfer center`).

Delivered:

- Streaming multipart encoder with one escaped `file` part, fixed terminal boundary, fixed-length request body, and non-2xx failure propagation.
- Durable transfer queue observation, direction tabs, capped attention badge, retry/dismiss controls, and browser toolbar badge updates.
- `TransferCoordinator` with `Semaphore(2)`, per-file durable folder upload tasks, directory-first recursion, execution-time permission checks, and one-shot upload session behavior.
- SAF download flow that writes a `.<name>.part` document, copies it to the final document in 8 KiB chunks only after a successful network stream, and exposes replace/keep-both/cancel conflicts.
- Retry ownership is tied to the originating server profile so a queued task cannot be sent to another selected server.

Verification:

- RED: focused tests initially failed with unresolved policy and multipart APIs.
- GREEN: `testDebugUnitTest --tests "*TransferPolicyTest" --tests "*MultipartEncoderTest"` passed.
- Final: `testDebugUnitTest lintDebug assembleDebug` passed (23.7s).
- `git diff --check` passed.

Concerns:

- Existing durable tasks created by earlier app versions lack a profile owner and are deliberately not retryable; users can dismiss and enqueue them again.

Review follow-up:

- Process-owned application store/session and a single `TransferRuntime` scope/semaphore now survive Activity/profile changes; recovered active tasks fail with `transfer_interrupted_after_restart` and never auto-replay.
- Dismiss updates persistence and the observable queue atomically. SAF Replace preserves the existing document until `.part` staging completes, using a restore-on-finalization-failure backup rename.
- Folder traversal creates the complete directory subtree before file tasks are enqueued. Focused reliability tests and final `testDebugUnitTest lintDebug assembleDebug` passed.

Second review follow-up:

- Implementation commit: `9c0b43c` (`fix: close transfer race conditions`).
- RED/GREEN: `TransferReliabilityTest` was extended with paused-task restart recovery; the focused `testDebugUnitTest --tests "*TransferReliabilityTest"` passed after the recovery/state-transition changes. The final `testDebugUnitTest lintDebug assembleDebug` and `git diff --check` also passed.
- Full-tree folder planning now performs the complete recursive directory-create pass while collecting file entries, and only enqueues files after all directory creates succeed. This closes the sibling-subtree ordering finding.
- Retry now atomically changes only Failed/Cancelled tasks to Queued; a worker starts only when the durable task is still Queued. This closes the retry-then-dismiss stale captured-task resurrection path (the UI has no dismiss action for Queued/Running tasks).
- Paused tasks now recover to Failed with the stable interruption error, making restart-interrupted work retryable/dismissible without automatic replay.
- Replace backups remain task-unique (`task.id`) and are only made after network staging. The restore path no longer treats the backup as a normal final until finalization has completed.

Remaining concerns:

- SAF provider operations are still exercised through `DocumentFile` directly; a provider-operations abstraction with failure-injection tests for every rename/delete/restore partial failure was not added in `9c0b43c`. Providers that fail restore still preserve the backup name as the recovery artifact, but the UI does not yet surface a dedicated recovery-path affordance.
- `SessionRepository`'s mutable in-memory token map was not changed to a concurrent map/mutex in this follow-up. The process-wide transfer runtime can invoke it concurrently, so this remains a follow-up hardening item.

Final SAF follow-up: `4d74280` adds a per-attempt UUID backup name, only deletes backups created by that attempt, verifies cleanup/restore outcomes and retains a named recovery backup on partial failure. `SessionRepository` now uses `ConcurrentHashMap`. Focused `TransferReliabilityTest` passed; full verification should be repeated by the integrating task.
