### Task 4: Reliable Uploads, Folder Upload, Downloads, and Transfer Badge

**Files:**
- Modify: `app/src/main/java/com/j2team/fileserver/core/network/FileBrowserClient.kt`
- Modify: `app/src/main/java/com/j2team/fileserver/feature/transfers/TransferModels.kt`
- Modify: `app/src/main/java/com/j2team/fileserver/feature/transfers/TransferStore.kt`
- Create: `app/src/main/java/com/j2team/fileserver/feature/transfers/TransferCoordinator.kt`
- Create: `app/src/main/java/com/j2team/fileserver/feature/transfers/TransfersScreen.kt`
- Test: `app/src/test/java/com/j2team/fileserver/feature/transfers/TransferPolicyTest.kt`
- Test: `app/src/test/java/com/j2team/fileserver/core/network/MultipartEncoderTest.kt`

**Interfaces:**
- Produces `enum class TransferTab { Downloads, Uploads }`.
- Produces `fun List<TransferTask>.attentionCount(): Int`.
- Produces `class TransferCoordinator` limited by `Semaphore(2)`.
- Produces `suspend fun enqueueFolder(treeUri, remotePath)`.

- [ ] **Step 1: Write failing badge/tab tests**

```kotlin
@Test fun badgeCountsTasksNeedingAttentionAndCapsAt99() {
    assertEquals("4", fourMixedActiveAndFailed.attentionBadge())
    assertEquals("99+", oneHundredQueued.attentionBadge())
}

@Test fun tabsSeparateDirections() {
    assertTrue(tasks.forTab(TransferTab.Uploads).all { it.direction == TransferDirection.Upload })
}
```

- [ ] **Step 2: Write failing multipart tests**

Assert one `file` part, filename escaping, correct terminal boundary, exact
encoded destination, non-2xx failure, and progress never exceeding total.

- [ ] **Step 3: Verify RED**

Run: `.\gradlew.bat testDebugUnitTest --tests "*TransferPolicyTest" --tests "*MultipartEncoderTest"`
Expected: FAIL for missing policy/encoder.

- [ ] **Step 4: Implement transfer coordinator**

Use `Semaphore(2)` around streaming operations. Folder upload recursively walks
`DocumentFile`, creates directories before files, and enqueues one durable task
per file. Failed tasks retain their last byte count and error.

- [ ] **Step 5: Implement SAF downloads**

Write `.<name>.part` through `DocumentFile.createFile`, copy with an 8 KiB
buffer, and create the final document only after success. Handle replace, keep
both, and cancel via an explicit conflict dialog.

- [ ] **Step 6: Implement transfer UI and badge**

Use a 28 dp transfer icon, Material badge, Downloads/Uploads tabs, state rows,
retry, dismiss, and progress. The browser toolbar observes the queue and updates
the badge without reopening the screen.

- [ ] **Step 7: Verify and commit**

Run: `.\gradlew.bat testDebugUnitTest lintDebug assembleDebug`
Expected: pass.

Commit: `git commit -m "feat: deliver reliable transfer center"`

---

