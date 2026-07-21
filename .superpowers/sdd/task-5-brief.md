### Task 5: Scrollable Text, Code, PDF, and Image Preview

**Files:**
- Modify: `app/src/main/java/com/j2team/fileserver/feature/preview/PreviewRouter.kt`
- Create: `app/src/main/java/com/j2team/fileserver/feature/preview/TextPreview.kt`
- Create: `app/src/main/java/com/j2team/fileserver/feature/preview/PdfPreview.kt`
- Create: `app/src/main/java/com/j2team/fileserver/feature/preview/ImagePreview.kt`
- Create: `app/src/main/java/com/j2team/fileserver/feature/preview/PreviewScreen.kt`
- Test: `app/src/test/java/com/j2team/fileserver/feature/preview/PreviewRouterTest.kt`
- Test: `app/src/test/java/com/j2team/fileserver/feature/preview/TextPagerTest.kt`

**Interfaces:**
- Adds `PreviewKind.Pdf`.
- Produces `class TextPager(input: InputStream, pageBytes: Int = 128 * 1024)`.
- Produces `suspend fun loadNext(): TextPage`.

- [ ] **Step 1: Write failing preview-routing tests**

Create table-driven assertions covering every text/code, image, PDF, audio, and
video extension listed in the specification, including uppercase extensions and
extensionless text.

- [ ] **Step 2: Write failing long-text paging test**

```kotlin
@Test fun readsLongTextInBoundedPagesWithoutTruncating() {
    val pager = TextPager(ByteArrayInputStream("x".repeat(400_000).toByteArray()), 128_000)
    val pages = generateSequence { pager.loadNextBlocking() }.toList()
    assertEquals(400_000, pages.sumOf { it.text.length })
    assertTrue(pages.size > 1)
}
```

- [ ] **Step 3: Verify RED**

Run: `.\gradlew.bat testDebugUnitTest --tests "*PreviewRouterTest" --tests "*TextPagerTest"`
Expected: FAIL.

- [ ] **Step 4: Implement previews**

Text/code uses `LazyColumn` pages and selectable monospace text. Markdown remains
scrollable plain/source text. PDF uses `PdfRenderer` and lazy page bitmaps.
Images decode sampled dimensions and recycle temporary files/bitmaps when the
screen leaves composition.

- [ ] **Step 5: Verify and commit**

Run: `.\gradlew.bat testDebugUnitTest lintDebug assembleDebug`
Expected: pass.

Commit: `git commit -m "feat: add document and code previews"`

---

