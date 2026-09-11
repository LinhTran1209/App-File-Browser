package com.j2team.fileserver.feature.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.j2team.fileserver.core.model.RemoteResource
import com.j2team.fileserver.core.model.ServerProfile
import com.j2team.fileserver.core.session.SessionRepository
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TEXT_PAGE_BYTES = 128 * 1024
private const val MAX_RETAINED_TEXT_PAGES = 32

data class TextPage(val text: String)
internal data class RenderedTextPage(val id: Long, val page: TextPage)

/** Reads UTF-8 source incrementally, retaining at most one page plus a three-byte pushback buffer. */
class TextPager(input: InputStream, private val pageBytes: Int = TEXT_PAGE_BYTES) : Closeable {
    private val input = PushbackInputStream(BufferedInputStream(input), MAX_UTF8_TAIL_BYTES)
    private var exhausted = false

    init {
        require(pageBytes >= 4) { "pageBytes must fit one UTF-8 character" }
    }

    suspend fun loadNext(): TextPage? = withContext(Dispatchers.IO) { loadNextBlocking() }

    fun loadNextBlocking(): TextPage? {
        if (exhausted) return null

        val bytes = ByteArray(pageBytes)
        var count = 0
        while (count < bytes.size) {
            val read = input.read(bytes, count, bytes.size - count)
            if (read < 0) {
                exhausted = true
                break
            }
            count += read
        }
        if (count == 0) return null

        val pushback = trailingIncompleteUtf8Bytes(bytes, count)
        if (pushback > 0) {
            input.unread(bytes, count - pushback, pushback)
            count -= pushback
        }
        return TextPage(String(bytes, 0, count, Charsets.UTF_8))
    }

    override fun close() {
        input.close()
    }

    private companion object {
        const val MAX_UTF8_TAIL_BYTES = 3
    }
}

@Composable
internal fun TextPreview(
    profile: ServerProfile,
    item: RemoteResource,
    sessionRepository: SessionRepository,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
    isEditing: Boolean = false,
    textDraft: String = "",
    onTextDraftChange: (String) -> Unit = {},
    onTap: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val previewFile = remember(profile.id, item.path) {
        File(context.cacheDir, "text-preview-${UUID.randomUUID()}.tmp")
    }
    var pager by remember(profile.id, item.path) { mutableStateOf<TextPager?>(null) }
    val pages = remember(profile.id, item.path) { mutableStateListOf<RenderedTextPage>() }
    var exhausted by remember(profile.id, item.path) { mutableStateOf(false) }
    var nextPageId by remember(profile.id, item.path) { mutableStateOf(0L) }
    var isLoaded by remember(profile.id, item.path) { mutableStateOf(false) }

    DisposableEffect(profile.id, item.path) {
        onDispose {
            pager?.close()
            previewFile.delete()
        }
    }
    LaunchedEffect(profile.id, item.path) {
        val result = withContext(Dispatchers.IO) {
            sessionRepository.download(profile, item.path, previewFile)
        }
        result
            .onSuccess { downloaded ->
                val fullText = downloaded.readText(Charsets.UTF_8)
                onTextDraftChange(fullText)
                isLoaded = true
                pager = TextPager(downloaded.inputStream())
            }
            .onFailure {
                exhausted = true
                onError(it.message ?: "Unable to load text preview")
            }
    }

    if (isEditing) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color(0xFF0E0E0E))
                .padding(start = 16.dp, end = 16.dp, top = 64.dp, bottom = 64.dp)
        ) {
            BasicTextField(
                value = textDraft,
                onValueChange = onTextDraftChange,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                textStyle = TextStyle(
                    color = Color(0xFFECECEC),
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 22.sp
                ),
                cursorBrush = SolidColor(Color(0xFF2196F3))
            )
        }
        return
    }

    TextPreviewPageList(pages, modifier, onTap = onTap) {
        val activePager = pager
        if (activePager == null && !exhausted) {
            CircularProgressIndicator()
        } else if (activePager != null && !exhausted) {
            LaunchedEffect(activePager, nextPageId) {
                runCatching { activePager.loadNext() }
                    .onSuccess { page ->
                        if (page == null) {
                            exhausted = true
                        } else {
                            if (pages.size == MAX_RETAINED_TEXT_PAGES) pages.removeAt(0)
                            pages.add(RenderedTextPage(nextPageId++, page))
                        }
                    }
                    .onFailure {
                        exhausted = true
                        onError(it.message ?: "Unable to read text preview")
                    }
            }
        }
    }
}

/** Scrollable page renderer shared by streamed previews and deterministic UI tests. */
@Composable
internal fun TextPreviewPageList(
    pages: List<RenderedTextPage>,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null,
    loadMore: @Composable () -> Unit = {},
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("text-preview-pages")
            .pointerInput(onTap) {
                detectTapGestures(onTap = { onTap?.invoke() })
            },
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 72.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(pages, key = RenderedTextPage::id) { rendered ->
            SelectionContainer {
                Text(
                    text = rendered.page.text,
                    color = Color(0xFFE0E0E0),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                )
            }
        }
        item {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                loadMore()
            }
        }
    }
}

internal fun trailingIncompleteUtf8Bytes(bytes: ByteArray, length: Int): Int {
    if (length <= 0) return 0
    var index = length - 1
    var sequenceBytes = 0
    while (index >= 0 && sequenceBytes < 4) {
        val current = bytes[index].toInt() and 0xFF
        if (current and 0b1100_0000 != 0b1000_0000) {
            val expectedLength = when {
                current and 0b1000_0000 == 0 -> 1
                current and 0b1110_0000 == 0b1100_0000 -> 2
                current and 0b1111_0000 == 0b1110_0000 -> 3
                current and 0b1111_1000 == 0b1111_0000 -> 4
                else -> 1
            }
            val availableLength = length - index
            return if (availableLength < expectedLength) availableLength else 0
        }
        sequenceBytes++
        index--
    }
    return 0
}
