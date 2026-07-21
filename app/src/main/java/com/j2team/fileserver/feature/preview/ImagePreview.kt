package com.j2team.fileserver.feature.preview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import java.util.concurrent.atomic.AtomicReference

internal fun decodeSampledImage(file: File, requestedWidth: Int, requestedHeight: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, requestedWidth, requestedHeight)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeFile(file.path, options)
}

private suspend fun decodeSampledImageOwned(file: File, width: Int, height: Int, owner: AtomicReference<Bitmap?>): Bitmap? {
    val bitmap = decodeSampledImage(file, width, height)
    owner.set(bitmap)
    coroutineContext.ensureActive()
    return bitmap
}

private fun sampleSize(width: Int, height: Int, requestedWidth: Int, requestedHeight: Int): Int {
    var sample = 1
    while (width / (sample * 2) >= requestedWidth && height / (sample * 2) >= requestedHeight) sample *= 2
    return sample
}

@Composable
internal fun ImagePreview(
    file: File,
    description: String,
    modifier: Modifier = Modifier,
    onSwipeUp: (() -> Unit)? = null,
    onSwipeDown: (() -> Unit)? = null,
) {
    val density = LocalDensity.current
    val swipeThreshold = with(density) { 72.dp.toPx() }
    var dragDistance by remember(file) { mutableStateOf(0f) }
    BoxWithConstraints(
        modifier = modifier.fillMaxSize().pointerInput(file, onSwipeUp, onSwipeDown) {
            detectVerticalDragGestures(
                onDragStart = { dragDistance = 0f },
                onVerticalDrag = { change, amount ->
                    change.consume()
                    dragDistance += amount
                },
                onDragEnd = {
                    when {
                        dragDistance <= -swipeThreshold -> onSwipeUp?.invoke()
                        dragDistance >= swipeThreshold -> onSwipeDown?.invoke()
                    }
                    dragDistance = 0f
                },
                onDragCancel = { dragDistance = 0f },
            )
        },
        contentAlignment = Alignment.Center,
    ) {
        val targetWidth = with(density) { maxWidth.roundToPx().coerceAtLeast(1) }
        val targetHeight = with(density) { maxHeight.roundToPx().coerceAtLeast(1) }
        var bitmap by remember(file, targetWidth, targetHeight) { mutableStateOf<Bitmap?>(null) }
        val unpublishedBitmap = remember(file, targetWidth, targetHeight) { AtomicReference<Bitmap?>(null) }

        DisposableEffect(unpublishedBitmap) {
            onDispose {
                unpublishedBitmap.getAndSet(null)?.let { if (!it.isRecycled) it.recycle() }
            }
        }

        LaunchedEffect(file, targetWidth, targetHeight) {
            var published = false
            try {
                bitmap = withContext(Dispatchers.IO) {
                    decodeSampledImageOwned(file, targetWidth, targetHeight, unpublishedBitmap)
                }
                published = true
            } finally {
                if (!published) unpublishedBitmap.getAndSet(null)?.let { if (!it.isRecycled) it.recycle() }
            }
        }
        bitmap?.let { decoded ->
            DisposableEffect(decoded) {
                unpublishedBitmap.compareAndSet(decoded, null)
                onDispose { if (!decoded.isRecycled) decoded.recycle() }
            }
            Image(
                bitmap = decoded.asImageBitmap(),
                contentDescription = description,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}
