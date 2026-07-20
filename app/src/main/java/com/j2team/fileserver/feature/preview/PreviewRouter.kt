Exit code: 0
Wall time: 0.6 seconds
Output:
package com.j2team.fileserver.feature.preview

enum class PreviewKind { Image, Video, Audio, Text, Unsupported }

object PreviewRouter {
    fun kind(name: String): PreviewKind = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg", "png", "gif", "webp", "heic" -> PreviewKind.Image
        "mp4", "mkv", "webm", "mov" -> PreviewKind.Video
        "mp3", "wav", "flac", "m4a" -> PreviewKind.Audio
        "txt", "md", "json", "xml", "kt", "java", "log" -> PreviewKind.Text
        else -> PreviewKind.Unsupported
    }
}

