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

## Review follow-up

- `PreviewScreen` now obtains an authenticated 64 KiB range probe before routing. It combines the probe's UTF-8 sample with the server `Content-Type` (and optional listed MIME metadata), so extensionless binaries remain unsupported while text is previewed and MPEG-TS wins over the ambiguous `.ts` suffix.
- The streaming download uses a public-API cancellable worker bridge: the complete `HttpURLConnection` transfer runs on `Dispatchers.IO`, while `invokeOnCancellation` atomically marks the request cancelled before taking and closing its published owner. The worker checks that flag before opening a connection and immediately after publishing one, so a handler that initially observes no resource cannot miss a later connection. The caller receives cancellation promptly; a detached native HTTP read is still bounded by the configured 15-second read timeout if close/disconnect cannot wake that runtime's primitive. `FileMutationRequestTest` covers both pre-publication ownership and a server-held mid-read cancellation.
- PDF and image bitmap allocation now keeps an explicit unpublished owner until Compose's `DisposableEffect` takes ownership. PDF rendering does not publish a bitmap until `page.render` returns, so disposal cannot recycle it while the renderer uses it; render/decode failures and post-render cancellation recycle exactly once. `RenderOwnerTest` covers publication after prior disposal. PDF page generation uses the count-based lazy API.
- MIME selection now combines listed metadata and probe headers, treating `video/mp2t` as a recognized specific type ahead of generic probe values. `PreviewRouterTest` covers both metadata/probe orders.

## Final review verification

- `testDebugUnitTest --tests "*PreviewRouterTest" --tests "*TextPagerTest" --tests "*FileMutationRequestTest"` passed using the repository portable JDK 17 and Android SDK.
- The focused cancellation test was first observed failing with an `ApiResult` instead of `CancellationException`; it passes after the lifecycle fix.
- `PdfPreview.kt` compiled in the same fresh Kotlin compile, so Compose's count-based `items` overload is available without an additional import in this dependency version.

## Known constraint

The `.ts` suffix is ambiguous between TypeScript and MPEG transport streams. Extension-only routing defaults to TypeScript; `PreviewRouter.kind(name, declaredMimeType)` lets a server-declared `video/mp2t` select video instead.
