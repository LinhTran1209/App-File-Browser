package com.j2team.fileserver.feature.preview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

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

private fun sampleSize(width: Int, height: Int, requestedWidth: Int, requestedHeight: Int): Int {
    var sample = 1
    while (width / (sample * 2) >= requestedWidth && height / (sample * 2) >= requestedHeight) sample *= 2
    return sample
}

@Composable
internal fun ImagePreview(file: File, description: String, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val targetWidth = with(density) { maxWidth.roundToPx().coerceAtLeast(1) }
        val targetHeight = with(density) { maxHeight.roundToPx().coerceAtLeast(1) }
        var bitmap by remember(file, targetWidth, targetHeight) { mutableStateOf<Bitmap?>(null) }

        LaunchedEffect(file, targetWidth, targetHeight) {
            val decoded = withContext(Dispatchers.IO) {
                val candidate = decodeSampledImage(file, targetWidth, targetHeight)
                try {
                    coroutineContext.ensureActive()
                    candidate
                } catch (error: Throwable) {
                    candidate?.recycle()
                    throw error
                }
            }
            bitmap = decoded
        }
        bitmap?.let { decoded ->
            DisposableEffect(decoded) {
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
