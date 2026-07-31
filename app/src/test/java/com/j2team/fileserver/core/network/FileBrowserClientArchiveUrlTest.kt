package com.j2team.fileserver.core.network

import com.j2team.fileserver.core.model.ServerProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileBrowserClientArchiveUrlTest {
    private val profile = ServerProfile("pi", "Pi", "http", "192.168.10.37", 8888)
    private val client = FileBrowserClient()

    @Test
    fun singleFolderUsesItsRawResourcePath() {
        assertEquals(
            "http://192.168.10.37:8888/api/raw/media/photos?algo=zip",
            client.archiveUrl(profile, listOf("/media/photos"), "zip"),
        )
    }

    @Test
    fun multipleItemsUseBulkFilesQuery() {
        val url = client.archiveUrl(profile, listOf("/media/a", "/media/b"), "targz")

        assertTrue(url.startsWith("http://192.168.10.37:8888/api/raw/?files="))
        assertTrue(url.endsWith("&algo=targz"))
    }
}
