package com.j2team.fileserver.feature.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
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
            if (read < 0) break
            if (read > 0) count += read
        }

        if (count == 0) {
            exhausted = true
            close()
            return null
        }

        val completeLength = completeUtf8PrefixLength(bytes, count)
        if (completeLength < count) input.unread(bytes, completeLength, count - completeLength)
        if (count < bytes.size) {
            exhausted = true
            close()
        }
        return TextPage(bytes.decodeToString(endIndex = completeLength))
    }

    override fun close() {
        input.close()
    }

    private fun completeUtf8PrefixLength(bytes: ByteArray, length: Int): Int {
        var start = length - 1
        while (start > 0 && isContinuationByte(bytes[start])) start--
        val width = utf8Width(bytes[start])
        return if (width > 1 && length - start < width) start else length
    }

    private fun isContinuationByte(byte: Byte): Boolean = byte.toInt() and 0xC0 == 0x80

    private fun utf8Width(byte: Byte): Int = when (byte.toInt() and 0xFF) {
        in 0xC2..0xDF -> 2
        in 0xE0..0xEF -> 3
        in 0xF0..0xF4 -> 4
        else -> 1
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
) {
    val context = LocalContext.current
    val previewFile = remember(profile.id, item.path) {
        File(context.cacheDir, "text-preview-${UUID.randomUUID()}.tmp")
    }
    var pager by remember(profile.id, item.path) { mutableStateOf<TextPager?>(null) }
    val pages = remember(profile.id, item.path) { mutableStateListOf<RenderedTextPage>() }
    var exhausted by remember(profile.id, item.path) { mutableStateOf(false) }
    var nextPageId by remember(profile.id, item.path) { mutableStateOf(0L) }

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
            .onSuccess { downloaded -> pager = TextPager(downloaded.inputStream()) }
            .onFailure {
                exhausted = true
                onError(it.message ?: "Unable to load text preview")
            }
    }

    TextPreviewPageList(pages, modifier) {
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
    loadMore: @Composable () -> Unit = {},
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("text-preview-pages"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(pages, key = RenderedTextPage::id) { rendered ->
            SelectionContainer {
                Text(
                    text = rendered.page.text,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.testTag("text-page-${rendered.id}"),
                )
            }
        }
        item(key = "next-page") { loadMore() }
    }
}
