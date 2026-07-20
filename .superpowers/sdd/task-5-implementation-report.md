# Task 5 Implementation Report

## Delivered

- Extended preview routing for all specified image, PDF, source/text, video, and audio extensions with case-insensitive MIME lookup.
- Added UTF-8 `TextPager` with 128 KiB bounded pages, boundary-safe decoding, and focused regression tests.
- Streamed authenticated text previews through `SessionRepository.downloadTo` into a pipe, capped retained UI pages, and added a localized 2 MiB **Open anyway** gate.
- Added lazy `PdfRenderer` page rendering with page/bitmap cleanup and per-page error UI.
- Added sampled image decoding with bitmap recycling and cache-file cleanup.
- Moved preview composition into `feature/preview` while retaining the temporary legacy media route for Task 6 replacement.

## Verification

- RED: focused tests initially failed because `PreviewKind.Pdf` and `TextPager` were unresolved.
- GREEN: `./gradlew.bat testDebugUnitTest --tests "*PreviewRouterTest" --tests "*TextPagerTest"` passed.
- Final: `./gradlew.bat testDebugUnitTest lintDebug assembleDebug` passed.

## Known constraint

The `.ts` suffix is ambiguous between TypeScript and MPEG transport streams. Extension-only routing defaults to TypeScript; `PreviewRouter.kind(name, declaredMimeType)` lets a server-declared `video/mp2t` select video instead.
