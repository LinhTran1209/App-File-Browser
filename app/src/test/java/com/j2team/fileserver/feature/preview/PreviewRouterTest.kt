package com.j2team.fileserver.feature.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewRouterTest {
    @Test
    fun routesEverySupportedExtensionCaseInsensitively() {
        val cases = mapOf(
            PreviewKind.Image to listOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heif", "heic", "avif"),
            PreviewKind.Pdf to listOf("pdf"),
            PreviewKind.Text to listOf(
                "txt", "md", "markdown", "py", "pyw", "json", "xml", "yaml", "yml", "toml", "ini", "conf", "cfg",
                "log", "csv", "tsv", "kt", "kts", "java", "c", "h", "cpp", "hpp", "cs", "js", "jsx", "ts", "tsx",
                "html", "htm", "css", "scss", "sql", "sh", "bash", "zsh", "ps1", "bat", "gradle", "properties", "env",
                "dockerfile",
            ),
            // `.ts` is ambiguous (TypeScript vs. MPEG transport stream); extension-only routing treats it as source text.
            PreviewKind.Video to listOf("mp4", "m4v", "mkv", "mov", "webm", "wmv", "avi", "m2ts", "mts", "flv", "3gp", "3g2", "mpeg", "mpg", "vob", "ogv"),
            PreviewKind.Audio to listOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "amr", "aiff", "aif", "mka"),
        )

        cases.forEach { (kind, extensions) ->
            extensions.forEach { extension ->
                assertEquals(kind, PreviewRouter.kind("sample.$extension"))
                assertEquals(kind, PreviewRouter.kind("sample.${extension.uppercase()}"))
            }
        }
    }

    @Test
    fun routesExtensionlessNamesAsText() {
        assertEquals(PreviewKind.Text, PreviewRouter.kind("README"))
        assertEquals(PreviewKind.Text, PreviewRouter.kind("Dockerfile"))
        assertTrue(PreviewRouter.canPreview("LICENSE"))
    }

    @Test
    fun detectsExtensionlessUtf8TextWithoutRoutingBinaryAsText() {
        assertEquals(PreviewKind.Text, PreviewRouter.kind("server-output", "ready\n".toByteArray()))
        assertEquals(PreviewKind.Unsupported, PreviewRouter.kind("server-output", byteArrayOf(0, 1, 2)))
    }

    @Test
    fun resolvesAmbiguousTsUsingDeclaredMimeType() {
        assertEquals(PreviewKind.Text, PreviewRouter.kind("client.ts"))
        assertEquals(PreviewKind.Video, PreviewRouter.kind("stream.ts", "video/mp2t"))
        assertEquals(PreviewKind.Video, PreviewRouter.kind("stream.ts", "application/octet-stream"))
        assertEquals(PreviewKind.Text, PreviewRouter.kind("client.ts", "text/typescript"))
        assertEquals("text/typescript", PreviewRouter.mimeType("client.ts"))
    }

    @Test
    fun combinesBoundedSampleAndDeclaredMimeForActualPreviewRouting() {
        assertEquals(PreviewKind.Text, PreviewRouter.kind("server-output", "ready\n".toByteArray(), "text/plain"))
        assertEquals(PreviewKind.Unsupported, PreviewRouter.kind("server-output", byteArrayOf(0, 1), "application/octet-stream"))
        assertEquals(PreviewKind.Unsupported, PreviewRouter.kind("README", byteArrayOf(0, 1), "application/octet-stream"))
        assertEquals(PreviewKind.Video, PreviewRouter.kind("stream.ts", "binary".toByteArray(), "video/mp2t"))
    }

    @Test
    fun prefersSpecificTransportMimeOverGenericProbeMime() {
        assertEquals("video/mp2t", PreviewRouter.preferredMimeType("video/mp2t", "application/octet-stream"))
        assertEquals("video/mp2t", PreviewRouter.preferredMimeType("application/octet-stream", "video/mp2t"))
    }

    @Test
    fun suppliesConsistentMimeTypes() {
        assertEquals("application/pdf", PreviewRouter.mimeType("manual.PDF"))
        assertEquals("text/markdown", PreviewRouter.mimeType("notes.md"))
        assertEquals("application/octet-stream", PreviewRouter.mimeType("archive.zip"))
    }
}
