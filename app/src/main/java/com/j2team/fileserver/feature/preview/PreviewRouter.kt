Exit code: 0
Wall time: 0.5 seconds
Output:
package com.j2team.fileserver.feature.preview

enum class PreviewKind { Image, Video, Audio, Text, Unsupported }

object PreviewRouter {
    private val image = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "avif")
    private val video = setOf("mp4", "mkv", "webm", "mov", "avi", "m4v", "3gp")
    private val audio = setOf("mp3", "wav", "flac", "m4a", "ogg", "aac", "opus")
    private val text = setOf("txt", "md", "json", "xml", "kt", "java", "log", "csv", "yaml", "yml", "properties")

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun kind(name: String): PreviewKind = when (extension(name)) {
        in image -> PreviewKind.Image
        in video -> PreviewKind.Video
        in audio -> PreviewKind.Audio
        in text -> PreviewKind.Text
        else -> PreviewKind.Unsupported
    }

    fun mimeType(name: String): String = when (extension(name)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        "bmp" -> "image/bmp"
        "avif" -> "image/avif"
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "m4a" -> "audio/mp4"
        "ogg", "opus" -> "audio/ogg"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "csv" -> "text/csv"
        "md", "txt", "log", "kt", "java", "yaml", "yml", "properties" -> "text/plain"
        else -> "application/octet-stream"
    }

    fun canPreview(name: String): Boolean = kind(name) != PreviewKind.Unsupported
}

