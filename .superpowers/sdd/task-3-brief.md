### Task 3: File Browser Permissions, Long Press, and Mutations

**Files:**
- Extend: `app/src/main/java/com/j2team/fileserver/core/model/RemoteResource.kt`
- Modify: `app/src/main/java/com/j2team/fileserver/core/network/FileBrowserClient.kt`
- Create: `app/src/main/java/com/j2team/fileserver/feature/browser/SelectionPolicy.kt`
- Create: `app/src/main/java/com/j2team/fileserver/feature/browser/BrowserScreen.kt`
- Test: `app/src/test/java/com/j2team/fileserver/feature/browser/SelectionPolicyTest.kt`
- Test: `app/src/test/java/com/j2team/fileserver/core/network/FileMutationRequestTest.kt`

**Interfaces:**
- Extends `RemoteResource` with `permissions: ResourcePermissions`.
- Produces `data class ResourcePermissions(val canDownload: Boolean, val canUpload: Boolean, val canCreate: Boolean, val canDelete: Boolean)`.
- Produces `suspend fun createDirectory(profile, token, path): Result<Unit>`.
- Produces `suspend fun delete(profile, token, paths): Result<Unit>`.

- [ ] **Step 1: Write failing permission-intersection tests**

```kotlin
@Test fun deleteRequiresEverySelectedResourceToPermitIt() {
    val allowed = resource(canDelete = true)
    val denied = resource(canDelete = false)
    assertTrue(SelectionPolicy.actions(listOf(allowed)).canDelete)
    assertFalse(SelectionPolicy.actions(listOf(allowed, denied)).canDelete)
}
```

- [ ] **Step 2: Verify RED**

Run: `.\gradlew.bat testDebugUnitTest --tests "*SelectionPolicyTest"`
Expected: FAIL because permissions and selection policy are absent.

- [ ] **Step 3: Parse permissions and implement API mutations**

Map File Browser response permissions into the stable domain model. Encode each
path segment once. `DELETE /api/resources/{path}` runs sequentially and stops on
the first rejected resource; the browser refreshes from the server instead of
removing items optimistically.

- [ ] **Step 4: Implement blank-strip and selection interactions**

Use `combinedClickable(onLongClick = ...)` on a 48 dp action strip below the
breadcrumb. Its menu contains New folder, Upload files, and Upload folder.
Resource long press enters selection mode; the contextual app bar shows the
count and only the `SelectionPolicy` intersection.

- [ ] **Step 5: Add guarded delete**

Show a confirmation dialog with selection count. Disable delete when any
selected item has `canDelete == false`. On server rejection retain every item,
clear no selection, and show the returned error.

- [ ] **Step 6: Verify and commit**

Run: `.\gradlew.bat testDebugUnitTest lintDebug assembleDebug`
Expected: pass.

Commit: `git commit -m "feat: add permission aware browser mutations"`

---

