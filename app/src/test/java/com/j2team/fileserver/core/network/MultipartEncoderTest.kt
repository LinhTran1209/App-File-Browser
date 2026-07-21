package com.j2team.fileserver.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import com.j2team.fileserver.core.model.ServerProfile
import com.sun.net.httpserver.HttpServer

class MultipartEncoderTest {
    @Test
    fun writesOneFilePartEscapesFilenameAndEndsWithBoundary() {
        val output = ByteArrayOutputStream()
        val progress = mutableListOf<Pair<Long, Long>>()

        val total = MultipartEncoder.write(
            output = output,
            boundary = "boundary",
            fileName = "quo\"te\\name.txt",
            input = "data".byteInputStream(),
            fileSize = 4,
            onProgress = { sent, all -> progress += sent to all },
        )

        val encoded = output.toString(Charsets.UTF_8.name())
        assertEquals(1, Regex("name=\\\"file\\\"").findAll(encoded).count())
        assertTrue(encoded.contains("filename=\"quo%22te%5Cname.txt\""))
        assertTrue(encoded.endsWith("\r\n--boundary--\r\n"))
        assertEquals(encoded.toByteArray().size.toLong(), total)
        assertTrue(progress.isNotEmpty())
        assertTrue(progress.all { (sent, all) -> sent <= all })
    }

    @Test
    fun filenameEscapingDoesNotAlterEncodedDestinationPath() {
        assertEquals("/api/resources/Media/50%25%20off/%23tag", FileBrowserClient.encodedResourcePath("/Media/50% off/#tag"))
        assertFalse(MultipartEncoder.escapeFilename("safe.txt").contains('"'))
    }

    @Test
    fun uploadUsesExactEncodedDestinationAndReportsNon2xxFailure() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                assertEquals("/api/resources/Media/50%25%20off/%23tag", exchange.requestURI.rawPath)
                assertTrue(exchange.requestBody.readBytes().toString(Charsets.UTF_8).contains("name=\"file\""))
                exchange.sendResponseHeaders(403, 9)
                exchange.responseBody.use { it.write("forbidden".toByteArray()) }
            }
            start()
        }
        val file = File.createTempFile("multipart", ".txt").apply { writeText("data") }
        try {
            val profile = ServerProfile("test", "Test", "http", "127.0.0.1", server.address.port, "/")
            val result = FileBrowserClient().upload(profile, "token", "/Media/50% off/#tag", file)

            assertFalse(result.isSuccess)
        } finally {
            file.delete()
            server.stop(0)
        }
    }
}
