# Task 5 independent final re-review

## Verdicts

- **Specification:** APPROVED
- **Code quality:** APPROVED

Reviewed the Task 5 brief, updated implementation report, prior review, supplied `2e9e5f3..871f700` package diff, and current source at `871f700`. No Critical or Important findings remain. No production code was changed.

The root reviewer independently verified `clean compileDebugKotlin` passes, so the earlier concern about `PdfPreview.kt`'s count-based `items` import is retracted: in this Compose dependency version the overload resolves without that explicit import.

## Verified closures

- **Actual routing is binary-safe and reaches the UI.** `PreviewScreen.kt:63-68` probes before routing; `PreviewRouter.kt:46-49,98-119` uses the bounded sample for extensionless resources, rejects NUL/malformed UTF-8 input, and selects `video/mp2t` over a generic MIME from either metadata source. `PreviewRouterTest.kt:38-59` covers extensionless text/binary and both `.ts` MIME-source orders.
- **Request cancellation is now race-safe in production.** `CancellableRequestOwner` (`FileBrowserClient.kt:27-49`) atomically records cancellation and owns the published close action. `downloadToResult` checks cancellation before opening the connection (`127-131`) and publishes a cleanup action immediately after connection creation (`131-134`); a cancellation that won the race before publication makes `publish` close the late connection and prevents the request from advancing. Once published, cancellation closes the active request from IO (`113-126`). `cancellationBeforePublicationClosesTheLateRequestOwner` (`FileMutationRequestTest.kt:20-28`) deterministically proves that ordering; the blocked-read test (`31-75`) proves prompt caller cancellation.
- **The detached native-read fallback is bounded, not an unbounded leak.** If a platform does not wake a native HTTP read from close/disconnect, the caller is already cancelled promptly and the isolated `Dispatchers.IO` worker is bounded by the configured 15-second HTTP read timeout (`FileBrowserClient.kt:145-146,344-348`). The cancellation owner prevents a request from being started after pre-publication cancellation, and always disconnects on worker completion (`156-161`). This is a documented bounded trade-off, not a remaining defect.
- **PDF bitmap ownership begins only after native rendering completes.** `PdfDocument.pageBitmap` renders first, then calls `RenderOwner.publishAfterRender` (`PdfPreview.kt:84-101`); disposal before publication marks the owner released and recycles only when the render completes (`RenderOwner:39-57`). The page composable transfers published ownership to Compose and releases unpublished values on cancellation/disposal (`152-172`). Thus disposal cannot recycle a bitmap while `PdfRenderer` is rendering into it. `RenderOwnerTest.kt:8-17` deterministically covers the disposed-before-publication path.
- **Image and text resource behavior remains sound.** Images retain explicit post-decode ownership and recycle on cancellation/disposal (`ImagePreview.kt:60-82`). `TextPager` keeps 128 KiB UTF-8-safe pages and the UI retains eight pages at most (`TextPreview.kt:35-100,141-148`), with long-text and UTF-8 split tests in `TextPagerTest.kt:9-27`.
- **PDF remains count-lazy.** `PdfPreview.kt:134-142` uses the count-based `items` API and does not allocate an index list.

## Findings

- Critical: none.
- Important: none.
- Minor: none.
