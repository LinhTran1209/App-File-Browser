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
