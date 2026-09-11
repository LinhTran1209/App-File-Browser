package com.j2team.fileserver.feature.preview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
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
    onTap: (() -> Unit)? = null,
    onSwipeUp: (() -> Unit)? = null,
    onSwipeDown: (() -> Unit)? = null,
) {
    val density = LocalDensity.current
    val swipeThreshold = with(density) { 72.dp.toPx() }
    var scale by remember(file) { mutableStateOf(1f) }
    var offset by remember(file) { mutableStateOf(Offset.Zero) }
    var dragDistanceX by remember(file) { mutableStateOf(0f) }
    var dragDistanceY by remember(file) { mutableStateOf(0f) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(file, onTap) {
                detectTapGestures(
                    onTap = { onTap?.invoke() },
                    onDoubleTap = {
                        if (scale > 1.2f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                            offset = Offset.Zero
                        }
                    }
                )
            }
            .pointerInput(file, onSwipeUp, onSwipeDown) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 5f)
                    val maxOffsetX = (newScale - 1f) * (size.width / 2f)
                    val maxOffsetY = (newScale - 1f) * (size.height / 2f)
                    val newOffsetX = if (newScale <= 1f) 0f else (offset.x + pan.x).coerceIn(-maxOffsetX, maxOffsetX)
                    val newOffsetY = if (newScale <= 1f) 0f else (offset.y + pan.y).coerceIn(-maxOffsetY, maxOffsetY)

                    if (scale <= 1.05f && newScale <= 1.05f) {
                        dragDistanceY += pan.y
                        dragDistanceX += pan.x
                        if (dragDistanceY <= -swipeThreshold || dragDistanceX <= -swipeThreshold) {
                            onSwipeUp?.invoke()
                            dragDistanceY = 0f
                            dragDistanceX = 0f
                        } else if (dragDistanceY >= swipeThreshold || dragDistanceX >= swipeThreshold) {
                            onSwipeDown?.invoke()
                            dragDistanceY = 0f
                            dragDistanceX = 0f
                        }
                    } else {
                        dragDistanceY = 0f
                        dragDistanceX = 0f
                        scale = newScale
                        offset = Offset(newOffsetX, newOffsetY)
                    }
                }
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
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
                contentScale = ContentScale.Fit,
            )
        }
    }
}
