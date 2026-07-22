package com.j2team.fileserver.feature.preview

import android.content.Context
import android.graphics.Bitmap
import com.j2team.fileserver.core.cache.AppCacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

private const val VIDEO_THUMBNAIL_MAX_EDGE = 320
private const val VIDEO_THUMBNAIL_JPEG_QUALITY = 75

object VideoThumbnailCache {
    fun exists(context: Context, profileId: String, remotePath: String): Boolean {
        val file = AppCacheManager.thumbnailFile(context, profileId, remotePath)
        return file.isFile && file.length() > 0L
    }

    suspend fun save(
        context: Context,
        profileId: String,
        remotePath: String,
        frame: Bitmap,
    ): Result<File> = withContext(Dispatchers.IO) {
        val destination = AppCacheManager.thumbnailFile(context, profileId, remotePath)
        var outputBitmap: Bitmap? = null
        try {
            check(frame.width > 0 && frame.height > 0)
            destination.parentFile?.mkdirs()
            val longestEdge = max(frame.width, frame.height)
            val thumbnail = if (longestEdge > VIDEO_THUMBNAIL_MAX_EDGE) {
                val scale = VIDEO_THUMBNAIL_MAX_EDGE.toFloat() / longestEdge
                Bitmap.createScaledBitmap(
                    frame,
                    (frame.width * scale).roundToInt().coerceAtLeast(1),
                    (frame.height * scale).roundToInt().coerceAtLeast(1),
                    true,
                )
            } else {
                frame
            }
            outputBitmap = thumbnail
            destination.outputStream().buffered().use { output ->
                check(thumbnail.compress(Bitmap.CompressFormat.JPEG, VIDEO_THUMBNAIL_JPEG_QUALITY, output))
            }
            AppCacheManager.recordWrite(context, destination)
            Result.success(destination)
        } catch (error: Throwable) {
            destination.delete()
            Result.failure(error)
        } finally {
            if (outputBitmap !== frame) outputBitmap?.recycle()
            frame.recycle()
        }
    }
}
