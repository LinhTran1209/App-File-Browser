# Task 7 independent review

**Verdict: REQUEST CHANGES** — no Critical findings; two Important findings prevent approval.

## Important

1. **The text-preview refactor drops the stable keys that its rolling page window requires.**
   `TextPreview` still assigns an increasing `id` and evicts the first page once the
   eight-page retention limit is reached (`TextPreview.kt:114-116, 127-132`), but
   `TextPreviewPageList` maps those objects to plain `TextPage` values and renders
   them with `items(pages)` without a key (`TextPreview.kt:127, 141-151`).  When an
   oldest page is removed, every remaining item therefore takes the preceding
   position's Compose slot.  This regresses the original
   `items(pages, key = RenderedTextPage::id)` behavior and can produce incorrect
   viewport/selection identity as a streamed preview advances.  Keep the stable
   rendered-page ID through the list renderer (or supply an equally stable key),
   and test eviction/append behavior rather than only a fixed list.

2. **Several required end-to-end behaviors are represented only by callback or
   storage surrogates, so the instrumentation suite would not catch broken product
   flows.**
   The session test merely proves the `Application` returns the same lazy object
   before/after recreation (`SessionResumeTest.kt:16-23`); it neither establishes a
   session nor verifies reopening/resuming it.  The directory test writes an
   arbitrary URI directly to `SettingsStore` (`SettingsAndSelectionTest.kt:76-85`),
   bypassing `SettingsScreen`'s grant validation and `onChanged` path
   (`SettingsScreen.kt:29-39`).  The long-press test changes a non-observable local
   Boolean and asserts that the injected callback ran (`SettingsAndSelectionTest.kt:91-107`),
   rather than observing the selected `ResourceRow` state or the real
   `BrowserScreen` selection model (`BrowserScreen.kt:323-335, 351-355`).  These
   tests satisfy neither meaningful session resume nor directory-selection and
   long-press product-state coverage requested in Step 1.  Use observable Compose
   state/test fakes to drive the production flows and assert their user-visible
   state; no live Pi credentials are needed.

## Minor

1. **The transfer-tab test is not isolated and does not actually demonstrate
   durability.**  It removes every task in the shared persistent store before its
   `try` block (`SettingsAndSelectionTest.kt:111-116`), does not restore preexisting
   tasks, and only removes its two newly-created tasks in `finally`
   (`SettingsAndSelectionTest.kt:117-125`).  This risks order-dependent tests and
   can erase useful diagnostic state.  Use an injected/unique test store or retain
   and restore the original queue; construct a new store (or otherwise reload)
   before asserting the queued tasks survive.

2. **The long-text test verifies static list scrolling, not streamed-preview window
   behavior.**  It gives the extracted renderer 21 tiny fixed pages
   (`SettingsAndSelectionTest.kt:129-135`), so it is a useful non-flaky scroll check
   but cannot protect the page loading/eviction regression above.

## Confirmed

- Password-eye semantics are checked through the actual `LoginScreen` content
  description before and after toggling (`SettingsAndSelectionTest.kt:40-57`), which
  is appropriate for this accessibility requirement.
- The adaptive and round icon XML resources are correctly qualified for API 26+
  and both manifest attributes point to them (`AndroidManifest.xml:6-10`).  The
  foreground mark bounds are within the documented 21dp inset of its 108dp viewport
  (`ic_launcher_foreground.xml:2-20`), and the indigo colour resource is valid.
  There is intentionally no pre-26 fallback: `minSdk = 26`, so a pre-26 device
  cannot install this app.  No legacy-icon defect follows from the missing default
  `mipmap` resources.
- Test-only exposure is limited to `internal` composables plus one stable test tag;
  no public product API was added for the tests.
- `git diff --check d5c80c9 e41215f` and `git show --check e41215f` are clean.

## Out of scope

Raspberry Pi live acceptance is an operator check and was not treated as a code
review blocker.  The findings above are independently reproducible from source and
test code.
