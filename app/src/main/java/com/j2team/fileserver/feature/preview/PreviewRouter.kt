package com.j2team.fileserver.feature.preview

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

enum class PreviewKind { Image, Pdf, Video, Audio, Text, Comic, Unsupported }

object PreviewRouter {
    private val image = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heif", "heic", "avif")
    private val comic = setOf("cbz", "cbr", "cbt", "cb7")
    private val video = setOf("mp4", "m4v", "mkv", "mov", "webm", "wmv", "avi", "m2ts", "mts", "ts", "flv", "3gp", "3g2", "mpeg", "mpg", "vob", "ogv")
    private val audio = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "amr", "aiff", "aif", "mka")
    private val text = setOf(
        "txt", "md", "markdown", "py", "pyw", "json", "xml", "yaml", "yml", "toml", "ini", "conf", "cfg", "log", "csv", "tsv",
        "kt", "kts", "java", "c", "h", "cpp", "hpp", "cs", "js", "jsx", "ts", "tsx", "html", "htm", "css", "scss", "sql",
        "sh", "bash", "zsh", "ps1", "bat", "gradle", "properties", "env", "dockerfile", "ipynb",
        "r", "rb", "php", "go", "rs", "swift", "dart", "lua", "pl", "vue", "svelte", "astro",
        "graphql", "gql", "lock", "gitignore", "gitattributes", "editorconfig", "npmrc", "yarnrc",
        "npmignore", "dockerignore", "make", "mk", "cmake", "tex", "rst", "manifest",
    )
    private val extensionlessTextNames = setOf("readme", "license", "copying", "notice", "dockerfile", "makefile", "procfile", "gemfile", "rakefile")

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun kind(name: String): PreviewKind = when (extension(name)) {
        in image -> PreviewKind.Image
        in comic -> PreviewKind.Comic
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

    /** Missing/generic MIME for `.ts` listings means transport stream; explicit text MIME keeps TypeScript as text. */
    fun kind(name: String, declaredMimeType: String?): PreviewKind {
        val mimeType = declaredMimeType?.substringBefore(';')?.trim()?.lowercase()
        return when {
            mimeType == "video/mp2t" -> PreviewKind.Video
            mimeType in setOf("text/typescript", "application/typescript") -> PreviewKind.Text
            extension(name) == "ts" && (mimeType == null || mimeType == "application/octet-stream") -> PreviewKind.Video
            else -> kind(name)
        }
    }

    fun kind(name: String, sample: ByteArray, declaredMimeType: String?): PreviewKind {
        if (declaredMimeType?.substringBefore(';')?.trim()?.lowercase() == "video/mp2t") return PreviewKind.Video
        if (extension(name) == "ts") {
            if (sample.isMpegTransportStream()) return PreviewKind.Video
            return if (sample.isLikelyUtf8Text()) PreviewKind.Text else PreviewKind.Video
        }
        if (extension(name).isEmpty()) return kind(name, sample)
        return kind(name, declaredMimeType)
    }

    fun resolvedMimeType(name: String, kind: PreviewKind, declaredMimeType: String?): String =
        if (extension(name) == "ts" && kind == PreviewKind.Video) {
            "video/mp2t"
        } else {
            declaredMimeType?.substringBefore(';')?.trim()?.lowercase() ?: mimeType(name)
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
        "cbz" -> "application/vnd.comicbook+zip"
        "cbr" -> "application/vnd.comicbook-rar"
        "cbt" -> "application/vnd.comicbook+tar"
        "cb7" -> "application/x-cb7"
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

    fun preferredMimeType(listed: String?, probed: String?): String? {
        val normalizedProbe = probed?.substringBefore(';')?.trim()?.lowercase()
        val normalizedListed = listed?.substringBefore(';')?.trim()?.lowercase()
        return when {
            normalizedProbe == "video/mp2t" -> normalizedProbe
            normalizedListed == "video/mp2t" -> normalizedListed
            normalizedProbe != null && normalizedProbe !in setOf("application/octet-stream", "text/plain") -> normalizedProbe
            else -> normalizedListed ?: normalizedProbe
        }
    }

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

    private fun ByteArray.isMpegTransportStream(): Boolean {
        if (size < 188 * 3) return false
        val lastStart = minOf(187, size - (188 * 2) - 1)
        return (0..lastStart).any { offset ->
            this[offset] == 0x47.toByte() &&
                this[offset + 188] == 0x47.toByte() &&
                this[offset + (188 * 2)] == 0x47.toByte()
        }
    }
}
