package com.j2team.fileserver.feature.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaTypeTest {
    @Test
    fun mapsSupportedVideoExtensionsCaseInsensitively() {
        val cases = mapOf(
            "mp4" to "video/mp4",
            "mkv" to "video/x-matroska",
            "mov" to "video/quicktime",
            "webm" to "video/webm",
            "wmv" to "video/x-ms-wmv",
            "avi" to "video/x-msvideo",
            "m2ts" to "video/mp2t",
            "mts" to "video/mp2t",
            "flv" to "video/x-flv",
            "3gp" to "video/3gpp",
            "mpeg" to "video/mpeg",
            "vob" to "video/dvd",
            "ogv" to "video/ogg",
        )

        cases.forEach { (extension, mimeType) ->
            assertEquals(mimeType, mediaMimeType("movie.$extension"))
            assertEquals(mimeType, mediaMimeType("movie.${extension.uppercase()}"))
        }
    }

    @Test
    fun mapsSupportedAudioExtensionsCaseInsensitively() {
        val cases = mapOf(
            "mp3" to "audio/mpeg",
            "aac" to "audio/aac",
            "flac" to "audio/flac",
            "wav" to "audio/wav",
            "ogg" to "audio/ogg",
            "opus" to "audio/ogg",
            "wma" to "audio/x-ms-wma",
            "amr" to "audio/amr",
            "aiff" to "audio/aiff",
            "mka" to "audio/x-matroska",
        )

        cases.forEach { (extension, mimeType) ->
            assertEquals(mimeType, mediaMimeType("track.$extension"))
            assertEquals(mimeType, mediaMimeType("track.${extension.uppercase()}"))
        }
    }

    @Test
    fun mapsTsAsTransportMimeWhileRouterPreservesTextFiles() {
        assertEquals("video/mp2t", mediaMimeType("client.ts"))
        assertEquals("video/mp2t", mediaMimeType("stream.ts", "video/mp2t"))
        assertEquals("video/mp2t", mediaMimeType("STREAM.TS", "video/mp2t"))
        assertEquals("video/mp2t", mediaMimeType("client.ts", "text/typescript"))
    }

    @Test
    fun buildsBoundedAuthenticatedReadOnlyStreamConfiguration() {
        val configuration = mediaStreamConfiguration("jwt-token")

        assertEquals("jwt-token", configuration.headers["X-Auth"])
        assertEquals(15_000, configuration.minBufferMs)
        assertEquals(50_000, configuration.maxBufferMs)
        assertEquals(2_500, configuration.bufferForPlaybackMs)
        assertEquals(5_000, configuration.bufferForPlaybackAfterRebufferMs)
    }
}
