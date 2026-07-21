# Task 2 final independent review

**Verdict: APPROVED.** No Critical or Important findings remain.

## Critical

None.

## Important

None.

## Minor

1. **Migration coverage remains resolver-level rather than a complete
   `SettingsStore`/SharedPreferences integration test.**  
   Evidence: [SettingsCodecTest.kt:30-46](../../app/src/test/java/com/j2team/fileserver/feature/settings/SettingsCodecTest.kt#L30)
   exercises `resolvePersistedSettings`, while former preference keys are read
   in [SettingsStore.kt:25-33](../../app/src/main/java/com/j2team/fileserver/feature/settings/SettingsStore.kt#L25).

   Impact: fallback and precedence are verified in isolation, but a future
   edit to the legacy preference key/type reads would need an Android-backed
   test to be caught. This does not block Task 2.

## Final verification

- The prior read-only SAF finding is closed. The selection callback now requires
  `acceptsDownloadTreeGrant()` before persisting the URI
  ([SettingsScreen.kt:29-39](../../app/src/main/java/com/j2team/fileserver/feature/settings/SettingsScreen.kt#L29)).
  The predicate checks the write-grant bit ([SettingsStore.kt:80-81](../../app/src/main/java/com/j2team/fileserver/feature/settings/SettingsStore.kt#L80)),
  so a read-only result is rejected, the prior URI remains unchanged, and the
  localized permission error is displayed.
- The added tests explicitly reject `FLAG_GRANT_READ_URI_PERMISSION` only and
  accept `FLAG_GRANT_WRITE_URI_PERMISSION`
  ([SettingsCodecTest.kt:49-55](../../app/src/test/java/com/j2team/fileserver/feature/settings/SettingsCodecTest.kt#L49)).
- Selected SAF trees are used for downloads; write/revocation/provider failures
  mark the transfer failed, show localized recovery guidance, and do not fall
  back silently. Staged cache files and partially created documents are cleaned
  up on normal failure paths.
- Codec round-trip/default compatibility, locale application, Settings wiring,
  password-toggle semantics, resource localization, normalized icons, and
  folder-icon use remain satisfactory. New-folder/delete wiring is explicitly
  deferred to Task 3.

## Validation note

The review environment still has no usable JDK (`JAVA_HOME` invalid and no
`java` on `PATH`), so I could not independently rerun Gradle. The source and
the recorded focused/full verification in the implementation report were
reviewed.
