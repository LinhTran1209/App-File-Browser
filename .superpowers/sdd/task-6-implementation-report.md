# Task 6 Implementation Report

## Delivered

- Pinned Media3 to `1.10.1` and added ExoPlayer, Compose UI, and OkHttp datasource artifacts.
- Replaced eager temporary media downloads with authenticated Media3 playback from File Browser raw URLs.
- Added case-insensitive audio/video MIME mappings and retained the Task 5 `.ts` rule: only a server-declared `video/mp2t` is streamed as MPEG transport video.
- Added a bounded 15-50 second load-control buffer, `X-Auth` request header, player release on composition disposal, basic play/pause controls, and one safe refresh/reprepare after raw GET authorization rejection.
- Added an explicit external-app fallback that downloads only on demand, serves the temporary file through FileProvider, and removes it when the preview leaves composition.
- Added Vietnamese and English media error/action resources.

## Test evidence

- RED: `testDebugUnitTest --tests "*MediaTypeTest"` failed with unresolved `mediaMimeType` and `mediaStreamConfiguration` before implementation.
- GREEN: the focused `MediaTypeTest` passes after implementation.
- Final: `testDebugUnitTest lintDebug assembleDebug` completed successfully.

## Review notes

- No directory/listing composable constructs a Media3 player or prebuffers media.
- Player construction and a new prepare happen only in the preview screen. The only full-file temporary download is the user-triggered external fallback.
- Media3 1.10.1 marks the configured datasource/source/surface APIs unstable; the narrow AndroidX opt-in is applied on the factory and media composable.

## Review remediation

- Added a tested `requiresDownloadedFile` render policy so only PDF and image previews wait for a local download; audio and video now enter `MediaPreview` directly.
- Kept playback and authentication errors local to `MediaPreview`, preserving the external-app fallback action.
- Made the fallback download cancellable through `SessionRepository.downloadTo`, with an owned Job, `.part` staging file, disposal cancellation, and cleanup guards before rename or intent launch.
- Restored the required `.ts` Media MIME mapping while using the bounded preview sample and declared MIME to keep TypeScript text previews as text.
- Added pure tests for preview render policy, one-shot retry/reprepare decision, fallback cleanup paths, and `.ts` text/binary routing; added loading, seek/progress, play/pause, semantic labels, and saved playback position controls.
- Added `StreamRetryState.reprepareGeneration`, which increments after every successful authorized renewal and keys Media3 player construction independently from token equality; the test covers an identical renewed token and verifies a second 401 surfaces the local error instead of another retry.
