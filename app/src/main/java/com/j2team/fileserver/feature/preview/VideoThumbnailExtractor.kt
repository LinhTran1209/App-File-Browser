package com.j2team.fileserver.feature.preview

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.j2team.fileserver.core.model.RemoteResource
import com.j2team.fileserver.core.model.ServerProfile
import com.j2team.fileserver.core.session.SessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object VideoThumbnailExtractor {
    suspend fun extract(
        profile: ServerProfile,
        item: RemoteResource,
        sessionRepository: SessionRepository,
        destination: File,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val token = sessionRepository.streamingToken(profile).getOrThrow()
            destination.parentFile?.mkdirs()
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(
                    sessionRepository.rawUrl(profile, item.path),
                    mapOf("X-Auth" to token),
                )
                val durationMs = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?: error("Video duration is unavailable")
                val frame = retriever.getFrameAtTime(
                    durationMs * 1_000L / 4L,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                ) ?: error("Video frame is unavailable")
                try {
                    destination.outputStream().buffered().use { output ->
                        check(frame.compress(Bitmap.CompressFormat.JPEG, 82, output))
                    }
                } finally {
                    frame.recycle()
                }
                check(destination.length() > 0L)
                destination
            } finally {
                retriever.release()
            }
        }.onFailure { destination.delete() }
    }
}
