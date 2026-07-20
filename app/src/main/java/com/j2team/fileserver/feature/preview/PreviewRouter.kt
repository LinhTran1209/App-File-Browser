package com.j2team.fileserver.feature.preview

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

enum class PreviewKind { Image, Pdf, Video, Audio, Text, Unsupported }

object PreviewRouter {
    private val image = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heif", "heic", "avif")
    private val video = setOf("mp4", "m4v", "mkv", "mov", "webm", "wmv", "avi", "m2ts", "mts", "ts", "flv", "3gp", "3g2", "mpeg", "mpg", "vob", "ogv")
    private val audio = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "amr", "aiff", "aif", "mka")
    private val text = setOf(
        "txt", "md", "markdown", "py", "pyw", "json", "xml", "yaml", "yml", "toml", "ini", "conf", "cfg", "log", "csv", "tsv",
        "kt", "kts", "java", "c", "h", "cpp", "hpp", "cs", "js", "jsx", "ts", "tsx", "html", "htm", "css", "scss", "sql",
        "sh", "bash", "zsh", "ps1", "bat", "gradle", "properties", "env", "dockerfile",
    )
    private val extensionlessTextNames = setOf("readme", "license", "copying", "notice", "dockerfile", "makefile", "procfile", "gemfile", "rakefile")

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun kind(name: String): PreviewKind = when (extension(name)) {
        in image -> PreviewKind.Image
        "pdf" -> PreviewKind.Pdf
        in text -> PreviewKind.Text
        in video -> PreviewKind.Video
        in audio -> PreviewKind.Audio
        else -> if (name.substringAfterLast('/').lowercase() in extensionlessTextNames) PreviewKind.Text else PreviewKind.Unsupported
    }

    /** Uses a UTF-8 sample only for extensionless files; known extensions always win. */
    fun kind(name: String, sample: ByteArray): PreviewKind {
        if (extension(name).isEmpty()) return if (sample.isLikelyUtf8Text()) PreviewKind.Text else PreviewKind.Unsupported
        val named = kind(name)
        if (named != PreviewKind.Unsupported || extension(name).isNotEmpty()) return named
        return if (sample.isLikelyUtf8Text()) PreviewKind.Text else PreviewKind.Unsupported
    }

    /** `.ts` defaults to TypeScript; a server-declared MPEG transport MIME overrides that ambiguous suffix. */
    fun kind(name: String, declaredMimeType: String?): PreviewKind = when (declaredMimeType?.lowercase()) {
        "video/mp2t" -> PreviewKind.Video
        "text/typescript", "application/typescript" -> PreviewKind.Text
        else -> kind(name)
    }

    fun kind(name: String, sample: ByteArray, declaredMimeType: String?): PreviewKind {
        if (declaredMimeType?.lowercase() == "video/mp2t") return PreviewKind.Video
        if (extension(name).isEmpty()) return kind(name, sample)
        return kind(name, declaredMimeType)
    }

    fun mimeType(name: String): String = when (extension(name)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "heif" -> "image/heif"
        "heic" -> "image/heic"
        "bmp" -> "image/bmp"
        "avif" -> "image/avif"
        "pdf" -> "application/pdf"
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "wmv" -> "video/x-ms-wmv"
        "avi" -> "video/x-msvideo"
        "3gp", "3g2" -> "video/3gpp"
        "mpeg", "mpg" -> "video/mpeg"
        "m2ts", "mts" -> "video/mp2t"
        "flv" -> "video/x-flv"
        "vob" -> "video/dvd"
        "ogv" -> "video/ogg"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "m4a" -> "audio/mp4"
        "ogg", "opus" -> "audio/ogg"
        "aac" -> "audio/aac"
        "wma" -> "audio/x-ms-wma"
        "amr" -> "audio/amr"
        "aiff", "aif" -> "audio/aiff"
        "mka" -> "audio/x-matroska"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "csv" -> "text/csv"
        "tsv" -> "text/tab-separated-values"
        "ts" -> "text/typescript"
        "md", "markdown" -> "text/markdown"
        "yaml", "yml" -> "text/yaml"
        in text -> "text/plain"
        "" -> "text/plain"
        else -> "application/octet-stream"
    }

    fun canPreview(name: String): Boolean = kind(name) != PreviewKind.Unsupported

    private fun ByteArray.isLikelyUtf8Text(): Boolean {
        if (any { it == 0.toByte() }) return false
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(this))
            true
        } catch (_: CharacterCodingException) {
            false
        }
    }
}
