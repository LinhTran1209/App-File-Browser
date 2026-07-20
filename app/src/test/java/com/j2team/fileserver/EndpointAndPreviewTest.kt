package com.j2team.fileserver

import com.j2team.fileserver.core.network.Endpoint
import com.j2team.fileserver.core.network.FileBrowserClient
import com.j2team.fileserver.core.model.ServerProfile
import com.j2team.fileserver.feature.preview.PreviewKind
import com.j2team.fileserver.feature.preview.PreviewRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointAndPreviewTest {
    @Test fun normalizesDefaultHttpPortAndPath() {
        val endpoint = Endpoint.normalize("192.168.1.10:8080/files")
        assertTrue(endpoint.isSuccess)
        assertEquals("http://192.168.1.10:8080/files/", endpoint.getOrThrow().url)
    }

    @Test fun routesMediaByExtension() {
        assertEquals(PreviewKind.Image, PreviewRouter.kind("photo.webp"))
        assertEquals(PreviewKind.Video, PreviewRouter.kind("clip.mp4"))
        assertEquals(PreviewKind.Text, PreviewRouter.kind("notes.md"))
    }

    @Test fun rawResourceUrlEncodesPathSegments() {
        val profile = ServerProfile(
            id = "pi",
            displayName = "Pi",
            scheme = "http",
            host = "192.168.10.37",
            port = 8888,
            basePath = "/",
        )
        assertEquals(
            "http://192.168.10.37:8888/api/raw/Media/My%20Video.mp4",
            FileBrowserClient().rawUrl(profile, "/Media/My Video.mp4"),
        )
    }
}
