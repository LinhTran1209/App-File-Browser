package com.j2team.fileserver.feature.preview

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

private class PdfDocument(private val file: File) : Closeable {
    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    var pageCount by mutableIntStateOf(0)
        private set

    fun markReady(value: Int) {
        pageCount = value
    }

    suspend fun open(): Int = withContext(Dispatchers.IO) {
        val openedDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            val openedRenderer = PdfRenderer(openedDescriptor)
            synchronized(this@PdfDocument) {
                descriptor = openedDescriptor
                renderer = openedRenderer
                openedRenderer.pageCount
            }
        } catch (error: Throwable) {
            openedDescriptor.close()
            throw error
        }
    }

    suspend fun pageBitmap(index: Int, width: Int): Bitmap = withContext(Dispatchers.IO) {
        synchronized(this@PdfDocument) {
            renderer.orThrow().openPage(index).use { page ->
                val height = (page.height.toFloat() / page.width * width).toInt().coerceAtLeast(1)
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                    try {
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    } catch (error: Throwable) {
                        bitmap.recycle()
                        throw error
                    }
                }
            }
        }
    }

    override fun close() {
        synchronized(this) {
            renderer?.close()
            renderer = null
            descriptor?.close()
            descriptor = null
        }
    }

    private fun PdfRenderer?.orThrow(): PdfRenderer = requireNotNull(this) { "PDF renderer is not open" }

}

@Composable
internal fun PdfPreview(file: File, modifier: Modifier = Modifier) {
    val document = remember(file) { PdfDocument(file) }
    var error by remember(file) { mutableStateOf<String?>(null) }

    DisposableEffect(document) {
        onDispose(document::close)
    }
    LaunchedEffect(document) {
        runCatching { document.open() }
            .onSuccess(document::markReady)
            .onFailure { if (it is CancellationException) throw it; error = it.message ?: "Unable to render PDF" }
    }

    when {
        error != null -> androidx.compose.material3.Text(error!!)
        document.pageCount == 0 -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(count = document.pageCount, key = { it }) { index ->
                PdfPage(document, index)
            }
        }
    }
}

@Composable
private fun PdfPage(document: PdfDocument, index: Int) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val width = with(LocalDensity.current) { maxWidth.roundToPx().coerceAtLeast(1) }
        var bitmap by remember(document, index, width) { mutableStateOf<Bitmap?>(null) }
        var error by remember(document, index, width) { mutableStateOf<String?>(null) }
        LaunchedEffect(document, index, width) {
            runCatching { document.pageBitmap(index, width) }
                .onSuccess { bitmap = it }
                .onFailure { if (it is CancellationException) throw it; error = it.message ?: "Unable to render page ${index + 1}" }
        }
        bitmap?.let {
            DisposableEffect(it) {
                onDispose { if (!it.isRecycled) it.recycle() }
            }
            Image(it.asImageBitmap(), "PDF page ${index + 1}", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
        } ?: error?.let { androidx.compose.material3.Text(it) } ?: CircularProgressIndicator()
    }
}
